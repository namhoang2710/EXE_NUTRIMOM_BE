package vn.nutrimom.admin.service;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import vn.nutrimom.admin.dto.AdminExpertPerformanceDtos.*;
import vn.nutrimom.admin.dto.AdminOperationsDtos.*;
import vn.nutrimom.admin.dto.AdminOperationsDtos.Period;
import vn.nutrimom.admin.model.*;
import vn.nutrimom.admin.realtime.AdminRealtimeRepository;
import vn.nutrimom.admin.repository.*;
import vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.*;
import vn.nutrimom.admin.repository.ExpertScheduleKpiRepository.Schedule;
import vn.nutrimom.common.exception.*;

/** Version 3: calendar schedule availability and authenticated server entry actions. */
@Service
public class ExpertScheduleKpiService {
    private final ExpertScheduleKpiRepository schedules;
    private final AdminExpertPerformanceRepository consultations;
    private final AdminOperationsRepository operations;
    private final AdminRealtimeRepository realtime;
    private final Clock clock;
    public ExpertScheduleKpiService(ExpertScheduleKpiRepository schedules,AdminExpertPerformanceRepository consultations,
            AdminOperationsRepository operations,AdminRealtimeRepository realtime,Clock clock) {
        this.schedules=schedules;this.consultations=consultations;this.operations=operations;this.realtime=realtime;this.clock=clock;
    }
    public record ScheduleSummary(String unit,long standardSlots,long availableSlots,long offSlots,long dayOffDays,long closedSlots) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CheckinSummary(long eligibleConsultations,long checkedInConsultations,long onTimeConsultations,
                                  long lateConsultations,long notCheckedInConsultations,long unknownConsultations) { }
    public record KpiRules(String attendanceEvidence,String attendanceUnit,String attendanceAggregation,String scheduleOpeningTime,
                            String scheduleClosingTime,int slotMinutes,String punctualityEvidence,int entryEarlyMinutes,
                            int onTimeGraceMinutes,String punctualityCohort,String comparisonBasis,int satisfiedMinimumStars) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Coverage(LocalDate earliestExpertDate,OffsetDateTime checkinTrackingStartedAt,String historicalCheckinPolicy,
                           LocalDate filterMinDate,LocalDate currentEffectiveFromDate,LocalDate previousEffectiveFromDate,
                           boolean currentPeriodComplete,boolean previousPeriodComplete,String comparisonStatus) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(OffsetDateTime snapshotAt,String timezone,Period period,String specialty,SelectedExpert expert,
                            KpiRules rules,List<Axis> axes,Summary summary,ScheduleSummary scheduleSummary,CheckinSummary checkinSummary,
                            Coverage dataCoverage,String realtimeRevision,int calculationVersion) { }
    public record ExpertRow(SelectedExpert expert,Summary summary,ScheduleSummary scheduleSummary,CheckinSummary checkinSummary,List<Axis> axes,Coverage dataCoverage) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public Snapshot snapshot(LocalDate from,LocalDate to,String specialty,String expert,boolean extended) {
        var period=ExpertKpiPeriod.resolve(from,to,clock);
        specialty=AdminQuery.option("specialty",specialty,AdminOperationsService.SPECIALTIES,null);
        expert=AdminQuery.id("expertUserId",expert);
        var now=OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var params=parameters(now,period,specialty,expert);
        var experts=consultations.experts(specialty,expert,params);
        if(expert!=null && experts.total()==0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var window=schedules.window(params);
        if(from!=null && window.filterMinDate()!=null && from.isBefore(window.filterMinDate()))
            throw AdminQuery.invalid("fromDate","Ngày bắt đầu sớm nhất có lịch làm việc chuyên gia trong DB là "+window.filterMinDate()+".");
        var coverage=coverage(window.earliestExpertDate(),window.filterMinDate(),window.trackingStartedAt(),period);
        var work=schedules.counts(params,false);
        var counts=consultations.countsV3(specialty,expert,params,false);
        var extras=extended?consultations.extendedCounts(specialty,expert,params,true,true):List.<ExtendedCounts>of();
        var c=presence(counts,"CURRENT");var p=presence(counts,"PREVIOUS");
        var currentWork=schedule(work,"CURRENT");var previousWork=schedule(work,"PREVIOUS");
        return new Snapshot(now,AdminQuery.ZONE.getId(),period,specialty,experts.selected(),
                new KpiRules("WORK_SCHEDULE","HALF_HOUR_SLOTS","SUM_AVAILABLE_DIVIDED_BY_SUM_STANDARD","08:00","20:00",30,
                    "SERVER_ENTRY_ACTION",5,3,"SCHEDULED_START_PLUS_GRACE_ELAPSED",
                    isMonth(period)?"PREVIOUS_CALENDAR_MONTH":"PREVIOUS_EQUAL_LENGTH_PERIOD",4),
                axes(currentWork,previousWork,c,p,extra(extras,"CURRENT"),extra(extras,"PREVIOUS"),extended,coverage),
                summary(experts.total(),experts.active(),c),workSummary(currentWork),checkinSummary(c),
                coverage,realtime.dashboardRevision(),3);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
    public Report report(LocalDate from,LocalDate to,String specialty,String expert,int page,int size,boolean export) {
        AdminQuery.page(page,export?100:size);
        var base=snapshot(from,to,specialty,expert,true);
        long total=base.summary().totalExperts();
        if(export && total>10000) throw AdminQuery.invalid("specialty","Báo cáo vượt 10000 dòng. Vui lòng thu hẹp bộ lọc.");
        var p=parameters(base.snapshotAt(),base.period(),base.specialty(),base.expert()==null?null:base.expert().userId());
        var profiles=consultations.reportExperts(base.specialty(),expert,p,(page-1)*size,size);
        var items=new ArrayList<ExpertRow>();
        if(!profiles.isEmpty()) {
            p.addValue("filterExpertIds",true).addValue("expertIds",profiles.stream().map(SelectedExpert::userId).toList());
            var work=schedules.counts(p,true);var counts=consultations.countsV3(base.specialty(),expert,p,true);
            var extended=consultations.extendedPerExpert(base.specialty(),expert,p,true);
            var works=new HashMap<String,Schedule>();work.forEach(r->works.put(r.expertId()+r.period(),r));
            var allCounts=new HashMap<String,PresenceCounts>();counts.forEach(r->allCounts.put(r.expertId()+r.period(),r));
            var extras=new HashMap<String,ExtendedCounts>();extended.forEach(r->extras.put(r.expertId()+r.counts().period(),r.counts()));
            for(var profile:profiles) {
                String id=profile.userId();var c=allCounts.getOrDefault(id+"CURRENT",empty("CURRENT"));var prev=allCounts.getOrDefault(id+"PREVIOUS",empty("PREVIOUS"));
                var cw=works.getOrDefault(id+"CURRENT",Schedule.empty("CURRENT"));var pw=works.getOrDefault(id+"PREVIOUS",Schedule.empty("PREVIOUS"));
                var coverage=coverage(profile.profileCreatedAt().atZoneSameInstant(AdminQuery.ZONE).toLocalDate(),
                    base.dataCoverage().filterMinDate(),base.dataCoverage().checkinTrackingStartedAt(),base.period());
                items.add(new ExpertRow(profile,summary(1,profile.expertStatus().equals("ACTIVE") && profile.accountStatus().equals("ACTIVE")?1:0,c),
                    workSummary(cw),checkinSummary(c),axes(cw,pw,c,prev,extras.getOrDefault(id+"CURRENT",ExtendedCounts.empty("CURRENT")),
                        extras.getOrDefault(id+"PREVIOUS",ExtendedCounts.empty("PREVIOUS")),true,coverage),coverage));
            }
        }
        return new Report("EXPERT_PERFORMANCE",base.snapshotAt(),base.timezone(),base.period(),Map.of("snapshot",base),List.copyOf(items),page,size,total,(int)((total+size-1)/size));
    }
    private org.springframework.jdbc.core.namedparam.MapSqlParameterSource parameters(OffsetDateTime now,Period period,String specialty,String expert) {
        return operations.parameters(now).addValue("specialty",specialty,java.sql.Types.VARCHAR).addValue("expert",expert,java.sql.Types.VARCHAR)
                .addValue("fromDate",period.fromDate()).addValue("toDate",period.toDate()).addValue("previousFrom",period.previousFromDate())
                .addValue("previousStart",period.previousFromDate().atStartOfDay(AdminQuery.ZONE).toOffsetDateTime())
                .addValue("until",period.toDate().plusDays(1).atStartOfDay(AdminQuery.ZONE).toOffsetDateTime())
                .addValue("filterExpertIds",false).addValue("expertIds",List.of(""));
    }
    private static boolean isMonth(Period p) {return p.fromDate().getDayOfMonth()==1 && p.toDate().equals(YearMonth.from(p.fromDate()).atEndOfMonth());}
    private static Coverage coverage(LocalDate earliest,LocalDate minimum,OffsetDateTime tracking,Period period) {
        LocalDate current=effectiveFrom(earliest,period.fromDate(),period.toDate());
        LocalDate previous=effectiveFrom(earliest,period.previousFromDate(),period.previousToDate());
        boolean currentComplete=current!=null && current.equals(period.fromDate());
        boolean previousComplete=previous!=null && previous.equals(period.previousFromDate());
        String status=current==null?"NO_CURRENT_PERIOD_DATA":!currentComplete?"PARTIAL_CURRENT_PERIOD":
            previous==null?"NO_PREVIOUS_PERIOD_DATA":!previousComplete?"PARTIAL_PREVIOUS_PERIOD":"COMPLETE";
        return new Coverage(earliest,tracking,"UNKNOWN_UNLESS_SERVER_CHECKIN_EXISTS",minimum,current,previous,currentComplete,previousComplete,status);
    }
    private static LocalDate effectiveFrom(LocalDate earliest,LocalDate from,LocalDate to) {
        return earliest==null || earliest.isAfter(to)?null:earliest.isAfter(from)?earliest:from;
    }
    private static PresenceCounts empty(String period) {return new PresenceCounts(period,null,Counts.empty(period),0,0,0,0);}
    private static PresenceCounts presence(List<PresenceCounts> rows,String period) {return rows.stream().filter(r->r.period().equals(period)).findFirst().orElse(empty(period));}
    private static Schedule schedule(List<Schedule> rows,String period) {return rows.stream().filter(r->r.period().equals(period)).findFirst().orElse(Schedule.empty(period));}
    private static ExtendedCounts extra(List<ExtendedCounts> rows,String period) {return rows.stream().filter(r->r.period().equals(period)).findFirst().orElse(ExtendedCounts.empty(period));}
    private static ScheduleSummary workSummary(Schedule s) {return new ScheduleSummary("HALF_HOUR_SLOTS",s.standardSlots(),s.availableSlots(),s.offSlots(),s.dayOffDays(),s.closedSlots());}
    private static CheckinSummary checkinSummary(PresenceCounts c) {return new CheckinSummary(c.known()+c.unknown(),c.counts().attended(),c.counts().onTime(),c.late(),c.absent(),c.unknown());}
    private static Summary summary(long total,long active,PresenceCounts c) {
        var n=c.counts();
        return new Summary(total,active,n.eligible(),n.completed(),n.observed(),n.ratings(),n.ratings()==0?null:
                BigDecimal.valueOf(n.ratingSum()).divide(BigDecimal.valueOf(n.ratings()),2,RoundingMode.HALF_UP),null,"NOT_TRACKED");
    }
    private static Value value(long numerator,long denominator,long missing,String noData) {
        return new Value(numerator,denominator,denominator==0?null:BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP),denominator==0?noData:missing>0?"PARTIAL_DATA":"AVAILABLE",missing);
    }
    private static Value metric(String key,Schedule s,PresenceCounts c,ExtendedCounts e) {
        return switch(key) {
            case "ATTENDANCE"->value(s.availableSlots(),s.standardSlots(),0,"NO_WORK_SCHEDULE");
            case "PUNCTUALITY"->value(c.counts().onTime(),c.known(),c.unknown(),c.unknown()>0?"NO_CHECKIN_EVIDENCE":"NO_ELIGIBLE_CONSULTATIONS");
            case "SATISFACTION"->{
                var rated=value(c.counts().satisfied(),c.counts().ratings(),0,"NO_REVIEWS");
                yield new Value(rated.numerator(),rated.denominator(),rated.percent(),rated.status(),c.counts().completed()-c.counts().ratings());
            }
            case "COMPLETION"->value(c.counts().completed(),c.counts().eligible(),0,"NO_ELIGIBLE_CONSULTATIONS");
            case "TIMELY_ACCEPTANCE"->value(e.acceptedInTime(),e.acceptanceEligible(),e.acceptanceMissing(),e.acceptanceMissing()>0?"NO_ACCEPTANCE_EVIDENCE":"NO_ELIGIBLE_REQUESTS");
            default->value(e.returningCustomers(),e.customers(),0,"NO_COMPLETED_CUSTOMERS");
        };
    }
    private static List<Axis> axes(Schedule cw,Schedule pw,PresenceCounts c,PresenceCounts p,ExtendedCounts ce,ExtendedCounts pe,boolean extended,Coverage coverage) {
        var keys=List.of("ATTENDANCE","PUNCTUALITY","SATISFACTION","COMPLETION","TIMELY_ACCEPTANCE","RETURNING_CUSTOMERS");
        var labels=List.of("Chuyên cần","Đúng giờ","Hài lòng","Hoàn thành","Tiếp nhận đúng hạn","Khách quay lại");
        var result=new ArrayList<Axis>();
        for(int i=0;i<(extended?6:4);i++) {
            String key=keys.get(i);var cv=metric(key,cw,c,ce);var pv=metric(key,pw,p,pe);
            result.add(new Axis(key,labels.get(i),cv,pv,coverage.currentPeriodComplete() && coverage.previousPeriodComplete() && cv.percent()!=null && pv.percent()!=null
                    && cv.status().equals("AVAILABLE") && pv.status().equals("AVAILABLE")?cv.percent().subtract(pv.percent()):null));
        }
        return List.copyOf(result);
    }
}

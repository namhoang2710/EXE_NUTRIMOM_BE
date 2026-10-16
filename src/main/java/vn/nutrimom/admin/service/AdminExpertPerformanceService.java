package vn.nutrimom.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.admin.dto.AdminExpertPerformanceDtos.*;
import vn.nutrimom.admin.model.AdminQuery;
import vn.nutrimom.admin.realtime.AdminRealtimeRepository;
import vn.nutrimom.admin.repository.AdminExpertPerformanceRepository;
import vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.Counts;
import vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.ExtendedCounts;
import vn.nutrimom.admin.repository.AdminOperationsRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

@Service
public class AdminExpertPerformanceService {
    private final AdminExpertPerformanceRepository repository;
    private final AdminOperationsRepository operations;
    private final AdminRealtimeRepository realtime;
    private final Clock clock;
    private final int acceptanceMinutes;
    public AdminExpertPerformanceService(AdminExpertPerformanceRepository repository,AdminOperationsRepository operations,
                                         AdminRealtimeRepository realtime,Clock clock) {
        this(repository,operations,realtime,clock,30);
    }
    @Autowired
    public AdminExpertPerformanceService(AdminExpertPerformanceRepository repository,AdminOperationsRepository operations,
                                         AdminRealtimeRepository realtime,Clock clock,
                                         @org.springframework.beans.factory.annotation.Value("${app.admin.expert-performance.acceptance-sla-minutes:30}") int acceptanceMinutes) {
        if(acceptanceMinutes<1 || acceptanceMinutes>1440)throw new IllegalArgumentException("Acceptance SLA must be 1..1440 minutes");
        this.acceptanceMinutes=acceptanceMinutes;
        this.repository=repository;this.operations=operations;this.realtime=realtime;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public Performance snapshot(LocalDate from,LocalDate to,String specialty,String expert) {
        return snapshot(from,to,specialty,expert,false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public Performance snapshot(LocalDate from,LocalDate to,String specialty,String expert,boolean includeExtendedMetrics) {
        var period=AdminQuery.period(from,to,clock,true,false);
        String selectedSpecialty=AdminQuery.option("specialty",specialty,AdminOperationsService.SPECIALTIES,null);
        String selectedExpert=AdminQuery.id("expertUserId",expert);
        var now=OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var parameters=operations.parameters(now).addValue("fromDate",period.fromDate()).addValue("toDate",period.toDate())
                .addValue("previousFrom",period.previousFromDate()).addValue("specialty",selectedSpecialty).addValue("expert",selectedExpert)
                .addValue("previousStart",period.previousFromDate().atStartOfDay(AdminQuery.ZONE).toOffsetDateTime())
                .addValue("until",period.toDate().plusDays(1).atStartOfDay(AdminQuery.ZONE).toOffsetDateTime())
                .addValue("acceptanceMinutes",acceptanceMinutes);
        var experts=repository.experts(selectedSpecialty,selectedExpert,parameters);
        if(selectedExpert!=null && experts.total()==0)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var rows=repository.counts(selectedSpecialty,selectedExpert,parameters);
        Counts current=rows.stream().filter(r->r.period().equals("CURRENT")).findFirst().orElse(Counts.empty("CURRENT"));
        Counts previous=rows.stream().filter(r->r.period().equals("PREVIOUS")).findFirst().orElse(Counts.empty("PREVIOUS"));
        var axes=new ArrayList<>(List.of(axis("ATTENDANCE","Chuyên cần",current,previous),axis("PUNCTUALITY","Đúng giờ",current,previous),
                axis("SATISFACTION","Hài lòng",current,previous),axis("COMPLETION","Hoàn thành",current,previous)));
        if(includeExtendedMetrics) {
            var extended=repository.extendedCounts(selectedSpecialty,selectedExpert,parameters);
            var c=extended.stream().filter(r->r.period().equals("CURRENT")).findFirst().orElse(ExtendedCounts.empty("CURRENT"));
            var p=extended.stream().filter(r->r.period().equals("PREVIOUS")).findFirst().orElse(ExtendedCounts.empty("PREVIOUS"));
            axes.add(extendedAxis("TIMELY_ACCEPTANCE","Tiếp nhận đúng hạn",c,p));
            axes.add(extendedAxis("RETURNING_CUSTOMERS","Khách quay lại",c,p));
        }
        return new Performance(now,AdminQuery.ZONE.getId(),period,selectedSpecialty,experts.selected(),
                new Rules(0,4,"SCHEDULED_END_AT","COMPLETED_OR_VERIFIED_ROOM_JOIN","PREVIOUS_EQUAL_LENGTH_PERIOD",
                        acceptanceMinutes,"QUEUE_OPENED_AT","RANDOM_QUEUE_TEAM_OR_CLAIMED_EXPERT","SAME_EXPERT_COMPLETED_BEFORE_VISIT_START",
                        "08:00","20:00",AdminQuery.ZONE.getId()),List.copyOf(axes),
                new Summary(experts.total(),experts.active(),current.eligible(),current.completed(),current.observed(),
                        current.ratings(),current.ratings()==0?null:BigDecimal.valueOf(current.ratingSum())
                        .divide(BigDecimal.valueOf(current.ratings()),2,RoundingMode.HALF_UP),null,"NOT_TRACKED"),
                realtime.dashboardRevision());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=10)
    public Performance snapshot(LocalDate from,LocalDate to,String specialty,String expert,boolean extended,int version) {
        if(version==1) return snapshot(from,to,specialty,expert,extended);
        if(version!=2) throw AdminQuery.invalid("calculationVersion","Chỉ hỗ trợ phiên bản 1 hoặc 2.");
        // Validate malformed filters before any database work.
        AdminQuery.period(from,to,clock,true,false);
        String selectedSpecialty=AdminQuery.option("specialty",specialty,AdminOperationsService.SPECIALTIES,null);
        String selectedExpert=AdminQuery.id("expertUserId",expert);
        var now=OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var parameters=operations.parameters(now).addValue("filterExpertIds",false).addValue("expertIds",List.of(""));
        var window=repository.dataWindow(parameters);
        LocalDate earliest=window.earliestScheduledDate();
        if(from!=null && earliest!=null && from.isBefore(earliest))
            throw AdminQuery.invalid("fromDate","Ngày bắt đầu sớm nhất có lịch trong DB là "+earliest+".");
        if(from==null && earliest!=null && earliest.isAfter(LocalDate.now(clock.withZone(AdminQuery.ZONE)).withDayOfMonth(1))) {
            from=earliest;to=LocalDate.now(clock.withZone(AdminQuery.ZONE));
        }
        var period=AdminQuery.period(from,to,clock,true,false);
        boolean previousComplete=earliest!=null && !period.previousFromDate().isBefore(earliest);
        parameters.addValue("fromDate",period.fromDate()).addValue("toDate",period.toDate())
                .addValue("previousFrom",period.previousFromDate()).addValue("specialty",selectedSpecialty).addValue("expert",selectedExpert)
                .addValue("previousStart",period.previousFromDate().atStartOfDay(AdminQuery.ZONE).toOffsetDateTime())
                .addValue("until",period.toDate().plusDays(1).atStartOfDay(AdminQuery.ZONE).toOffsetDateTime());
        var experts=repository.experts(selectedSpecialty,selectedExpert,parameters);
        if(selectedExpert!=null && experts.total()==0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var rows=repository.countsV2(selectedSpecialty,selectedExpert,parameters,false);
        var c=rows.stream().filter(r->r.period().equals("CURRENT")).findFirst().orElse(emptyPresence("CURRENT"));
        var p=rows.stream().filter(r->r.period().equals("PREVIOUS")).findFirst().orElse(emptyPresence("PREVIOUS"));
        var axes=new ArrayList<Axis>();
        for(String key:List.of("ATTENDANCE","PUNCTUALITY","SATISFACTION","COMPLETION")) {
            Value current=presenceValue(key,c),previous=presenceValue(key,p);
            String label=switch(key) {case "ATTENDANCE"->"Chuyên cần";case "PUNCTUALITY"->"Đúng giờ";
                case "SATISFACTION"->"Hài lòng";default->"Hoàn thành";};
            axes.add(new Axis(key,label,current,previous,previousComplete && current.status().equals("AVAILABLE")
                    && previous.status().equals("AVAILABLE")?current.percent().subtract(previous.percent()):null));
        }
        if(extended) {
            var extra=repository.extendedCounts(selectedSpecialty,selectedExpert,parameters,true);
            var ec=extra.stream().filter(r->r.period().equals("CURRENT")).findFirst().orElse(ExtendedCounts.empty("CURRENT"));
            var ep=extra.stream().filter(r->r.period().equals("PREVIOUS")).findFirst().orElse(ExtendedCounts.empty("PREVIOUS"));
            for(String key:List.of("TIMELY_ACCEPTANCE","RETURNING_CUSTOMERS")) {
                var a=extendedAxis(key,key.equals("TIMELY_ACCEPTANCE")?"Tiếp nhận đúng hạn":"Khách quay lại",ec,ep);
                axes.add(previousComplete?a:new Axis(a.key(),a.label(),a.current(),a.previous(),null));
            }
        }
        var count=c.counts();
        return new Performance(now,AdminQuery.ZONE.getId(),period,selectedSpecialty,experts.selected(),
                new Rules(3,4,"SCHEDULED_END_AT","VERIFIED_FIRST_ROOM_JOIN","PREVIOUS_EQUAL_LENGTH_PERIOD",30,
                        "QUEUE_OPENED_AT","RANDOM_QUEUE_TEAM_OR_CLAIMED_EXPERT","SAME_EXPERT_COMPLETED_BEFORE_VISIT_START",
                        "08:00","19:00",AdminQuery.ZONE.getId()),List.copyOf(axes),
                new Summary(experts.total(),experts.active(),count.eligible(),count.completed(),count.observed(),count.ratings(),
                        count.ratings()==0?null:BigDecimal.valueOf(count.ratingSum()).divide(BigDecimal.valueOf(count.ratings()),2,RoundingMode.HALF_UP),
                        null,"NOT_TRACKED",c.absent(),c.late(),c.unknown()),realtime.dashboardRevision(),2,
                new DataCoverage(earliest,window.trackingStartedAt(),previousComplete,
                        previousComplete?"COMPLETE":earliest==null?"NO_SCHEDULED_DATA":"PREVIOUS_PERIOD_OUTSIDE_DATA"));
    }
    private static vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.PresenceCounts emptyPresence(String period) {
        return new vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.PresenceCounts(period,null,Counts.empty(period),0,0,0,0);
    }
    private static Value presenceValue(String key,vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.PresenceCounts c) {
        if(!key.equals("ATTENDANCE") && !key.equals("PUNCTUALITY")) return value(key,c.counts());
        long numerator=key.equals("ATTENDANCE")?c.counts().attended():c.counts().onTime();
        return new Value(numerator,c.known(),c.known()==0?null:BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(c.known()),2,RoundingMode.HALF_UP),
                c.known()==0?c.unknown()>0?"NO_ATTENDANCE_EVIDENCE":"NO_ELIGIBLE_CONSULTATIONS":c.unknown()>0?"PARTIAL_DATA":"AVAILABLE",c.unknown());
    }
    public record ExpertReportRow(SelectedExpert expert,Summary summary,List<Axis> axes) { }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
    public vn.nutrimom.admin.dto.AdminOperationsDtos.Report report(LocalDate from,LocalDate to,String specialty,String expert,int page,int size,boolean export) {
        AdminQuery.page(page,export?100:size);
        var base=snapshot(from,to,specialty,expert,true,2);
        long total=base.summary().totalExperts();
        if(export && total>10000) throw AdminQuery.invalid("specialty","Báo cáo vượt 10000 dòng. Vui lòng thu hẹp bộ lọc.");
        var period=base.period();var params=operations.parameters(base.snapshotAt()).addValue("specialty",base.specialty())
                .addValue("expert",base.expert()==null?null:base.expert().userId())
                .addValue("fromDate",period.fromDate()).addValue("toDate",period.toDate()).addValue("previousFrom",period.previousFromDate())
                .addValue("previousStart",period.previousFromDate().atStartOfDay(AdminQuery.ZONE).toOffsetDateTime())
                .addValue("until",period.toDate().plusDays(1).atStartOfDay(AdminQuery.ZONE).toOffsetDateTime());
        var profiles=repository.reportExperts(specialty,expert,params,(page-1)*size,size);
        List<ExpertReportRow> items=new ArrayList<>();
        if(!profiles.isEmpty()) {
            params.addValue("filterExpertIds",true).addValue("expertIds",profiles.stream().map(SelectedExpert::userId).toList());
            var counts=repository.countsV2(specialty,expert,params,true);
            var extended=repository.extendedPerExpert(specialty,expert,params);
            var countMap=new java.util.HashMap<String,vn.nutrimom.admin.repository.AdminExpertPerformanceRepository.PresenceCounts>();
            counts.forEach(r->countMap.put(r.expertId()+r.period(),r));
            var extendedMap=new java.util.HashMap<String,ExtendedCounts>();
            extended.forEach(r->extendedMap.put(r.expertId()+r.counts().period(),r.counts()));
            for(var profile:profiles) {
                var c=countMap.getOrDefault(profile.userId()+"CURRENT",emptyPresence("CURRENT"));
                var p=countMap.getOrDefault(profile.userId()+"PREVIOUS",emptyPresence("PREVIOUS"));
                List<Axis> axes=new ArrayList<>();
                for(var template:base.axes()) {
                    boolean extra=template.key().equals("TIMELY_ACCEPTANCE") || template.key().equals("RETURNING_CUSTOMERS");
                    var cv=extra?extendedValue(template.key(),extendedMap.getOrDefault(profile.userId()+"CURRENT",ExtendedCounts.empty("CURRENT"))):presenceValue(template.key(),c);
                    var pv=extra?extendedValue(template.key(),extendedMap.getOrDefault(profile.userId()+"PREVIOUS",ExtendedCounts.empty("PREVIOUS"))):presenceValue(template.key(),p);
                    axes.add(new Axis(template.key(),template.label(),cv,pv,base.dataCoverage().previousPeriodComplete()
                            && cv.status().equals("AVAILABLE") && pv.status().equals("AVAILABLE")?cv.percent().subtract(pv.percent()):null));
                }
                var n=c.counts();
                items.add(new ExpertReportRow(profile,new Summary(1,profile.expertStatus().equals("ACTIVE") && profile.accountStatus().equals("ACTIVE")?1:0,
                        n.eligible(),n.completed(),n.observed(),n.ratings(),n.ratings()==0?null:BigDecimal.valueOf(n.ratingSum())
                        .divide(BigDecimal.valueOf(n.ratings()),2,RoundingMode.HALF_UP),null,"NOT_TRACKED",c.absent(),c.late(),c.unknown()),List.copyOf(axes)));
            }
        }
        return new vn.nutrimom.admin.dto.AdminOperationsDtos.Report("EXPERT_PERFORMANCE",base.snapshotAt(),base.timezone(),period,
                java.util.Map.of("snapshot",base),List.copyOf(items),page,size,total,(int)((total+size-1)/size));
    }

    private static Axis extendedAxis(String key,String label,ExtendedCounts current,ExtendedCounts previous) {
        var c=extendedValue(key,current);var p=extendedValue(key,previous);
        return new Axis(key,label,c,p,!c.status().equals("AVAILABLE") || !p.status().equals("AVAILABLE")
                ?null:c.percent().subtract(p.percent()));
    }
    private static Value extendedValue(String key,ExtendedCounts c) {
        boolean acceptance=key.equals("TIMELY_ACCEPTANCE");
        long denominator=acceptance?c.acceptanceEligible():c.customers();
        long numerator=acceptance?c.acceptedInTime():c.returningCustomers();
        long missing=acceptance?c.acceptanceMissing():0;
        String status=denominator==0?missing>0?"NO_ACCEPTANCE_EVIDENCE":acceptance?"NO_ELIGIBLE_REQUESTS":"NO_COMPLETED_CUSTOMERS"
                :missing>0?"PARTIAL_DATA":"AVAILABLE";
        return new Value(numerator,denominator,denominator==0?null:BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP),status,missing);
    }
    private static Axis axis(String key,String label,Counts current,Counts previous) {
        var c=value(key,current);var p=value(key,previous);
        return new Axis(key,label,c,p,!c.status().equals("AVAILABLE") || !p.status().equals("AVAILABLE")
                ?null:c.percent().subtract(p.percent()));
    }
    private static Value value(String key,Counts c) {
        long numerator,denominator,missing=0;String status;
        switch(key) {
            case "ATTENDANCE","PUNCTUALITY" -> {
                numerator=key.equals("ATTENDANCE")?c.attended():c.onTime();denominator=c.eligible();
                status=denominator==0?"NO_ELIGIBLE_CONSULTATIONS":"AVAILABLE";
            }
            case "SATISFACTION" -> {
                numerator=c.satisfied();denominator=c.ratings();missing=c.completed()-c.ratings();
                status=denominator==0?"NO_REVIEWS":"AVAILABLE";
            }
            default -> {numerator=c.completed();denominator=c.eligible();status=denominator==0?"NO_ELIGIBLE_CONSULTATIONS":"AVAILABLE";}
        }
        return new Value(numerator,denominator,denominator==0?null:BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP),status,missing);
    }
}

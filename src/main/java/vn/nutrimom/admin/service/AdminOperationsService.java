package vn.nutrimom.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.nutrimom.admin.dto.AdminOperationsDtos.*;
import vn.nutrimom.admin.dto.AdminOperationsDtos.Period;
import vn.nutrimom.admin.model.AdminQuery;
import vn.nutrimom.admin.repository.AdminOperationsRepository;
import vn.nutrimom.admin.repository.AdminOperationsRepository.Filter;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.consultation.dto.ExpertDtos.AdminExpertResponse;
import static vn.nutrimom.admin.repository.AdminOperationsRepository.*;

@Service
@Transactional(readOnly = true)
public class AdminOperationsService {
    private static final Logger log = LoggerFactory.getLogger(AdminOperationsService.class);
    private final java.util.concurrent.atomic.AtomicLong nextPaymentQualityCheck = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong nextSubscriptionWarning = new java.util.concurrent.atomic.AtomicLong();
    public static final Set<String> SPECIALTIES=Set.of("PSYCHOLOGY","OBSTETRICS","HEALTH");
    public static final Set<String> SOURCES=Set.of("REAL","DEMO","LEGACY_UNKNOWN","ALL");
    public static final Set<String> PLANS=Set.of("FREE","PLAN_99K","PLAN_399K");
    private final AdminOperationsRepository db;
    private final AdminDashboardMetricsService metrics;
    private final Clock clock;
    private final vn.nutrimom.admin.realtime.AdminRealtimeRepository realtime;
    public AdminOperationsService(AdminOperationsRepository db,Clock clock,AdminDashboardMetricsService metrics,
            vn.nutrimom.admin.realtime.AdminRealtimeRepository realtime) {
        this.db=db;this.clock=clock;this.metrics=metrics;this.realtime=realtime;
    }
    public OffsetDateTime now() { return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    public Period period(LocalDate from,LocalDate to) { return AdminQuery.period(from,to,clock,true,false); }
    public Page<Appointment> appointments(int page,int size,String q,String status,String specialty,
            String expert,LocalDate from,LocalDate to,String basis,boolean attention) {
        AdminQuery.page(page,size);
        var now=now();
        var f=db.appointments(q,AdminQuery.statuses(status),AdminQuery.option("specialty",specialty,SPECIALTIES,null),
                AdminQuery.id("expertUserId",expert),AdminQuery.period(from,to,clock,false,true),
                AdminQuery.option("dateBasis",basis,Set.of("SCHEDULED","CREATED"),"SCHEDULED"),attention,now);
        return db.appointmentPage(f,page,size,now,false);
    }
    public Appointment appointment(String id) {
        var result=db.appointment(AdminQuery.id("requestId",id),now());
        if(result==null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return result;
    }
    public Page<Appointment> history(int page,int size,String q,String specialty,String expert,
                                     LocalDate from,LocalDate to,Boolean reviewed) {
        // Retain historical endpoint page semantics; new endpoints have an additional page cap.
        if(page<1 || size<1 || size>100) throw AdminQuery.invalid("page","Phân trang không hợp lệ.");
        var now=now();
        var f=db.history(q,AdminQuery.option("specialty",specialty,SPECIALTIES,null),AdminQuery.id("expertUserId",expert),
                AdminQuery.period(from,to,clock,false,false),reviewed,now);
        return db.appointmentPage(f,page,size,now,true);
    }
    public Page<AdminExpertResponse> experts(int page,int size,String q,String specialty,String status) {
        AdminQuery.page(page,size);
        return db.experts(q,AdminQuery.option("specialty",specialty,SPECIALTIES,null),
                AdminQuery.option("status",status,Set.of("ACTIVE","INACTIVE"),null),page,size);
    }
    public Page<PaymentOrder> payments(int page,int size,String q,String plan,String status,String source,
                                       LocalDate from,LocalDate to,String basis) {
        return payments(page,size,q,plan,status,source,from,to,basis,null);
    }
    public Page<PaymentOrder> payments(int page,int size,String q,String plan,String status,String source,
                                       LocalDate from,LocalDate to,String basis,String range) {
        AdminQuery.page(page,size);
        String scope=AdminQuery.option("range",range,Set.of("PERIOD","ALL_TIME"),"PERIOD");
        validateAllTime(scope,from,to);
        String dateBasis=AdminQuery.option("dateBasis",basis,Set.of("CREATED","PAID"),"CREATED");
        var filter=db.payments(q,AdminQuery.option("planTier",plan,PLANS,null),
                AdminQuery.option("status",status,Set.of("PENDING","PAID","CANCELLED","EXPIRED"),null),
                AdminQuery.option("source",source,SOURCES,"ALL"),AdminQuery.period(from,to,clock,false,false),
                dateBasis,now());
        if("ALL_TIME".equals(scope) && "PAID".equals(dateBasis))
            filter=new Filter(filter.where()+" and p.paid_at<=:now ",filter.params());
        return db.paymentPage(filter,page,size);
    }
    public PaymentOrder payment(String code) {
        var row=db.payment(AdminQuery.orderCode(code));
        if(row==null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return row;
    }
    public Page<Subscription> subscriptions(int page,int size,String q,String plan,String effective,Integer expires) {
        AdminQuery.page(page,size);
        if(expires!=null && (expires<1 || expires>30)) throw AdminQuery.invalid("expiringWithinDays","Giới hạn từ 1 đến 30 ngày.");
        return db.subscriptions(q,AdminQuery.option("planTier",plan,PLANS,null),
                AdminQuery.option("effectiveStatus",effective,Set.of("ACTIVE","EXPIRED","CANCELLED","NOT_STARTED"),null),
                expires,page,size,now());
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Revenue revenue(LocalDate from,LocalDate to,String source) {
        return revenue(period(from,to),AdminQuery.option("source",source,SOURCES,"ALL"),now());
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Revenue revenue(LocalDate from,LocalDate to,String source,String range) {
        String scope=AdminQuery.option("range",range,Set.of("PERIOD","ALL_TIME"),"PERIOD");
        validateAllTime(scope,from,to);
        if(!"ALL_TIME".equals(scope)) return revenue(from,to,source);
        var at=now();checkPaymentQuality(at);
        String origin=AdminQuery.option("source",source,SOURCES,"ALL");
        var base=db.payments(null,null,"PAID",origin,null,"PAID",at);
        var filter=new Filter(base.where()+" and p.paid_at<=:now ",base.params());
        var totals=db.paymentTotals(filter);
        var unknownBase=db.payments(null,null,"PAID","LEGACY_UNKNOWN",null,"PAID",at);
        var unknown=db.paymentTotals(new Filter(unknownBase.where()+" and p.paid_at<=:now ",unknownBase.params()));
        long amount=number(totals,"amount"),count=number(totals,"count");
        return new Revenue(at,AdminQuery.ZONE.getId(),null,origin,
                new RevenueTotals(amount,count,average(amount,count),0,number(unknown,"count")),
                List.of(),db.planCollections(filter),"ALL_TIME");
    }
    private static void validateAllTime(String scope,LocalDate from,LocalDate to) {
        if("ALL_TIME".equals(scope) && (from!=null || to!=null))
            throw AdminQuery.invalid("range","Phạm vi Toàn bộ không nhận bộ lọc ngày.");
    }
    private Revenue revenue(Period period,String source,OffsetDateTime now) {
        checkPaymentQuality(now);
        var f=db.payments(null,null,"PAID",source,period,"PAID",now);
        var totals=db.paymentTotals(f);
        var previous=new Period(period.previousFromDate(),period.previousToDate(),null,null);
        var prior=db.paymentTotals(db.payments(null,null,"PAID",source,previous,"PAID",now));
        long amount=number(totals,"amount"),count=number(totals,"count");
        var unknown=db.paymentTotals(db.payments(null,null,"PAID","LEGACY_UNKNOWN",period,"PAID",now));
        var daily=new HashMap<LocalDate,DailyCollection>();
        db.collections(f).forEach(row->daily.put(row.date(),row));
        List<DailyCollection> filled=period.fromDate().datesUntil(period.toDate().plusDays(1))
                .map(date->daily.getOrDefault(date,new DailyCollection(date,0,0))).toList();
        return new Revenue(now,AdminQuery.ZONE.getId(),period,source,
                new RevenueTotals(amount,count,average(amount,count),number(prior,"amount"),number(unknown,"count")),
                filled,db.planCollections(f));
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Dashboard dashboard(LocalDate from,LocalDate to) {
        var now=now();var period=period(from,to);var p=db.parameters(now);
        LocalDate today=now.atZoneSameInstant(AdminQuery.ZONE).toLocalDate();p.addValue("today",today)
                .addValue("densityFrom",today.minusDays(30)).addValue("densityUntil",today.minusDays(1));
        var support=db.one("""
                select count(*) pending,
                  coalesce(sum(case when created_at < :overdueAt then 1 else 0 end),0) overdue
                from app.contact_requests where status='PENDING'
                """,p);
        var consultation=db.one("""
                select coalesce(sum(case when c.status='PENDING_EXPERT' then 1 else 0 end),0) pending,
                  coalesce(sum(case when c.status='PENDING_EXPERT' and ((c.acceptance_deadline_at is not null and c.acceptance_deadline_at<:now) or (c.acceptance_deadline_at is null and c.created_at<:overdueAt)) then 1 else 0 end),0) overdue,
                  coalesce(sum(case when
                """+PAST_END+"""
                  then 1 else 0 end),0) past,
                  coalesce(sum(case when coalesce(s.slot_date,c.scheduled_date)=:today then 1 else 0 end),0) today_total,
                  coalesce(sum(case when coalesce(s.slot_date,c.scheduled_date)=:today and c.status='COMPLETED' then 1 else 0 end),0) today_completed,
                  coalesce(sum(case when coalesce(s.slot_date,c.scheduled_date)=:today and c.status='PENDING_CONSULTATION' then 1 else 0 end),0) today_pending,
                  coalesce(sum(case when coalesce(s.slot_date,c.scheduled_date)=:today and c.status in ('PENDING_CONSULTATION','COMPLETED') then 1 else 0 end),0) today_booked,
                  count(distinct case when coalesce(s.slot_date,c.scheduled_date)=:today and c.status in ('PENDING_CONSULTATION','COMPLETED') then c.user_id else null end) today_users,
                  coalesce(sum(case when coalesce(s.slot_date,c.scheduled_date) between :densityFrom and :densityUntil
                    and c.status in ('PENDING_CONSULTATION','COMPLETED') then 1 else 0 end),0) prior_booked
                from app.consultation_requests c left join app.consultation_slots s on s.id=c.slot_id
                """,p);
        var revenue=revenue(period,"ALL",now);
        var previousPeriod=new Period(period.previousFromDate(),period.previousToDate(),null,null);
        var priorMoney=new HashMap<LocalDate,DailyCollection>();
        db.collections(db.payments(null,null,"PAID","ALL",previousPeriod,"PAID",now))
                .forEach(row->priorMoney.put(row.date(),row));
        var registrations=db.registrations(period);var previousRegistrations=db.registrations(previousPeriod);
        long days=ChronoUnit.DAYS.between(period.fromDate(),period.toDate())+1;
        List<ComparedCollection> moneySeries=new ArrayList<>();List<Registration> registrationSeries=new ArrayList<>();
        for(var row:revenue.dailySeries()) {
            LocalDate previousDate=row.date().minusDays(days);
            moneySeries.add(new ComparedCollection(row.date(),row.amount(),row.paidOrders(),previousDate,
                    priorMoney.getOrDefault(previousDate,new DailyCollection(previousDate,0,0)).amount()));
            registrationSeries.add(new Registration(row.date(),registrations.getOrDefault(row.date(),0L),
                    previousDate,previousRegistrations.getOrDefault(previousDate,0L)));
        }
        var subscriptions=db.one("""
                select coalesce(sum(case when t.plan_tier='PLAN_99K' then 1 else 0 end),0) basic,
                       coalesce(sum(case when t.plan_tier='PLAN_399K' then 1 else 0 end),0) comprehensive,
                       coalesce(sum(case when t.current_matches>1 then 1 else 0 end),0) duplicate_users
                from
                """+CURRENT_SUB+" t",p);
        if (number(subscriptions,"duplicate_users") > 0 && takeDiagnosticWindow(nextSubscriptionWarning)) {
            log.warn("Admin snapshot found {} users with overlapping active subscriptions; current plan is selected deterministically",
                    number(subscriptions,"duplicate_users"));
        }
        var kpis=new Kpis(number(support,"pending"),number(support,"overdue"),
                number(consultation,"pending"),number(consultation,"overdue"),number(consultation,"today_total"),
                number(consultation,"today_completed"),number(consultation,"today_pending"),
                revenue.summary().collectedAmount(),revenue.summary().paidOrders(),revenue.summary().previousCollectedAmount());
        var additional=metrics.read(now);
        return new Dashboard(now,AdminQuery.ZONE.getId(),period,kpis,
                new Attention(new AttentionTotals(number(consultation,"past"),number(consultation,"pending"),number(support,"pending")),
                    attention(now)),
                new TodaySchedule(number(consultation,"today_total"),db.todaySchedule(now),db.todayBookingHours(now)),
                new Series(moneySeries,registrationSeries),
                new SubscriptionSnapshot(now,number(subscriptions,"basic"),number(subscriptions,"comprehensive")),activity(now),
                new Overview(additional.lifetimeAmount(),number(consultation,"today_booked"),number(consultation,"today_users")),
                additional.customers(),additional.monthly(),additional.adoption(),
                new MetricComparisons(java.time.YearMonth.from(today).minusMonths(1).toString(),30,
                    new MetricComparison(BigDecimal.valueOf(additional.lifetimeAmount()),BigDecimal.valueOf(additional.previousLifetimeAmount())),
                    new MetricComparison(BigDecimal.valueOf(additional.currentMonthNew()),BigDecimal.valueOf(additional.previousMonthNew())),
                    new MetricComparison(BigDecimal.valueOf(number(consultation,"today_booked")),
                        BigDecimal.valueOf(number(consultation,"prior_booked")).divide(BigDecimal.valueOf(30),12,java.math.RoundingMode.HALF_UP)),
                    new MetricComparison(additional.adoption().currentRatioPercent(),additional.adoption().previousRatioPercent())),
                realtime.dashboardRevision());
    }
    private List<AttentionItem> attention(OffsetDateTime now) {
        String sql="""
                select * from (
                  select c.id,'CONSULTATION_PAST_END' kind,u.display_name,c.created_at occurred_at,0 priority
                  from app.consultation_requests c join app.users u on u.id=c.user_id
                  join app.consultation_slots s on s.id=c.slot_id where
                """+PAST_END+"""
                  union all select c.id,case when c.acceptance_deadline_at<:now then 'ACCEPTANCE_OVERDUE' else 'EXPERT_WAITING' end,
                       u.display_name,coalesce(c.acceptance_deadline_at,c.created_at),
                       case when c.acceptance_deadline_at<:now or (c.acceptance_deadline_at is null and c.created_at<:overdueAt) then 1 else 3 end
                  from app.consultation_requests c join app.users u on u.id=c.user_id where c.status='PENDING_EXPERT'
                  union all select c.id,'SUPPORT_PENDING',u.display_name,c.created_at,
                       case when c.created_at<:overdueAt then 2 else 4 end
                  from app.contact_requests c join app.users u on u.id=c.user_id where c.status='PENDING'
                ) attention order by priority,occurred_at,id limit 6
                """;
        return db.rows(sql,db.parameters(now),(r,n)->{
            String kind=r.getString("kind");var occurred=time(r,"occurred_at");
            String title=switch(kind) {
                case "CONSULTATION_PAST_END"->"Buổi tư vấn quá giờ, chưa hoàn tất";
                case "ACCEPTANCE_OVERDUE"->"Yêu cầu tư vấn quá hạn tiếp nhận";
                case "EXPERT_WAITING"->"Yêu cầu đang chờ chuyên gia tiếp nhận";
                default->"Yêu cầu hỗ trợ đang chờ xử lý";
            };
            return new AttentionItem(kind+":"+r.getString("id"),kind,r.getString("id"),r.getString("display_name"),
                    title,occurred,hours(occurred,now),kind.equals("CONSULTATION_PAST_END")||kind.equals("ACCEPTANCE_OVERDUE")||occurred.isBefore(now.minusHours(24)));
        });
    }
    private List<Activity> activity(OffsetDateTime now) {
        return db.rows("""
                select * from (
                  (select u.id,'USER_REGISTERED' kind,u.id source_id,u.created_at occurred_at,
                         cast(null as varchar) order_code from app.users u where
                """+USERS+"""
                  order by u.created_at desc,u.id asc limit 8)
                  union all (select p.id,'PAYMENT_PAID',p.id,p.paid_at,cast(p.order_code as varchar)
                       from app.payment_orders p where p.status='PAID' and p.paid_at is not null and p.paid_at<=:now
                  order by p.paid_at desc,p.id asc limit 8)
                  union all (select c.id,'CONSULTATION_COMPLETED',c.id,c.completed_at,null
                       from app.consultation_requests c where c.status='COMPLETED' and c.completed_at is not null
                  order by c.completed_at desc,c.id asc limit 8)
                  union all (select c.id,'SUPPORT_COMPLETED',c.id,c.completed_at,null
                       from app.contact_requests c where c.status='COMPLETED' and c.completed_at is not null
                  order by c.completed_at desc,c.id asc limit 8)
                ) activity order by occurred_at desc,id asc,kind asc limit 8
                """,db.parameters(now),(r,n)->{
            String kind=r.getString("kind");
            String title=switch(kind) {
                case "USER_REGISTERED"->"Có tài khoản người dùng mới";
                case "PAYMENT_PAID"->"Ghi nhận doanh thu gói dịch vụ";
                case "CONSULTATION_COMPLETED"->"Buổi tư vấn đã hoàn tất";
                default->"Yêu cầu hỗ trợ đã được xử lý";
            };
            return new Activity(kind+":"+r.getString("id"),kind,r.getString("source_id"),title,time(r,"occurred_at"),r.getString("order_code"));
        });
    }
    public static long number(Map<String,Object> row,String key) {
        var value=row.get(key);long result=value==null ? 0 : new BigDecimal(value.toString()).longValueExact();
        if(result<0 || result>9_007_199_254_740_991L) throw new IllegalStateException("Unsafe aggregate value: "+key);
        return result;
    }
    private void checkPaymentQuality(OffsetDateTime now) {
        if (!takeDiagnosticWindow(nextPaymentQualityCheck)) return;
        var quality = db.one("select count(*) missing from app.payment_orders where status='PAID' and paid_at is null",
                db.parameters(now));
        if (number(quality,"missing") > 0) {
            log.warn("Admin collections excluded {} paid orders without payment timestamps", number(quality,"missing"));
        }
    }
    private static boolean takeDiagnosticWindow(java.util.concurrent.atomic.AtomicLong next) {
        long now = System.currentTimeMillis();
        long previous = next.get();
        return previous <= now && next.compareAndSet(previous, now + 300_000);
    }
    public static BigDecimal average(long amount,long count) {
        return count==0 ? BigDecimal.ZERO : BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(count),2,RoundingMode.HALF_UP);
    }
}

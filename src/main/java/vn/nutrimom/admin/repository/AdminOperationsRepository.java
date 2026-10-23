package vn.nutrimom.admin.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.nutrimom.admin.dto.AdminOperationsDtos.*;
import vn.nutrimom.admin.dto.AdminOperationsDtos.Period;
import vn.nutrimom.admin.model.AdminQuery;
import vn.nutrimom.consultation.dto.ExpertDtos.AdminExpertResponse;
import vn.nutrimom.consultation.dto.ReviewDtos.ReviewResponse;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.payment.domain.PlanTier;

/** Bounded SQL projections. Dynamic fragments are server-owned; values are always bound. */
@Repository
public class AdminOperationsRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public AdminOperationsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static final String USERS = """
            exists (select 1 from app.user_roles ur where ur.user_id=u.id and ur.role='USER')
            and not exists (select 1 from app.user_roles ur where ur.user_id=u.id and ur.role in ('ADMIN','EXPERT'))
            """;
    public static final String PAST_END = """
            (c.status='PENDING_CONSULTATION' and s.id is not null and
             (s.slot_date < :pastDate or (s.slot_date=:pastDate and
              (s.end_time + cast(:localTimeOffset as integer) * interval '1' second) < :pastTime)))
            """;
    public static final String APPOINTMENT_FROM = """
            from app.consultation_requests c
            join app.users u on u.id=c.user_id
            left join app.expert_profiles e on e.user_id=c.expert_user_id
            left join app.consultation_slots s on s.id=c.slot_id
            left join app.consultation_reviews r on r.request_id=c.id
            """;
    private static final String APPOINTMENT_SELECT = """
            select c.id,c.user_id,u.display_name user_display_name,c.expert_user_id,
              e.full_name expert_name,c.specialty,c.assignment_type,c.status,c.created_at,
              c.completed_at,c.updated_at,coalesce(s.id,c.scheduled_slot_id) slot_id,
              coalesce(s.slot_date,c.scheduled_date) slot_date,
              (coalesce(s.start_time,c.scheduled_start_time) + cast(:localTimeOffset as integer) * interval '1' second) start_time,
              (coalesce(s.end_time,c.scheduled_end_time) + cast(:localTimeOffset as integer) * interval '1' second) end_time,
              c.preferred_date,c.accepted_at,c.acceptance_policy_version,c.acceptance_deadline_at,r.id review_id,r.rating,r.comment,r.created_at review_created_at
            """;
    public static final String PAYMENT_FROM = " from app.payment_orders p join app.users u on u.id=p.user_id ";
    private static final String PAYMENT_SELECT = """
            select p.id,p.order_code,p.user_id,u.display_name user_display_name,
              p.plan_tier,p.amount,p.currency,p.source,p.status,p.created_at,p.paid_at,
              p.description,p.updated_at
            """;
    public static final String EFFECTIVE = """
            case when t.status='CANCELLED' then 'CANCELLED'
                 when t.status='EXPIRED' or t.end_date <= :now then 'EXPIRED'
                 when t.start_date > :now then 'NOT_STARTED'
                 else 'ACTIVE' end
            """;
    // One current subscription per user, including deterministic handling of historical duplicates.
    public static final String CURRENT_SUB = """
            (select x.* from (
               select t.*, row_number() over (partition by t.user_id
                  order by t.end_date desc,t.updated_at desc,t.id asc) rn,
                  count(*) over (partition by t.user_id) current_matches
               from app.user_subscriptions t
               where t.status='ACTIVE' and t.start_date<=:now and t.end_date>:now
             ) x where x.rn=1)
            """;
    public record Filter(String where, MapSqlParameterSource params) {}
    public MapSqlParameterSource parameters(OffsetDateTime now) {
        var cutoff = now.atZoneSameInstant(AdminQuery.ZONE).toLocalDateTime().minusMinutes(5);
        // Hibernate's legacy java.sql.Time binding uses the JVM zone at the 1970 reference date.
        // Mirror that conversion in native projections so Admin hours match the LocalTime domain.
        int localTimeOffset=ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        return new MapSqlParameterSource().addValue("now", now).addValue("pastDate", cutoff.toLocalDate())
                .addValue("pastTime", cutoff.toLocalTime()).addValue("overdueAt", now.minusHours(24))
                .addValue("localTimeOffset",localTimeOffset);
    }
    private void dates(StringBuilder where, MapSqlParameterSource p, Period period, String column) {
        if (period != null) {
            p.addValue("from", period.fromDate().atStartOfDay(AdminQuery.ZONE).toOffsetDateTime());
            p.addValue("until", period.toDate().plusDays(1).atStartOfDay(AdminQuery.ZONE).toOffsetDateTime());
            where.append(" and ").append(column).append(" >= :from and ").append(column).append(" < :until ");
        }
    }
    private void text(StringBuilder where, MapSqlParameterSource p, String q, String condition) {
        if (!AdminQuery.search(q).isEmpty()) {
            p.addValue("q", AdminQuery.like(q));
            where.append(" and (").append(condition).append(") ");
        }
    }
    private void exact(StringBuilder where, MapSqlParameterSource p, String column, String name, String value) {
        if (value != null) { where.append(" and ").append(column).append("=:").append(name); p.addValue(name, value); }
    }
    public Filter appointments(String q, Set<String> statuses, String specialty, String expert,
            Period period, String basis, boolean attention, OffsetDateTime now) {
        var p = parameters(now); var w = new StringBuilder(" where 1=1 ");
        text(w,p,q,"lower(u.display_name) like :q escape '!' or lower(e.full_name) like :q escape '!'");
        if (!statuses.isEmpty()) { w.append(" and c.status in (:statuses) "); p.addValue("statuses",statuses); }
        exact(w,p,"c.specialty","specialty",specialty); exact(w,p,"c.expert_user_id","expert",expert);
        if ("SCHEDULED".equals(basis) && period != null) {
            w.append(" and coalesce(s.slot_date,c.scheduled_date) between :fromDate and :toDate ");
            p.addValue("fromDate",period.fromDate()).addValue("toDate",period.toDate());
        } else dates(w,p,period,"c.created_at");
        if (attention) w.append(" and (c.status='PENDING_EXPERT' or ").append(PAST_END).append(") ");
        return new Filter(w.toString(),p);
    }
    public Filter history(String q, String specialty, String expert, Period period, Boolean reviewed, OffsetDateTime now) {
        Filter base = appointments(q,Set.of("COMPLETED"),specialty,expert,null,"CREATED",false,now);
        var w = new StringBuilder(base.where());
        dates(w,base.params(),period,"c.completed_at");
        if (reviewed != null) w.append(reviewed ? " and r.id is not null " : " and r.id is null ");
        return new Filter(w.toString(),base.params());
    }
    public Page<Appointment> appointmentPage(Filter filter, int page, int size, OffsetDateTime now, boolean history) {
        String order = history ? "c.created_at desc,c.id asc"
                : "case when coalesce(s.id,c.scheduled_slot_id) is null then 1 else 0 end,"
                  + "coalesce(s.slot_date,c.scheduled_date) asc,(coalesce(s.start_time,c.scheduled_start_time) + cast(:localTimeOffset as integer) * interval '1' second) asc,c.created_at asc,c.id asc";
        return page(APPOINTMENT_SELECT,APPOINTMENT_FROM,filter,order,page,size,appointmentMapper(now));
    }
    public Appointment appointment(String id, OffsetDateTime now) {
        var rows = jdbc.query(APPOINTMENT_SELECT+APPOINTMENT_FROM+" where c.id=:id",
                parameters(now).addValue("id",id),appointmentMapper(now));
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<Appointment> todaySchedule(OffsetDateTime now) {
        return jdbc.query(APPOINTMENT_SELECT+APPOINTMENT_FROM+"""
                where coalesce(s.slot_date,c.scheduled_date)=:today
                order by case c.status when 'PENDING_CONSULTATION' then 0 when 'COMPLETED' then 1 else 2 end,
                         (coalesce(s.start_time,c.scheduled_start_time) + cast(:localTimeOffset as integer) * interval '1' second) asc,c.id asc limit 6
                """,parameters(now).addValue("today",now.atZoneSameInstant(AdminQuery.ZONE).toLocalDate()),appointmentMapper(now));
    }
    public List<HourlyBookings> todayBookingHours(OffsetDateTime now) {
        var rows=jdbc.query("""
                select extract(hour from (coalesce(s.start_time,c.scheduled_start_time) + cast(:localTimeOffset as integer) * interval '1' second)) hour_bucket,count(*) booked
                from app.consultation_requests c left join app.consultation_slots s on s.id=c.slot_id
                where coalesce(s.slot_date,c.scheduled_date)=:today and c.status in ('PENDING_CONSULTATION','COMPLETED')
                group by hour_bucket order by hour_bucket
                """,parameters(now).addValue("today",now.atZoneSameInstant(AdminQuery.ZONE).toLocalDate()),
                (r,n)->Map.entry(r.getInt("hour_bucket"),r.getLong("booked")));
        var counts=new HashMap<Integer,Long>();rows.forEach(row->counts.put(row.getKey(),row.getValue()));
        return java.util.stream.IntStream.range(0,24)
                .mapToObj(hour->new HourlyBookings(String.format(Locale.ROOT,"%02d:00",hour),counts.getOrDefault(hour,0L))).toList();
    }
    public Filter payments(String q, String plan, String status, String source, Period period,
            String basis, OffsetDateTime now) {
        var p = parameters(now); var w = new StringBuilder(" where 1=1 ");
        text(w,p,q,"lower(u.display_name) like :q escape '!' or cast(p.order_code as varchar) like :q escape '!'");
        exact(w,p,"p.plan_tier","plan",plan); exact(w,p,"p.status","status",status);
        if (!"ALL".equals(source)) exact(w,p,"p.source","source",source);
        if ("PAID".equals(basis)) w.append(" and p.status='PAID' and p.paid_at is not null and p.paid_at<=:now ");
        dates(w,p,period,"PAID".equals(basis) ? "p.paid_at" : "p.created_at");
        return new Filter(w.toString(),p);
    }
    public Page<PaymentOrder> paymentPage(Filter f, int page, int size) {
        return page(PAYMENT_SELECT,PAYMENT_FROM,f,"p.created_at desc,p.id asc",page,size,paymentMapper());
    }
    public PaymentOrder payment(long code) {
        var rows=jdbc.query(PAYMENT_SELECT+PAYMENT_FROM+" where p.order_code=:code",
                new MapSqlParameterSource("code",code),paymentMapper());
        return rows.isEmpty() ? null : rows.get(0);
    }
    public Map<String,Object> paymentTotals(Filter f) {
        return one("select coalesce(sum(p.amount),0) amount,count(*) count "+PAYMENT_FROM+f.where(),f.params());
    }
    public List<DailyCollection> collections(Filter f) {
        return jdbc.query("select "+AdminQuery.dayKey("p.paid_at")+" day_bucket,"
                +"coalesce(sum(p.amount),0) amount,count(*) count"
                +PAYMENT_FROM+f.where()+" group by day_bucket order by day_bucket",f.params(),
                (r,n)->new DailyCollection(AdminQuery.dayOf(r.getInt("day_bucket")),money(r,"amount"),r.getLong("count")));
    }
    public List<PlanCollection> planCollections(Filter f) {
        return jdbc.query("select p.plan_tier,coalesce(sum(p.amount),0) amount,count(*) count "
                +PAYMENT_FROM+f.where()+" group by p.plan_tier order by p.plan_tier",f.params(),
                (r,n)->new PlanCollection(r.getString("plan_tier"),money(r,"amount"),r.getLong("count")));
    }
    public Page<Subscription> subscriptions(String q,String plan,String effective,Integer expiring,
                                             int page,int size,OffsetDateTime now) {
        var p=parameters(now);var w=new StringBuilder(" where 1=1 ");
        text(w,p,q,"lower(u.display_name) like :q escape '!'");
        exact(w,p,"t.plan_tier","plan",plan);
        if (effective != null) { w.append(" and (").append(EFFECTIVE).append(")=:effective ");p.addValue("effective",effective); }
        if (expiring != null) {
            w.append(" and t.status='ACTIVE' and t.start_date<=:now and t.end_date>:now and t.end_date<=:expires ");
            p.addValue("expires",now.plusDays(expiring));
        }
        return page("select t.*,u.display_name user_display_name,"+EFFECTIVE+" effective_status",
                " from app.user_subscriptions t join app.users u on u.id=t.user_id ",
                new Filter(w.toString(),p),"t.end_date desc,t.updated_at desc,t.id asc",page,size,
                (r,n)->new Subscription(r.getString("id"),r.getString("user_id"),r.getString("user_display_name"),
                    r.getString("plan_tier"),r.getString("status"),r.getString("effective_status"),
                    time(r,"start_date"),time(r,"end_date"),Math.max(0,Duration.between(now,time(r,"end_date")).toDays())));
    }
    public Page<AdminExpertResponse> experts(String q,String specialty,String status,int page,int size) {
        var p=new MapSqlParameterSource();var w=new StringBuilder(" where 1=1 ");
        text(w,p,q,"lower(e.full_name) like :q escape '!'");
        exact(w,p,"e.specialty","specialty",specialty);exact(w,p,"e.status","status",status);
        return page("select e.*,u.phone"," from app.expert_profiles e join app.users u on u.id=e.user_id ",
                new Filter(w.toString(),p),"e.created_at desc,e.user_id asc",page,size,
                (r,n)->new AdminExpertResponse(r.getString("user_id"),r.getString("phone"),r.getString("full_name"),
                    Specialty.valueOf(r.getString("specialty")),r.getString("title"),r.getString("workplace"),
                    r.getInt("years_of_experience"),r.getString("bio"),r.getString("avatar_key"),r.getString("avatar_url"),
                    ExpertStatus.valueOf(r.getString("status")),r.getBigDecimal("average_rating"),r.getInt("rating_count"),
                    r.getLong("version"),time(r,"created_at"),time(r,"updated_at")));
    }
    public Map<LocalDate,Long> registrations(Period period) {
        var p=new MapSqlParameterSource();var w=new StringBuilder(" where "+USERS);
        dates(w,p,period,"u.created_at");
        var rows=jdbc.query("select "+AdminQuery.dayKey("u.created_at")+" day_bucket,count(*) count "
                +"from app.users u"+w+" group by day_bucket",p,(r,n)->Map.entry(AdminQuery.dayOf(r.getInt("day_bucket")),r.getLong("count")));
        var result=new HashMap<LocalDate,Long>();rows.forEach(e->result.put(e.getKey(),e.getValue()));return result;
    }
    public Filter registeredFilter(Period period,OffsetDateTime now) {
        var p=parameters(now);var w=new StringBuilder(" where "+USERS);dates(w,p,period,"u.created_at");
        return new Filter(w.toString(),p);
    }
    public static final String REGISTERED_FROM=" from app.users u left join "+CURRENT_SUB+" t on t.user_id=u.id ";
    private static final String REGISTERED_SELECT="""
            select u.id user_id,u.display_name,u.created_at,u.status account_status,
                   coalesce(t.plan_tier,'FREE') current_plan_tier,t.end_date subscription_end_date
            """;
    public Page<RegisteredUser> registeredPage(Filter f,int page,int size) {
        return page(REGISTERED_SELECT,REGISTERED_FROM,f,"u.created_at asc,u.id asc",page,size,registeredMapper());
    }
    public Map<String,Object> registeredTotals(Filter f) {
        return one("""
                select count(*) new_users,
                 coalesce(sum(case when t.plan_tier is null then 1 else 0 end),0) current_free_users,
                 coalesce(sum(case when t.plan_tier='PLAN_99K' then 1 else 0 end),0) current_plan_99k_users,
                 coalesce(sum(case when t.plan_tier='PLAN_399K' then 1 else 0 end),0) current_plan_399k_users
                """+REGISTERED_FROM+f.where(),f.params());
    }
    public long count(String from,Filter f) {
        return jdbc.queryForObject("select count(*) "+from+f.where(),f.params(),Long.class);
    }
    public Map<String,Object> one(String sql,MapSqlParameterSource p) {
        return jdbc.queryForObject(sql,p,(r,n)-> {
            Map<String,Object> result=new LinkedHashMap<>();
            for(int i=1;i<=r.getMetaData().getColumnCount();i++) {
                Object value=r.getObject(i);
                if(value instanceof BigDecimal number && number.scale()<=0) value=number.longValueExact();
                result.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),value);
            }
            return result;
        });
    }
    public <T> List<T> rows(String sql,MapSqlParameterSource p,RowMapper<T> mapper) { return jdbc.query(sql,p,mapper); }
    public List<Appointment> reportAppointments(Filter f,int offset,int size,OffsetDateTime now) {
        return jdbc.query(APPOINTMENT_SELECT+APPOINTMENT_FROM+f.where()
                +" order by c.created_at asc,c.id asc limit :limit offset :offset",
                new MapSqlParameterSource(f.params().getValues()).addValue("limit",size).addValue("offset",offset),appointmentMapper(now));
    }
    public List<RegisteredUser> reportUsers(Filter f,int offset,int size) {
        return jdbc.query(REGISTERED_SELECT+REGISTERED_FROM+f.where()
                +" order by u.created_at asc,u.id asc limit :limit offset :offset",
                new MapSqlParameterSource(f.params().getValues()).addValue("limit",size).addValue("offset",offset),registeredMapper());
    }
    public List<PaymentOrder> reportPayments(Filter f,int offset,int size) {
        return jdbc.query(PAYMENT_SELECT+PAYMENT_FROM+f.where()+" order by p.paid_at asc,p.id asc limit :limit offset :offset",
                new MapSqlParameterSource(f.params().getValues()).addValue("limit",size).addValue("offset",offset),paymentMapper());
    }
    private <T> Page<T> page(String select,String from,Filter f,String order,int page,int size,RowMapper<T> mapper) {
        long count=count(from,f);
        var p=new MapSqlParameterSource(f.params().getValues()).addValue("limit",size).addValue("offset",(long)(page-1)*size);
        List<T> rows=count==0 ? List.of() : jdbc.query(select+from+f.where()+" order by "+order+" limit :limit offset :offset",p,mapper);
        return new Page<>(rows,page,size,count,(int)Math.min(Integer.MAX_VALUE,(count+size-1)/size));
    }
    private RowMapper<RegisteredUser> registeredMapper() {
        return (r,n)->new RegisteredUser(r.getString("user_id"),r.getString("display_name"),time(r,"created_at"),
                r.getString("account_status"),r.getString("current_plan_tier"),time(r,"subscription_end_date"));
    }
    private RowMapper<PaymentOrder> paymentMapper() {
        return (r,n)->new PaymentOrder(r.getString("id"),r.getString("order_code"),r.getString("user_id"),
                r.getString("user_display_name"),r.getString("plan_tier"),PlanTier.valueOf(r.getString("plan_tier")).getDisplayName(),
                money(r,"amount"),r.getString("currency"),r.getString("source"),r.getString("status"),
                time(r,"created_at"),time(r,"paid_at"),r.getString("description"),time(r,"updated_at"));
    }
    public RowMapper<Appointment> appointmentMapper(OffsetDateTime now) {
        return (r,n)-> {
            Slot slot=r.getString("slot_id")==null ? null : new Slot(r.getString("slot_id"),
                    r.getObject("slot_date",LocalDate.class),r.getObject("start_time",LocalTime.class),r.getObject("end_time",LocalTime.class));
            String status=r.getString("status");
            boolean pending="PENDING_EXPERT".equals(status);
            boolean past="PENDING_CONSULTATION".equals(status) && slot!=null
                    && slot.slotDate().atTime(slot.endTime()).atZone(AdminQuery.ZONE).toInstant().plusSeconds(300).isBefore(now.toInstant());
            Integer rating=r.getObject("rating",Integer.class);
            ReviewResponse review=r.getString("review_id")==null ? null : new ReviewResponse(r.getString("review_id"),
                    r.getString("id"),r.getString("user_id"),r.getString("user_display_name"),r.getString("expert_user_id"),
                    rating.shortValue(),r.getString("comment"),time(r,"review_created_at"));
            return new Appointment(r.getString("id"),r.getString("user_id"),r.getString("user_display_name"),
                    r.getString("expert_user_id"),r.getString("expert_name"),r.getString("specialty"),
                    r.getString("assignment_type"),status,slot,time(r,"created_at"),time(r,"completed_at"),
                    pending ? hours(time(r,"created_at"),now) : null,pending||past,
                    pending ? time(r,"acceptance_deadline_at")!=null && now.isAfter(time(r,"acceptance_deadline_at"))?"ACCEPTANCE_OVERDUE":"WAITING_EXPERT" : past ? "PAST_END_UNCOMPLETED" : null,
                    time(r,"updated_at"),review,rating,r.getObject("preferred_date",LocalDate.class),
                    time(r,"acceptance_deadline_at"),time(r,"accepted_at"),r.getObject("acceptance_policy_version",Integer.class),now);
        };
    }
    public static OffsetDateTime time(ResultSet r,String column) throws SQLException { return r.getObject(column,OffsetDateTime.class); }
    public static double hours(OffsetDateTime start,OffsetDateTime now) { return Math.max(0,Duration.between(start,now).toMillis()/3600000.0); }
    public static long money(ResultSet r,String column) throws SQLException {
        BigDecimal amount=r.getBigDecimal(column);
        long value=amount==null ? 0 : amount.longValueExact();
        if(Math.abs(value)>9_007_199_254_740_991L) throw new IllegalStateException("Amount exceeds JSON safe integer range");
        return value;
    }
}

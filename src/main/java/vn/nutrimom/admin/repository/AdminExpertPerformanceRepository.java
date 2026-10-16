package vn.nutrimom.admin.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.nutrimom.admin.dto.AdminExpertPerformanceDtos.SelectedExpert;

@Repository
public class AdminExpertPerformanceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public AdminExpertPerformanceRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static String scope(String specialty,String expert) {
        return (specialty==null?"":" and e.specialty=:specialty ")
                +(expert==null?"":" and e.user_id=:expert ");
    }
    public record Experts(long total,long active,SelectedExpert selected) {}
    public Experts experts(String specialty,String expert,MapSqlParameterSource parameters) {
        return jdbc.query("""
                select count(*) total,
                  coalesce(sum(case when e.status='ACTIVE' and u.status='ACTIVE' and exists
                    (select 1 from app.user_roles r where r.user_id=u.id and r.role='EXPERT') then 1 else 0 end),0) active,
                  max(e.user_id) user_id,max(e.full_name) full_name,max(e.specialty) specialty,
                  max(e.status) expert_status,max(u.status) account_status,min(e.created_at) profile_created_at
                from app.expert_profiles e join app.users u on u.id=e.user_id where e.created_at<=:now
                """+scope(specialty,expert),parameters,(r,n)->new Experts(r.getLong("total"),r.getLong("active"),
                expert==null || r.getLong("total")==0?null:new SelectedExpert(r.getString("user_id"),r.getString("full_name"),
                r.getString("specialty"),r.getString("expert_status"),r.getString("account_status"),
                r.getObject("profile_created_at",OffsetDateTime.class)))).get(0);
    }
    public record DataWindow(java.time.LocalDate earliestScheduledDate,OffsetDateTime trackingStartedAt) { }
    public DataWindow dataWindow(MapSqlParameterSource parameters) {
        return jdbc.query("""
                select (select min(coalesce(s.slot_date,c.scheduled_date))
                  from app.consultation_requests c left join app.consultation_slots s on s.id=c.slot_id
                  where c.expert_user_id is not null and c.created_at<=:now
                    and coalesce(s.slot_date,c.scheduled_date) <= (:now at time zone 'Asia/Ho_Chi_Minh')::date
                    and coalesce(c.scheduled_starts_at,(coalesce(s.slot_date,c.scheduled_date)+
                      (coalesce(s.start_time,c.scheduled_start_time)+cast(:localTimeOffset as integer)*interval '1 second'))
                      at time zone 'Asia/Ho_Chi_Minh') < coalesce(c.scheduled_ends_at,
                      (coalesce(s.slot_date,c.scheduled_date)+(coalesce(s.end_time,c.scheduled_end_time)
                        +cast(:localTimeOffset as integer)*interval '1 second')) at time zone 'Asia/Ho_Chi_Minh')) earliest,
                  (select tracking_started_at from app.consultation_monitor_state where id=1) tracking_started_at
                """,parameters,(r,n)->new DataWindow(r.getObject("earliest",java.time.LocalDate.class),
                    r.getObject("tracking_started_at",OffsetDateTime.class))).get(0);
    }
    public record PresenceCounts(String period,String expertId,Counts counts,long known,long absent,long late,long unknown) { }
    private static String cohortV3(String specialty,String expert) {
        return """
                with scheduled as (
                  select c.id,c.user_id,c.expert_user_id,c.status,c.completed_at,
                    coalesce(s.slot_date,c.scheduled_date) scheduled_date,
                    coalesce(c.scheduled_starts_at,(coalesce(s.slot_date,c.scheduled_date)+
                      (coalesce(s.start_time,c.scheduled_start_time)+cast(:localTimeOffset as integer)*interval '1 second'))
                      at time zone 'Asia/Ho_Chi_Minh') starts_at,
                    coalesce(c.scheduled_ends_at,(coalesce(s.slot_date,c.scheduled_date)+
                      (coalesce(s.end_time,c.scheduled_end_time)+cast(:localTimeOffset as integer)*interval '1 second'))
                      at time zone 'Asia/Ho_Chi_Minh') ends_at,
                    k.checked_in_at,r.rating,r.created_at review_created_at
                  from app.consultation_requests c join app.expert_profiles e on e.user_id=c.expert_user_id
                  left join app.consultation_slots s on s.id=c.slot_id
                  left join app.expert_consultation_checkins k on k.request_id=c.id and k.expert_user_id=c.expert_user_id
                  left join app.consultation_reviews r on r.request_id=c.id and r.expert_user_id=c.expert_user_id
                  where c.status in ('PENDING_CONSULTATION','COMPLETED') and c.created_at<=:now and e.created_at<=:now
                    and coalesce(s.slot_date,c.scheduled_date) between :previousFrom and :toDate
                """+scope(specialty,expert)+" and (:filterExpertIds=false or e.user_id in (:expertIds)) "+"""
                ), all_scheduled as (
                  select *,case when scheduled_date>=:fromDate then 'CURRENT' else 'PREVIOUS' end period,
                    case when ends_at<=:now and status='COMPLETED' and completed_at<=:now then 1 else 0 end completed,
                    (checked_in_at>=starts_at-interval '5 minutes' and checked_in_at<ends_at+interval '5 minutes'
                      and checked_in_at<=:now) checked_in,
                    (starts_at-interval '5 minutes'>=(select started_at from app.expert_checkin_policy where id=1)) tracked
                  from scheduled where starts_at<ends_at
                ), eligible as (select * from all_scheduled where ends_at<=:now)
                """;
    }
    public List<PresenceCounts> countsV3(String specialty,String expert,MapSqlParameterSource p,boolean perExpert) {
        return jdbc.query(cohortV3(specialty,expert)+"""
                select period,
                """+(perExpert?"expert_user_id":"cast(null as varchar)")+" expert_id,"+"""
                  count(*) filter(where ends_at<=:now) eligible,coalesce(sum(completed),0) completed,
                  count(*) filter(where starts_at+interval '3 minutes'<=:now and checked_in) attended,
                  count(*) filter(where starts_at+interval '3 minutes'<=:now and (checked_in or tracked)) known,
                  count(*) filter(where starts_at+interval '3 minutes'<=:now and tracked and not coalesce(checked_in,false)) absent,
                  count(*) filter(where starts_at+interval '3 minutes'<=:now and checked_in and checked_in_at>starts_at+interval '3 minutes') late,
                  count(*) filter(where starts_at+interval '3 minutes'<=:now and not coalesce(checked_in,false) and not tracked) unknown,
                  count(*) filter(where starts_at+interval '3 minutes'<=:now and checked_in and checked_in_at<=starts_at+interval '3 minutes') on_time,
                  count(*) filter(where completed=1 and review_created_at<=:now) ratings,
                  count(*) filter(where completed=1 and review_created_at<=:now and rating>=4) satisfied,
                  coalesce(sum(case when completed=1 and review_created_at<=:now then rating else 0 end),0) rating_sum
                from all_scheduled group by period
                """+(perExpert?",expert_user_id":""),p,(r,n)->new PresenceCounts(r.getString("period"),r.getString("expert_id"),
                    new Counts(r.getString("period"),r.getLong("eligible"),r.getLong("completed"),r.getLong("attended"),r.getLong("attended"),
                        r.getLong("on_time"),r.getLong("ratings"),r.getLong("satisfied"),r.getLong("rating_sum")),
                    r.getLong("known"),r.getLong("absent"),r.getLong("late"),r.getLong("unknown")));
    }
    private static String cohortV2(String specialty,String expert) {
        return """
                with scheduled as (
                  select c.id,c.user_id,c.expert_user_id,c.status,c.completed_at,
                    coalesce(s.slot_date,c.scheduled_date) scheduled_date,
                    coalesce(c.scheduled_starts_at,(coalesce(s.slot_date,c.scheduled_date)+
                      (coalesce(s.start_time,c.scheduled_start_time)+cast(:localTimeOffset as integer)*interval '1 second'))
                      at time zone 'Asia/Ho_Chi_Minh') starts_at,
                    coalesce(c.scheduled_ends_at,(coalesce(s.slot_date,c.scheduled_date)+
                      (coalesce(s.end_time,c.scheduled_end_time)+cast(:localTimeOffset as integer)*interval '1 second'))
                      at time zone 'Asia/Ho_Chi_Minh') ends_at,
                    v.expert_joined_at,r.rating,r.created_at review_created_at
                  from app.consultation_requests c join app.expert_profiles e on e.user_id=c.expert_user_id
                  left join app.consultation_slots s on s.id=c.slot_id
                  left join app.consultation_video_sessions v on v.request_id=c.id
                  left join app.consultation_reviews r on r.request_id=c.id and r.expert_user_id=c.expert_user_id
                  where c.status in ('PENDING_CONSULTATION','COMPLETED') and c.created_at<=:now and e.created_at<=:now
                    and coalesce(s.slot_date,c.scheduled_date) between :previousFrom and :toDate
                """+scope(specialty,expert)+" and (:filterExpertIds=false or e.user_id in (:expertIds)) "+"""
                ), evidence as (
                  select s.*,case when scheduled_date>=:fromDate then 'CURRENT' else 'PREVIOUS' end period,
                    (expert_joined_at>=starts_at-interval '5 minutes' and expert_joined_at<ends_at+interval '5 minutes'
                       and expert_joined_at<=:now) joined,
                    (m.tracking_started_at<=starts_at-interval '5 minutes'
                       and m.checked_at>=ends_at+interval '6 minutes'
                       and not exists(select 1 from app.consultation_monitor_gaps g
                         where g.starts_at<s.ends_at+interval '6 minutes' and g.ends_at>s.starts_at-interval '5 minutes')) reliable,
                    case when status='COMPLETED' and completed_at<=:now then 1 else 0 end completed
                  from scheduled s left join app.consultation_monitor_state m on m.id=1
                  where starts_at<ends_at and ends_at<=:now
                ), eligible as (
                  select *,case when joined then 1 else 0 end attended,
                    case when joined or reliable then 1 else 0 end known,
                    case when joined and expert_joined_at<=starts_at+interval '3 minutes' then 1 else 0 end on_time
                  from evidence
                )
                """;
    }
    public List<PresenceCounts> countsV2(String specialty,String expert,MapSqlParameterSource parameters,boolean perExpert) {
        return jdbc.query(cohortV2(specialty,expert)+"""
                select period,
                """+(perExpert?"expert_user_id":"cast(null as varchar)")+" as expert_id,"+"""
                  count(*) eligible,sum(completed) completed,sum(attended) attended,sum(on_time) on_time,
                  sum(known) known,sum(case when known=1 and attended=0 then 1 else 0 end) absent,
                  sum(case when attended=1 and on_time=0 then 1 else 0 end) late,
                  sum(case when known=0 then 1 else 0 end) unknown,
                  count(*) filter(where completed=1 and review_created_at<=:now) ratings,
                  count(*) filter(where completed=1 and review_created_at<=:now and rating>=4) satisfied,
                  coalesce(sum(case when completed=1 and review_created_at<=:now then rating else 0 end),0) rating_sum
                from eligible group by period
                """+(perExpert?",expert_user_id":""),parameters,(r,n)->new PresenceCounts(r.getString("period"),r.getString("expert_id"),
                    new Counts(r.getString("period"),r.getLong("eligible"),r.getLong("completed"),r.getLong("attended"),
                        r.getLong("attended"),r.getLong("on_time"),r.getLong("ratings"),r.getLong("satisfied"),r.getLong("rating_sum")),
                    r.getLong("known"),r.getLong("absent"),r.getLong("late"),r.getLong("unknown")));
    }

    public List<SelectedExpert> reportExperts(String specialty,String expert,MapSqlParameterSource parameters,int offset,int limit) {
        return jdbc.query("""
                select e.user_id,e.full_name,e.specialty,e.status expert_status,u.status account_status,e.created_at
                from app.expert_profiles e join app.users u on u.id=e.user_id where e.created_at<=:now
                """+scope(specialty,expert)+" order by e.full_name,e.user_id limit :limit offset :offset",
                parameters.addValue("limit",limit).addValue("offset",offset),(r,n)->new SelectedExpert(r.getString("user_id"),r.getString("full_name"),
                    r.getString("specialty"),r.getString("expert_status"),r.getString("account_status"),r.getObject("created_at",OffsetDateTime.class)));
    }
    public record ExpertExtended(String expertId,ExtendedCounts counts) { }
    public List<ExpertExtended> extendedPerExpert(String specialty,String expert,MapSqlParameterSource parameters) {
        return extendedPerExpert(specialty,expert,parameters,false);
    }
    public List<ExpertExtended> extendedPerExpert(String specialty,String expert,MapSqlParameterSource parameters,boolean v3) {
        return jdbc.query((v3?cohortV3(specialty,expert):cohortV2(specialty,expert))+"""
                , first_completed as materialized (
                  select h.expert_user_id,h.user_id,min(h.completed_at) first_at from app.consultation_requests h
                  join (select distinct expert_user_id,user_id from eligible where completed=1) pairs
                    on pairs.expert_user_id=h.expert_user_id and pairs.user_id=h.user_id
                  where h.status='COMPLETED' and h.created_at<=:now and h.completed_at<=:now
                  group by h.expert_user_id,h.user_id
                ), customers as (
                  select e.period,e.expert_user_id,e.user_id,bool_or(f.first_at<e.starts_at and f.first_at<e.completed_at) is_returning
                  from eligible e join first_completed f on f.expert_user_id=e.expert_user_id and f.user_id=e.user_id
                  where e.completed=1 group by e.period,e.expert_user_id,e.user_id
                ), totals as (
                  select period,expert_user_id,count(*) customers,count(*) filter(where is_returning) returning_customers
                  from customers group by period,expert_user_id
                ), acceptance as (
                  select case when (coalesce(c.acceptance_opened_at,c.created_at) at time zone 'Asia/Ho_Chi_Minh')::date>=:fromDate
                    then 'CURRENT' else 'PREVIOUS' end period,c.expert_user_id,
                    count(*) filter(where c.acceptance_deadline_at is not null and c.accepted_at<=:now) acceptance_eligible,
                    count(*) filter(where c.acceptance_deadline_at is not null and c.accepted_at<=:now
                      and c.accepted_at>=c.acceptance_opened_at and c.accepted_at<=c.acceptance_deadline_at) accepted_in_time,
                    count(*) filter(where c.acceptance_deadline_at is null or c.accepted_at is null or c.accepted_at>:now) acceptance_missing
                  from app.consultation_requests c join app.expert_profiles e on e.user_id=c.expert_user_id
                  where c.assignment_type='RANDOM' and c.status<>'CANCELLED' and c.created_at<=:now and e.created_at<=:now
                    and coalesce(c.acceptance_opened_at,c.created_at)>=:previousStart
                    and coalesce(c.acceptance_opened_at,c.created_at)<:until
                """+scope(specialty,expert)+" and (:filterExpertIds=false or e.user_id in (:expertIds)) "+"""
                  group by period,c.expert_user_id
                ) select coalesce(t.period,a.period) period,coalesce(t.expert_user_id,a.expert_user_id) expert_id,
                    coalesce(t.customers,0) customers,coalesce(t.returning_customers,0) returning_customers,
                    coalesce(a.acceptance_eligible,0) acceptance_eligible,coalesce(a.accepted_in_time,0) accepted_in_time,
                    coalesce(a.acceptance_missing,0) acceptance_missing
                  from totals t full join acceptance a on a.period=t.period and a.expert_user_id=t.expert_user_id
                """,parameters,(r,n)->new ExpertExtended(r.getString("expert_id"),new ExtendedCounts(r.getString("period"),
                    r.getLong("acceptance_eligible"),r.getLong("accepted_in_time"),r.getLong("acceptance_missing"),
                    r.getLong("customers"),r.getLong("returning_customers"))));
    }

    public record Counts(String period,long eligible,long completed,long observed,long attended,long onTime,
                         long ratings,long satisfied,long ratingSum) {
        public static Counts empty(String period) {return new Counts(period,0,0,0,0,0,0,0,0);}
    }
    /** Two bounded aggregate rows for both periods; no per-expert or per-appointment reads. PostgreSQL SQL. */
    private static String cohort(String specialty,String expert) {
        return """
                with scheduled as (
                  select c.id,c.user_id,c.expert_user_id,c.status,c.completed_at,coalesce(s.slot_date,c.scheduled_date) scheduled_date,
                    (coalesce(s.slot_date,c.scheduled_date) + (coalesce(s.start_time,c.scheduled_start_time)
                       + cast(:localTimeOffset as integer)*interval '1 second')) at time zone 'Asia/Ho_Chi_Minh' starts_at,
                    (coalesce(s.slot_date,c.scheduled_date) + (coalesce(s.end_time,c.scheduled_end_time)
                       + cast(:localTimeOffset as integer)*interval '1 second')) at time zone 'Asia/Ho_Chi_Minh' ends_at,
                    v.opens_at,v.closes_at,v.user_joined_at,v.expert_joined_at,
                    r.rating,r.created_at review_created_at
                  from app.consultation_requests c
                  join app.expert_profiles e on e.user_id=c.expert_user_id
                  left join app.consultation_slots s on s.id=c.slot_id
                  left join app.consultation_video_sessions v on v.request_id=c.id
                  left join app.consultation_reviews r on r.request_id=c.id and r.expert_user_id=c.expert_user_id
                  where c.status in ('PENDING_CONSULTATION','COMPLETED') and c.created_at<=:now and e.created_at<=:now
                    and coalesce(s.slot_date,c.scheduled_date) between :previousFrom and :toDate
                """+scope(specialty,expert)+"""
                ), eligible as (
                  select *,case when scheduled_date>=:fromDate then 'CURRENT' else 'PREVIOUS' end period,
                    case when (status='COMPLETED' and completed_at<=:now)
                           or (expert_joined_at between opens_at and closes_at and expert_joined_at<=ends_at
                              and expert_joined_at<=:now) then 1 else 0 end attended,
                    case when (user_joined_at between opens_at and closes_at and user_joined_at<=:now)
                           or (expert_joined_at between opens_at and closes_at and expert_joined_at<=:now)
                         then 1 else 0 end observed,
                    case when status='COMPLETED' and completed_at<=:now then 1 else 0 end completed
                  from scheduled where starts_at<ends_at and ends_at<=:now
                )
                """;
    }
    public List<Counts> counts(String specialty,String expert,MapSqlParameterSource parameters) {
        return jdbc.query(cohort(specialty,expert)+"""
                select period,count(*) eligible,sum(completed) completed,sum(observed) observed,sum(attended) attended,
                  sum(case when completed=1 and completed_at<=ends_at then 1 else 0 end) on_time,
                  sum(case when completed=1 and review_created_at<=:now then 1 else 0 end) ratings,
                  sum(case when completed=1 and review_created_at<=:now and rating>=4 then 1 else 0 end) satisfied,
                  coalesce(sum(case when completed=1 and review_created_at<=:now then rating else 0 end),0) rating_sum
                from eligible group by period
                """,parameters,(r,n)->new Counts(r.getString("period"),r.getLong("eligible"),r.getLong("completed"),
                r.getLong("observed"),r.getLong("attended"),r.getLong("on_time"),r.getLong("ratings"),
                r.getLong("satisfied"),r.getLong("rating_sum")));
    }
    public record ExtendedCounts(String period,long acceptanceEligible,long acceptedInTime,long acceptanceMissing,
                                 long customers,long returningCustomers) {
        public static ExtendedCounts empty(String period) {return new ExtendedCounts(period,0,0,0,0,0);}
    }
    /** Four reads total for the extended endpoint, regardless of number of customers or experts. */
    public List<ExtendedCounts> extendedCounts(String specialty,String expert,MapSqlParameterSource parameters) {
        return extendedCounts(specialty,expert,parameters,false);
    }
    public List<ExtendedCounts> extendedCounts(String specialty,String expert,MapSqlParameterSource parameters,boolean v2) {
        return extendedCounts(specialty,expert,parameters,v2,false);
    }
    public List<ExtendedCounts> extendedCounts(String specialty,String expert,MapSqlParameterSource parameters,boolean v2,boolean v3) {
        String acceptanceScope=(specialty==null?"":" and coalesce(e.specialty,c.specialty)=:specialty ")
                +(expert==null?"":" and c.expert_user_id=:expert ");
        return jdbc.query((v3?cohortV3(specialty,expert):v2?cohortV2(specialty,expert):cohort(specialty,expert))+"""
                , first_completed as materialized (
                  select h.expert_user_id,h.user_id,min(h.completed_at) first_at
                  from app.consultation_requests h
                  join (select distinct expert_user_id,user_id from eligible where completed=1) pairs
                    on pairs.expert_user_id=h.expert_user_id and pairs.user_id=h.user_id
                  where h.status='COMPLETED' and h.created_at<=:now and h.completed_at<=:now
                  group by h.expert_user_id,h.user_id
                ), customers as (
                  select e.period,e.user_id,bool_or(f.first_at<e.starts_at and f.first_at<e.completed_at) is_returning
                  from eligible e join first_completed f
                    on f.expert_user_id=e.expert_user_id and f.user_id=e.user_id
                  where e.completed=1 group by e.period,e.user_id
                ), returning_totals as (
                  select period,count(*) customers,count(*) filter(where is_returning) returning_customers
                  from customers group by period
                ), acceptance as (
                  select case when (coalesce(c.acceptance_opened_at,c.created_at) at time zone 'Asia/Ho_Chi_Minh')::date>=:fromDate
                           then 'CURRENT' else 'PREVIOUS' end period,
                    c.acceptance_opened_at,c.accepted_at,
                """+(v2?"c.acceptance_deadline_at":"app.consultation_acceptance_deadline(c.acceptance_opened_at,cast(:acceptanceMinutes as integer))")+" as deadline_at,\n"+"""
                    (c.acceptance_opened_at is not null and
                """+(v2?"c.acceptance_deadline_at is not null and ":"")+"""
                      ((c.accepted_at is not null and c.accepted_at<=:now)
                        or (c.status='PENDING_EXPERT' and c.expert_user_id is null))) known
                  from app.consultation_requests c
                  left join app.expert_profiles e on e.user_id=c.expert_user_id
                  where c.assignment_type='RANDOM' and c.status<>'CANCELLED' and c.created_at<=:now
                    and (c.expert_user_id is null or e.created_at<=:now)
                    and coalesce(c.acceptance_opened_at,c.created_at)>=:previousStart
                    and coalesce(c.acceptance_opened_at,c.created_at)<:until
                    and coalesce(c.acceptance_opened_at,c.created_at)<=:now
                """+acceptanceScope+"""
                ), acceptance_totals as (
                  select period,
                    count(*) filter(where known and (accepted_at<=:now
                      or deadline_at<:now)) acceptance_eligible,
                    count(*) filter(where known and accepted_at<=:now
                      and accepted_at>=acceptance_opened_at
                      and accepted_at<=deadline_at) accepted_in_time,
                    count(*) filter(where not known) acceptance_missing
                  from acceptance group by period
                ), periods as (select 'CURRENT' period union all select 'PREVIOUS')
                select p.period,coalesce(a.acceptance_eligible,0) acceptance_eligible,
                  coalesce(a.accepted_in_time,0) accepted_in_time,coalesce(a.acceptance_missing,0) acceptance_missing,
                  coalesce(r.customers,0) customers,coalesce(r.returning_customers,0) returning_customers
                from periods p left join acceptance_totals a on a.period=p.period
                  left join returning_totals r on r.period=p.period
                """,parameters,(r,n)->new ExtendedCounts(r.getString("period"),r.getLong("acceptance_eligible"),
                    r.getLong("accepted_in_time"),r.getLong("acceptance_missing"),r.getLong("customers"),r.getLong("returning_customers")));
    }

}

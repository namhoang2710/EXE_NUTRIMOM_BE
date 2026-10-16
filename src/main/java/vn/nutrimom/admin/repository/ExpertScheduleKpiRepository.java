package vn.nutrimom.admin.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class ExpertScheduleKpiRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ExpertScheduleKpiRepository(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Window(LocalDate earliestExpertDate,LocalDate filterMinDate,OffsetDateTime trackingStartedAt) { }
    public record Schedule(String period,String expertId,long standardSlots,long offSlots,long dayOffDays,long closedSlots) {
        public long availableSlots() {return standardSlots-offSlots;}
        public static Schedule empty(String period) {return new Schedule(period,null,0,0,0,0);}
    }
    public Window window(MapSqlParameterSource p) {
        return jdbc.query("""
                select (select min((e.created_at at time zone 'Asia/Ho_Chi_Minh')::date)
                  from app.expert_profiles e where e.created_at<=:now
                  and (:specialty is null or e.specialty=:specialty) and (:expert is null or e.user_id=:expert)) earliest,
                  (select min((e.created_at at time zone 'Asia/Ho_Chi_Minh')::date)
                    from app.expert_profiles e where e.created_at<=:now) filter_min_date,
                  (select started_at from app.expert_checkin_policy where id=1) tracking
                """,p,(r,n)->new Window(r.getObject("earliest",LocalDate.class),r.getObject("filter_min_date",LocalDate.class),r.getObject("tracking",OffsetDateTime.class))).get(0);
    }
    /** Aggregate stored exceptions only: no expert x day x 24-slot expansion and no N+1. */
    public List<Schedule> counts(MapSqlParameterSource p,boolean perExpert) {
        return jdbc.query("""
                with periods as (
                  select 'CURRENT' period,cast(:fromDate as date) first_day,cast(:toDate as date) last_day
                  union all select 'PREVIOUS',cast(:previousFrom as date),cast(:fromDate as date)-1
                ), exposure as (
                  select e.user_id,period,greatest(first_day,(e.created_at at time zone 'Asia/Ho_Chi_Minh')::date) first_day,last_day
                  from app.expert_profiles e cross join periods
                  where e.created_at<=:now and (:specialty is null or e.specialty=:specialty)
                    and (:expert is null or e.user_id=:expert) and (:filterExpertIds=false or e.user_id in (:expertIds))
                ), days_off as (
                  select x.user_id,x.period,count(*) n from exposure x join app.expert_day_offs d
                    on d.expert_user_id=x.user_id and d.off_date between x.first_day and x.last_day
                  group by x.user_id,x.period
                ), exceptions as (
                  select x.user_id,x.period,
                    count(*) filter(where s.status='CLOSED' and d.id is null) closed,
                    count(*) filter(where s.status='BOOKED' and d.id is not null) booked_on_day_off
                  from exposure x join app.consultation_slots s on s.expert_user_id=x.user_id
                    and s.slot_date between x.first_day and x.last_day
                  left join app.expert_day_offs d on d.expert_user_id=x.user_id and d.off_date=s.slot_date
                  where (s.start_time+cast(:localTimeOffset as integer)*interval '1 second')>=time '08:00'
                    and (s.start_time+cast(:localTimeOffset as integer)*interval '1 second')<time '20:00'
                    and extract(minute from (s.start_time+cast(:localTimeOffset as integer)*interval '1 second')) in (0,30)
                    and extract(second from (s.start_time+cast(:localTimeOffset as integer)*interval '1 second'))=0
                    and s.end_time-s.start_time=interval '30 minutes'
                  group by x.user_id,x.period
                ), totals as (
                  select x.user_id,x.period,24*greatest(0,x.last_day-x.first_day+1) standard_slots,
                    coalesce(d.n,0)*24+coalesce(s.closed,0)-coalesce(s.booked_on_day_off,0) off_slots,
                    coalesce(d.n,0) day_off_days,coalesce(s.closed,0) closed_slots
                  from exposure x left join days_off d on d.user_id=x.user_id and d.period=x.period
                  left join exceptions s on s.user_id=x.user_id and s.period=x.period
                ) select period,
                """+(perExpert?"user_id":"cast(null as varchar)")+" expert_id,"+"""
                  sum(standard_slots) standard_slots,sum(off_slots) off_slots,sum(day_off_days) day_off_days,sum(closed_slots) closed_slots
                  from totals group by period
                """+(perExpert?",user_id":""),p,(r,n)->new Schedule(r.getString("period"),r.getString("expert_id"),
                    r.getLong("standard_slots"),r.getLong("off_slots"),r.getLong("day_off_days"),r.getLong("closed_slots")));
    }
}

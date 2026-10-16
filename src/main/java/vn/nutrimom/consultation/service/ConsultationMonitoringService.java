package vn.nutrimom.consultation.service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.nutrimom.consultation.domain.*;
import vn.nutrimom.consultation.video.*;
import vn.nutrimom.notification.domain.AdminNotificationEvent;
import vn.nutrimom.notification.service.*;

/** Bounded, restart-safe monitoring. Business row locks serialize claims, joins and warning decisions. */
@Service
public class ConsultationMonitoringService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final VideoProperties video;
    private final LiveKitGateway provider;
    private final NotificationService notifications;
    private final AdminNotificationEvents adminEvents;
    public ConsultationMonitoringService(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,
            VideoProperties video,LiveKitGateway provider,NotificationService notifications,AdminNotificationEvents adminEvents) {
        this.jdbc=jdbc;this.clock=clock;this.video=video;this.provider=provider;this.notifications=notifications;this.adminEvents=adminEvents;
        this.transaction=new TransactionTemplate(manager);this.transaction.setTimeout(10);
    }
    public void sweep() {
        boolean healthy=false;
        if(video.ready() && video.isPresenceTrackingEnabled()) try {provider.probe();healthy=true;} catch(RuntimeException ignored) { /* Persist unknown coverage below. */ }
        recordHealth(healthy);
        transaction.executeWithoutResult(tx->prepareExistingSchedules());
        checkDeadlines();
        transaction.executeWithoutResult(tx->processArrivals());
        transaction.executeWithoutResult(tx->finalizeEntryKpi());
        transaction.executeWithoutResult(tx->finalizeAttendance());
    }
    public void recordHealth(boolean healthy) {
        transaction.executeWithoutResult(tx->{
            var now=OffsetDateTime.now(clock);
            var p=new MapSqlParameterSource("now",now).addValue("healthy",healthy);
            var previous=jdbc.query("select started_at,checked_at,healthy from app.consultation_monitor_state where id=1 for update",
                    p,(r,n)->new Health(r.getObject("started_at",OffsetDateTime.class),r.getObject("checked_at",OffsetDateTime.class),r.getBoolean("healthy"))).get(0);
            if(previous.checked()!=null && !now.isAfter(previous.checked())) return;
            var from=previous.checked()==null?previous.started():previous.checked();
            if(from.isBefore(now) && (!healthy || !previous.healthy() || Duration.between(from,now).getSeconds()>45)) {
                p.addValue("from",from);
                int merged=previous.healthy()?0:jdbc.update("update app.consultation_monitor_gaps set ends_at=:now where ends_at=:from",p);
                if(merged==0) jdbc.update("insert into app.consultation_monitor_gaps(starts_at,ends_at) values(:from,:now)",p);
            }
            jdbc.update("""
                    update app.consultation_monitor_state set checked_at=:now,healthy=:healthy,
                      tracking_started_at=case when :healthy and tracking_started_at is null then :now else tracking_started_at end where id=1
                    """,p);
        });
    }
    /** Future legacy bookings already have factual schedule metadata; never backfill join evidence. */
    private void prepareExistingSchedules() {
        var now=OffsetDateTime.now(clock);var day=now.atZoneSameInstant(ConsultationTiming.ZONE).toLocalDate();
        int offset=ZoneId.systemDefault().getRules().getOffset(Instant.EPOCH).getTotalSeconds();
        var p=new MapSqlParameterSource("today",day).addValue("last",day.plusDays(30)).addValue("offset",offset);
        jdbc.update("""
                with candidates as (
                  select c.id,x.d,x.st,x.en
                  from app.consultation_requests c left join app.consultation_slots s on s.id=c.slot_id
                  cross join lateral (select coalesce(s.slot_date,c.scheduled_date) d,
                    coalesce(s.start_time,c.scheduled_start_time)+cast(:offset as integer)*interval '1 second' st,
                    coalesce(s.end_time,c.scheduled_end_time)+cast(:offset as integer)*interval '1 second' en) x
                  where c.status='PENDING_CONSULTATION' and c.scheduled_starts_at is null
                    and x.d between :today and :last and x.st<x.en
                    and (x.d+x.st) at time zone 'Asia/Ho_Chi_Minh'>=(select started_at from app.consultation_monitor_state where id=1)
                  order by x.d,c.id limit 100 for update of c skip locked
                ) update app.consultation_requests c set
                    scheduled_starts_at=(x.d+x.st) at time zone 'Asia/Ho_Chi_Minh',
                    scheduled_ends_at=(x.d+x.en) at time zone 'Asia/Ho_Chi_Minh'
                  from candidates x where c.id=x.id
                """,p);
    }
    private void finalizeAttendance() {
        var p=new MapSqlParameterSource("now",OffsetDateTime.now(clock));
        var rows=jdbc.query("""
                select c.id,case when exists(select 1 from app.consultation_monitor_events m
                    where m.request_id=c.id and m.event_kind='KPI_DUE') then 'ATTENDANCE_DUE' else 'KPI_DUE' end kind
                from app.consultation_requests c
                where c.status in ('PENDING_CONSULTATION','COMPLETED') and c.attendance_finalized_at is null
                  and c.scheduled_ends_at<=:now
                  and c.scheduled_starts_at>=(select started_at from app.consultation_monitor_state where id=1)
                  and (c.scheduled_ends_at+interval '6 minutes'<=:now or not exists(
                    select 1 from app.consultation_monitor_events m where m.request_id=c.id and m.event_kind='KPI_DUE'))
                order by c.scheduled_ends_at,c.id limit 100 for update of c skip locked
                """,p,(r,n)->new Finalization(r.getString("id"),r.getString("kind")));
        for(var row:rows) {
            mark(row.id(),row.kind(),p);
            if(row.kind().equals("ATTENDANCE_DUE")) jdbc.update(
                    "update app.consultation_requests set attendance_finalized_at=:now where id=:id",p.addValue("id",row.id()));
        }
    }
    private record Finalization(String id,String kind) { }
    private void finalizeEntryKpi() {
        var p=new MapSqlParameterSource("now",OffsetDateTime.now(clock));
        var ids=jdbc.queryForList("""
                select c.id from app.consultation_requests c
                where c.entry_kpi_finalized_at is null and c.status in ('PENDING_CONSULTATION','COMPLETED')
                  and c.scheduled_starts_at<=:now-interval '3 minutes'
                  and c.scheduled_starts_at>=(select started_at+interval '5 minutes' from app.expert_checkin_policy where id=1)
                order by c.scheduled_starts_at,c.id limit 100 for update of c skip locked
                """,p,String.class);
        for(String id:ids) {
            mark(id,"CHECKIN_KPI_DUE",p);
            jdbc.update("update app.consultation_requests set entry_kpi_finalized_at=:now where id=:id",p.addValue("id",id));
        }
    }

    public void checkDeadlines() {
        transaction.executeWithoutResult(tx->processDeadlines());
    }
    private void processDeadlines() {
        var p=new MapSqlParameterSource("now",OffsetDateTime.now(clock));
        var ids=jdbc.queryForList("""
                select c.id from app.consultation_requests c
                where c.assignment_type='RANDOM' and c.status='PENDING_EXPERT' and c.acceptance_deadline_at<:now
                  and c.acceptance_deadline_at>=(select started_at from app.consultation_monitor_state where id=1)
                  and not exists(select 1 from app.consultation_monitor_events m where m.request_id=c.id and m.event_kind='ACCEPTANCE_OVERDUE')
                order by c.acceptance_deadline_at,c.id limit 100 for update of c skip locked
                """,p,String.class);
        for(String id:ids) {
            mark(id,"ACCEPTANCE_OVERDUE",p);
            adminEvents.record(AdminNotificationEvent.ADMIN_ACCEPTANCE_OVERDUE,id);
        }
    }
    private void processArrivals() {
        var p=new MapSqlParameterSource("now",OffsetDateTime.now(clock));
        var rows=jdbc.query("""
                select c.id,c.expert_user_id,c.scheduled_starts_at,c.scheduled_ends_at
                from app.consultation_requests c
                where c.status='PENDING_CONSULTATION' and c.expert_user_id is not null
                  and c.scheduled_starts_at+interval '3 minutes'<:now and c.scheduled_ends_at>:now
                  and c.scheduled_starts_at>=(select started_at+interval '5 minutes' from app.expert_checkin_policy where id=1)
                  and not exists(select 1 from app.expert_consultation_checkins v where v.request_id=c.id and v.expert_user_id=c.expert_user_id)
                  and not exists(select 1 from app.consultation_monitor_events m where m.request_id=c.id and m.event_kind='ARRIVAL_WARNING')
                order by c.scheduled_starts_at,c.id limit 100 for update of c skip locked
                """,p,(r,n)->new Due(r.getString("id"),r.getString("expert_user_id"),r.getObject("scheduled_starts_at",OffsetDateTime.class),
                    r.getObject("scheduled_ends_at",OffsetDateTime.class)));
        for(var row:rows) {
            mark(row.id(),"ARRIVAL_WARNING",p);
            notifications.publishExpertTimingOnce(row.expert(),row.id(),"Chưa ghi nhận bạn vào phòng tư vấn",
                    "Đã quá giờ bắt đầu 3 phút của lịch "+label(row.start(),row.end())+". Vui lòng bấm Vào phòng tư vấn; hệ thống ghi nhận thời điểm bấm bằng giờ máy chủ.");
        }
    }
    /** Called while the booking row is already locked by the verified webhook transaction. */
    public void verifiedExpertJoin(ConsultationRequestEntity request,Instant firstJoin) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from app.expert_consultation_checkins where request_id=:id)",
                new MapSqlParameterSource("id",request.getId()),Boolean.class))) return;
        // After v3 activation, connection evidence cannot override entry-action notifications.
        var policy=jdbc.queryForObject("select max(started_at) from app.expert_checkin_policy where id=1",new MapSqlParameterSource(),OffsetDateTime.class);
        if(request.getScheduledStartsAt()!=null && policy!=null && !request.getScheduledStartsAt().minusMinutes(5).isBefore(policy)) return;
        arrivalNotice(request,firstJoin);
    }
    /** Called inside the same booking lock and transaction as the first check-in. */
    public void expertCheckedIn(ConsultationRequestEntity request,Instant firstClick) {
        arrivalNotice(request,firstClick);
    }
    private void arrivalNotice(ConsultationRequestEntity request,Instant firstJoin) {
        var start=request.getScheduledStartsAt();var end=request.getScheduledEndsAt();
        if(start==null || end==null || firstJoin.isBefore(start.toInstant().minusSeconds(300))) return; // Legacy scheduling is never guessed.
        if(ConsultationTiming.onTime(firstJoin,start.toInstant())) {
            var p=new MapSqlParameterSource("key","EXPERT_ARRIVAL:"+request.getExpertUserId()+":"+request.getId())
                    .addValue("body","Đã xác nhận bạn vào phòng đúng giờ cho lịch "+label(start,end)+".")
                    .addValue("now",OffsetDateTime.now(clock));
            jdbc.update("""
                    update app.notifications set title='Đã xác nhận vào phòng đúng giờ',body=:body,updated_at=:now,version=version+1
                    where dedup_key=:key and body<>:body
                    """,p);
        } else notifications.publishExpertTimingOnce(request.getExpertUserId(),request.getId(),"Bạn đã vào phòng tư vấn trễ giờ",
                "Lịch "+label(start,end)+"; lần vào phòng đầu tiên lúc "
                    +firstJoin.atZone(ConsultationTiming.ZONE).format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy"))+".");
    }
    private void mark(String id,String kind,MapSqlParameterSource p) {
        jdbc.update("insert into app.consultation_monitor_events(request_id,event_kind,created_at) values(:id,:kind,:now) on conflict do nothing",
                p.addValue("id",id).addValue("kind",kind));
    }
    private static String label(OffsetDateTime start,OffsetDateTime end) {
        return start.atZoneSameInstant(ConsultationTiming.ZONE).format(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"))
                +"–"+end.atZoneSameInstant(ConsultationTiming.ZONE).format(DateTimeFormatter.ofPattern("HH:mm"));
    }
    private record Health(OffsetDateTime started,OffsetDateTime checked,boolean healthy) { }
    private record Due(String id,String expert,OffsetDateTime start,OffsetDateTime end) { }
}

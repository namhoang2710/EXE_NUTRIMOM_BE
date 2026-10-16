package vn.nutrimom.consultation.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.*;
import vn.nutrimom.consultation.domain.*;
import vn.nutrimom.consultation.repository.*;

/** Server-received entry action; deliberately independent of LiveKit token/connection success. */
@Service
public class ExpertCheckinService {
    private final ConsultationRequestRepository requests;
    private final AvailabilitySlotRepository slots;
    private final ExpertProfileRepository experts;
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;
    private final ConsultationMonitoringService monitoring;
    private final Clock clock;
    public ExpertCheckinService(ConsultationRequestRepository requests,AvailabilitySlotRepository slots,
            ExpertProfileRepository experts,UserRepository users,NamedParameterJdbcTemplate jdbc,
            ConsultationMonitoringService monitoring,Clock clock) {
        this.requests=requests;this.slots=slots;this.experts=experts;this.users=users;
        this.jdbc=jdbc;this.monitoring=monitoring;this.clock=clock;
    }
    public record Checkin(String requestId,OffsetDateTime checkedInAt,OffsetDateTime scheduledStartsAt,
                          OffsetDateTime onTimeUntil,boolean onTime,String evidence) { }
    @Transactional(timeout=10)
    public Checkin checkIn(String actor,String id) {return record(actor,id,false);}
    /** The existing join endpoint also captures the action, so current clients keep working. */
    @Transactional(timeout=10)
    public void checkInIfExpert(String actor,String id) {record(actor,id,true);}
    private Checkin record(String actor,String id,boolean allowPatient) {
        // Match PostgreSQL timestamp precision so receipt and aggregate agree at the +3m boundary.
        var received=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var c=requests.findByIdForUpdate(id).orElseThrow(()->new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var user=users.findById(actor).filter(u->u.getStatus()==UserStatus.ACTIVE)
                .orElseThrow(()->new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE));
        if(allowPatient && actor.equals(c.getUserId())) return null;
        if(!actor.equals(c.getExpertUserId()) || !user.getRoles().contains(UserRole.EXPERT)
                || experts.findByUserIdAndStatus(actor,ExpertStatus.ACTIVE).isEmpty())
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        if(c.getScheduledStartsAt()==null && c.getSlotId()!=null)
            slots.findById(c.getSlotId()).ifPresent(c::captureScheduledSlot);
        var start=c.getScheduledStartsAt();var end=c.getScheduledEndsAt();
        if(start==null || end==null) throw new BusinessException(ErrorCode.VIDEO_NOT_OPEN);
        var params=new MapSqlParameterSource("id",id).addValue("expert",actor)
                .addValue("at",received.atOffset(ZoneOffset.UTC));
        var existing=jdbc.query("select checked_in_at from app.expert_consultation_checkins where request_id=:id and expert_user_id=:expert",
                params,(r,n)->r.getObject(1,OffsetDateTime.class));
        // Replays return the original receipt, even after completion; never rewrite it.
        if(!existing.isEmpty()) return result(id,existing.get(0),start);
        if(c.getStatus()!=ConsultationStatus.PENDING_CONSULTATION
                || !start.isBefore(end) || received.isBefore(start.toInstant().minusSeconds(300)))
            throw new BusinessException(ErrorCode.VIDEO_NOT_OPEN);
        if(!received.isBefore(end.toInstant().plusSeconds(300))) throw new BusinessException(ErrorCode.VIDEO_ENDED);
        if(users.findById(c.getUserId()).filter(u->u.getStatus()==UserStatus.ACTIVE).isEmpty())
            throw new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE);
        jdbc.update("insert into app.expert_consultation_checkins(request_id,expert_user_id,checked_in_at) values(:id,:expert,:at)",params);
        monitoring.expertCheckedIn(c,received);
        return result(id,received.atOffset(ZoneOffset.UTC),start);
    }
    private static Checkin result(String id,OffsetDateTime at,OffsetDateTime start) {
        return new Checkin(id,at,start,start.plusMinutes(3),!at.isAfter(start.plusMinutes(3)),"SERVER_ENTRY_ACTION");
    }
}

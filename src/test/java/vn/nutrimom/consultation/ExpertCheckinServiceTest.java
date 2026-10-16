package vn.nutrimom.consultation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.consultation.domain.*;
import vn.nutrimom.consultation.repository.*;
import vn.nutrimom.consultation.service.*;
import vn.nutrimom.common.exception.BusinessException;

class ExpertCheckinServiceTest {
    final ConsultationRequestRepository requests=mock(ConsultationRequestRepository.class);
    final AvailabilitySlotRepository slots=mock(AvailabilitySlotRepository.class);
    final ExpertProfileRepository experts=mock(ExpertProfileRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    final ConsultationMonitoringService monitoring=mock(ConsultationMonitoringService.class);
    final ConsultationRequestEntity request=new ConsultationRequestEntity();
    final OffsetDateTime start=OffsetDateTime.parse("2026-10-15T08:00:00+07:00");
    @BeforeEach void setup() {
        request.setExpertUserId("expert");request.setUserId("patient");request.setStatus(ConsultationStatus.PENDING_CONSULTATION);
        var slot=new AvailabilitySlotEntity();slot.setSlotDate(start.toLocalDate());slot.setStartTime(start.toLocalTime());slot.setEndTime(start.toLocalTime().plusMinutes(30));
        request.captureScheduledSlot(slot);
        when(requests.findByIdForUpdate("booking")).thenReturn(Optional.of(request));
        var expert=new UserEntity();expert.setStatus(UserStatus.ACTIVE);expert.setRoles(Set.of(UserRole.EXPERT));
        var patient=new UserEntity();patient.setStatus(UserStatus.ACTIVE);patient.setRoles(Set.of(UserRole.USER));
        when(users.findById("expert")).thenReturn(Optional.of(expert));when(users.findById("patient")).thenReturn(Optional.of(patient));
        when(experts.findByUserIdAndStatus("expert",ExpertStatus.ACTIVE)).thenReturn(Optional.of(new ExpertProfileEntity()));
    }
    ExpertCheckinService service(String time) {
        return new ExpertCheckinService(requests,slots,experts,users,jdbc,monitoring,Clock.fixed(OffsetDateTime.parse(time).toInstant(),ZoneOffset.UTC));
    }
    @Test void boundariesUseServerReceivedTimeAndLateCompletionCannotHideLateness() {
        for(String[] sample:new String[][]{{"07:55:00","true"},{"08:00:00","true"},{"08:03:00","true"},{"08:03:00.000001","false"},{"08:03:01","false"},{"08:15:00","false"}}) {
            var result=service("2026-10-15T"+sample[0]+"+07:00").checkIn("expert","booking");
            assertEquals(Boolean.parseBoolean(sample[1]),result.onTime());assertEquals("SERVER_ENTRY_ACTION",result.evidence());
            assertEquals(OffsetDateTime.parse("2026-10-15T"+sample[0]+"+07:00").toInstant(),result.checkedInAt().toInstant());
        }
    }
    @Test void rejectsTooEarlyEndedCancelledAndWrongActor() {
        assertThrows(BusinessException.class,()->service("2026-10-15T07:54:59+07:00").checkIn("expert","booking"));
        assertThrows(BusinessException.class,()->service("2026-10-15T08:35:00+07:00").checkIn("expert","booking"));
        assertThrows(BusinessException.class,()->service("2026-10-15T08:00:00+07:00").checkIn("patient","booking"));
        request.setStatus(ConsultationStatus.CANCELLED);
        assertThrows(BusinessException.class,()->service("2026-10-15T08:00:00+07:00").checkIn("expert","booking"));
        verify(jdbc,never()).update(anyString(),any(SqlParameterSource.class));verifyNoInteractions(monitoring);
    }
    @Test void replayReturnsOriginalFirstCheckinEvenAfterCompletion() {
        when(jdbc.query(anyString(),any(SqlParameterSource.class),org.mockito.ArgumentMatchers.<RowMapper<OffsetDateTime>>any()))
                .thenReturn(List.of(start.plusMinutes(15)));
        request.setStatus(ConsultationStatus.COMPLETED);
        var result=service("2026-10-15T09:00:00+07:00").checkIn("expert","booking");
        assertEquals(start.plusMinutes(15),result.checkedInAt());assertFalse(result.onTime());
        verify(jdbc,never()).update(anyString(),any(SqlParameterSource.class));verifyNoInteractions(monitoring);
    }
    @Test void patientJoinDoesNotProduceExpertCheckin() {
        service("2026-10-15T08:00:00+07:00").checkInIfExpert("patient","booking");verifyNoInteractions(jdbc,monitoring);
    }
}

package vn.nutrimom.consultation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.consultation.domain.AssignmentType;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.SlotStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.RequestDtos.AcceptConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/**
 * Unit test cho các guard nghiệp vụ mới của {@link ConsultationRequestService}:
 * <ul>
 *   <li>DIRECT: không được tự đặt lịch với chính mình.</li>
 *   <li>DIRECT: chuyên khoa đã chọn phải khớp chuyên gia được chỉ định.</li>
 *   <li>accept: chuyên gia không được tiếp nhận yêu cầu do chính mình tạo.</li>
 * </ul>
 * Các guard đều ném {@link BusinessException} với {@link ErrorCode#FORBIDDEN} trước khi chạm slot,
 * nên slot repository không bị gọi.
 */
@ExtendWith(MockitoExtension.class)
class ConsultationRequestServiceTest {

    @Mock private ConsultationRequestRepository requests;
    @Mock private AvailabilitySlotRepository slots;
    @Mock private ExpertProfileRepository experts;
    @Mock private ConsultationReviewRepository reviews;
    @Mock private UserRepository users;

    private ConsultationRequestService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new ConsultationRequestService(
                requests, slots, experts, reviews, users, new AccessGuard(), clock);
    }

    private static ExpertProfileEntity expert(String userId, Specialty specialty) {
        ExpertProfileEntity expert = new ExpertProfileEntity();
        expert.setUserId(userId);
        expert.setSpecialty(specialty);
        return expert;
    }

    @Test
    void directBookingRejectsSelfAssignment() {
        String userId = "user-1";
        when(experts.findByUserIdAndStatus(userId, ExpertStatus.ACTIVE))
                .thenReturn(Optional.of(expert(userId, Specialty.PSYCHOLOGY)));

        CreateConsultationRequest request = new CreateConsultationRequest(
                AssignmentType.DIRECT, userId, "slot-1", null, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.FORBIDDEN.code());
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(business.getMessage()).contains("chính mình");
                });

        verifyNoInteractions(slots);
    }

    @Test
    void directBookingRejectsSpecialtyMismatch() {
        String userId = "user-1";
        String expertUserId = "expert-9";
        when(experts.findByUserIdAndStatus(expertUserId, ExpertStatus.ACTIVE))
                .thenReturn(Optional.of(expert(expertUserId, Specialty.PSYCHOLOGY)));

        CreateConsultationRequest request = new CreateConsultationRequest(
                AssignmentType.DIRECT, expertUserId, "slot-1", Specialty.HEALTH, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.FORBIDDEN.code());
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(business.getMessage()).contains("Chuyên khoa");
                });

        verifyNoInteractions(slots);
    }

    @Test
    void directBookingRejectsSlotOfAnotherExpert() {
        String userId = "user-1";
        String expertUserId = "expert-9";
        when(experts.findByUserIdAndStatus(expertUserId, ExpertStatus.ACTIVE))
                .thenReturn(Optional.of(expert(expertUserId, Specialty.PSYCHOLOGY)));

        AvailabilitySlotEntity slot = new AvailabilitySlotEntity();
        slot.setExpertUserId("other-expert"); // slot thuộc chuyên gia khác
        slot.setStatus(SlotStatus.OPEN);
        when(slots.findByIdForUpdate("slot-x")).thenReturn(Optional.of(slot));

        CreateConsultationRequest request = new CreateConsultationRequest(
                AssignmentType.DIRECT, expertUserId, "slot-x", null, null);

        assertThatThrownBy(() -> service.create(userId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.SLOT_UNAVAILABLE.code());
                    assertThat(business.getMessage()).contains("không thuộc chuyên gia");
                });
    }

    @Test
    void acceptRejectsSelfCreatedRequest() {
        String expertUserId = "expert-9";
        String requestId = "req-1";
        when(experts.findById(expertUserId))
                .thenReturn(Optional.of(expert(expertUserId, Specialty.PSYCHOLOGY)));

        ConsultationRequestEntity entity = new ConsultationRequestEntity();
        entity.setUserId(expertUserId); // người tạo trùng chính chuyên gia đang accept
        entity.setAssignmentType(AssignmentType.RANDOM);
        entity.setStatus(ConsultationStatus.PENDING_EXPERT);
        entity.setSpecialty(Specialty.PSYCHOLOGY);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(entity));

        AcceptConsultationRequest body = new AcceptConsultationRequest("slot-1");

        assertThatThrownBy(() -> service.accept(expertUserId, requestId, body))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.FORBIDDEN.code());
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(business.getMessage()).contains("chính mình");
                });

        verifyNoInteractions(slots);
    }
}

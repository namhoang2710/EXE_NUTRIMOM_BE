package vn.nutrimom.consultation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.RequestDtos.AcceptConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertDayOffRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;
import vn.nutrimom.notification.service.ActivityFeedService;
import vn.nutrimom.notification.service.NotificationService;

/**
 * Unit test cho các guard nghiệp vụ của {@link ConsultationRequestService}:
 * <ul>
 *   <li>DIRECT: không được tự đặt lịch với chính mình.</li>
 *   <li>DIRECT: chuyên khoa đã chọn phải khớp chuyên gia được chỉ định.</li>
 *   <li>DIRECT: giờ bắt đầu phải đúng một mốc trong lưới mặc định.</li>
 *   <li>DIRECT: chuyên gia nghỉ cả ngày thì không đặt được.</li>
 *   <li>accept: chuyên gia không được tiếp nhận yêu cầu do chính mình tạo.</li>
 * </ul>
 * Mọi guard đều chặn trước khi chạm bảng slot, nên slot repository không bị gọi.
 */
@ExtendWith(MockitoExtension.class)
class ConsultationRequestServiceTest {
    /** 2026-01-01T07:00 giờ VN, nên 2026-01-02 09:00 vừa là tương lai vừa trong horizon. */
    private static final LocalDate TOMORROW = LocalDate.of(2026, 1, 2);
    private static final LocalTime NINE = LocalTime.of(9, 0);

    @Mock private ConsultationRequestRepository requests;
    @Mock private AvailabilitySlotRepository slots;
    @Mock private ExpertDayOffRepository dayOffs;
    @Mock private ExpertProfileRepository experts;
    @Mock private ConsultationReviewRepository reviews;
    @Mock private UserRepository users;
    /** Mọi guard đều chặn trước khi tới bước báo tin, nên hai cộng tác viên này không bị gọi. */
    @Mock private NotificationService notifications;
    @Mock private ActivityFeedService activityFeed;

    private ConsultationRequestService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new ConsultationRequestService(
                requests, slots, dayOffs, experts, reviews, users, new AccessGuard(), clock,
                notifications, activityFeed);
    }

    private static ExpertProfileEntity expert(String userId, Specialty specialty) {
        ExpertProfileEntity expert = new ExpertProfileEntity();
        expert.setUserId(userId);
        expert.setSpecialty(specialty);
        return expert;
    }

    private static CreateConsultationRequest direct(String expertUserId, LocalTime startTime,
                                                    Specialty specialty) {
        return new CreateConsultationRequest(
                AssignmentType.DIRECT, expertUserId, TOMORROW, startTime, specialty, null);
    }

    @Test
    void directBookingRejectsSelfAssignment() {
        String userId = "user-1";
        when(experts.findByUserIdAndStatus(userId, ExpertStatus.ACTIVE))
                .thenReturn(Optional.of(expert(userId, Specialty.PSYCHOLOGY)));

        assertThatThrownBy(() -> service.create(userId, direct(userId, NINE, null)))
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
        String expertUserId = "expert-9";
        when(experts.findByUserIdAndStatus(expertUserId, ExpertStatus.ACTIVE))
                .thenReturn(Optional.of(expert(expertUserId, Specialty.PSYCHOLOGY)));

        assertThatThrownBy(() ->
                service.create("user-1", direct(expertUserId, NINE, Specialty.HEALTH)))
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
    void directBookingRejectsStartTimeOutsideGrid() {
        String expertUserId = "expert-9";
        when(experts.findByUserIdAndStatus(expertUserId, ExpertStatus.ACTIVE))
                .thenReturn(Optional.of(expert(expertUserId, Specialty.PSYCHOLOGY)));

        assertThatThrownBy(() ->
                service.create("user-1", direct(expertUserId, LocalTime.of(9, 15), null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.code());
                    assertThat(business.getMessage()).contains("Khung giờ không hợp lệ");
                });

        verifyNoInteractions(slots);
    }

    @Test
    void directBookingRejectsDayOff() {
        String expertUserId = "expert-9";
        when(experts.findByUserIdAndStatus(expertUserId, ExpertStatus.ACTIVE))
                .thenReturn(Optional.of(expert(expertUserId, Specialty.PSYCHOLOGY)));
        when(dayOffs.existsByExpertUserIdAndOffDate(expertUserId, TOMORROW)).thenReturn(true);

        assertThatThrownBy(() -> service.create("user-1", direct(expertUserId, NINE, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo(ErrorCode.SLOT_UNAVAILABLE.code());
                    assertThat(business.getMessage()).contains("nghỉ trong ngày này");
                });

        verifyNoInteractions(slots);
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

        AcceptConsultationRequest body = new AcceptConsultationRequest(TOMORROW, NINE);

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

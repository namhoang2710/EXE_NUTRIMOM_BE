package vn.nutrimom.consultation.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import vn.nutrimom.consultation.domain.SlotGrid;
import vn.nutrimom.consultation.domain.SlotStatus;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AcceptConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.ConsultationRequestResponse;
import vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.SlotInfo;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertDayOffRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** Tạo/theo dõi yêu cầu tư vấn (user) và tiếp nhận/hoàn thành (chuyên gia). */
@Service
public class ConsultationRequestService {
    /** Sắp xếp theo giờ hẹn tăng dần; yêu cầu chưa có slot xếp cuối. */
    private static final Comparator<ConsultationRequestResponse> BY_APPOINTMENT =
            Comparator.comparing(
                    (ConsultationRequestResponse r) -> r.slot() == null ? null
                            : r.slot().slotDate().atTime(r.slot().startTime()),
                    Comparator.nullsLast(Comparator.naturalOrder()));

    private final ConsultationRequestRepository requests;
    private final AvailabilitySlotRepository slots;
    private final ExpertDayOffRepository dayOffs;
    private final ExpertProfileRepository experts;
    private final ConsultationReviewRepository reviews;
    private final UserRepository users;
    private final AccessGuard guard;
    private final Clock clock;
    private final vn.nutrimom.consultation.video.ConsultationVideoService video;

    public ConsultationRequestService(ConsultationRequestRepository requests,
                                      AvailabilitySlotRepository slots,
                                      ExpertDayOffRepository dayOffs,
                                      ExpertProfileRepository experts,
                                      ConsultationReviewRepository reviews,
                                      UserRepository users,
                                      AccessGuard guard,
                                      Clock clock, vn.nutrimom.consultation.video.ConsultationVideoService video) {
        this.requests = requests;
        this.slots = slots;
        this.dayOffs = dayOffs;
        this.experts = experts;
        this.reviews = reviews;
        this.users = users;
        this.guard = guard;
        this.clock = clock;
        this.video = video;
    }

    // ----- User -----

    @Transactional
    public ConsultationRequestResponse create(String userId, CreateConsultationRequest request) {
        ConsultationRequestEntity entity = new ConsultationRequestEntity();
        entity.setUserId(userId);
        entity.setAssignmentType(request.assignmentType());
        entity.setNote(request.note());

        if (request.assignmentType() == AssignmentType.DIRECT) {
            createDirect(entity, request);
        } else {
            createRandom(entity, request);
        }
        requests.saveAndFlush(entity);
        return toResponse(entity, userId);
    }

    private void createDirect(ConsultationRequestEntity entity, CreateConsultationRequest request) {
        if (request.expertUserId() == null || request.expertUserId().isBlank()
                || request.slotDate() == null || request.startTime() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Đặt lịch trực tiếp cần chọn chuyên gia, ngày và khung giờ.");
        }
        ExpertProfileEntity expert = experts
                .findByUserIdAndStatus(request.expertUserId(), ExpertStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy chuyên gia."));
        if (entity.getUserId().equals(expert.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Bạn không thể tự đặt lịch tư vấn với chính mình.");
        }
        if (request.specialty() != null && request.specialty() != expert.getSpecialty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Chuyên khoa đã chọn không khớp với chuyên gia này.");
        }
        AvailabilitySlotEntity slot =
                reserveSlot(expert.getUserId(), request.slotDate(), request.startTime());

        entity.setExpertUserId(expert.getUserId());
        entity.setSpecialty(expert.getSpecialty());
        entity.setSlotId(slot.getId());
        entity.setStatus(ConsultationStatus.PENDING_CONSULTATION);
    }

    private void createRandom(ConsultationRequestEntity entity, CreateConsultationRequest request) {
        if (request.specialty() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chọn ngẫu nhiên cần chọn chuyên khoa.");
        }
        entity.setSpecialty(request.specialty());
        entity.setStatus(ConsultationStatus.PENDING_EXPERT);
    }

    @Transactional(readOnly = true)
    public PageResponse<ConsultationRequestResponse> listOwn(String userId, int page, int pageSize) {
        List<ConsultationRequestResponse> all = requests.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(entity -> toResponse(entity, userId))
                .toList();
        return PageResponse.of(all, page, pageSize);
    }

    @Transactional(readOnly = true)
    public ConsultationRequestResponse detail(String userId, String requestId) {
        ConsultationRequestEntity entity = guard.requireOwned(
                requests.findByIdAndUserId(requestId, userId), "Không tìm thấy yêu cầu tư vấn.");
        return toResponse(entity, userId);
    }

    @Transactional
    public ConsultationRequestResponse cancel(String userId, String requestId) {
        ConsultationRequestEntity entity = guard.requireOwned(
                requests.findByIdForUpdate(requestId).filter(r -> userId.equals(r.getUserId())), "Không tìm thấy yêu cầu tư vấn.");
        if (entity.getStatus() == ConsultationStatus.COMPLETED
                || entity.getStatus() == ConsultationStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_CONSULTATION_STATE,
                    "Yêu cầu này không thể hủy.");
        }
        String slotId = entity.getSlotId();
        entity.setSlotId(null);
        entity.setStatus(ConsultationStatus.CANCELLED);
        video.stop(requestId);
        requests.saveAndFlush(entity);
        releaseSlot(slotId);
        return toResponse(entity, userId);
    }

    // ----- Expert -----

    /**
     * Danh sách buổi tư vấn được giao cho chuyên gia, sắp theo giờ hẹn tăng dần.
     *
     * @param status lọc trạng thái; null → mặc định PENDING_CONSULTATION (buổi sắp diễn ra).
     * @param from   lọc theo ngày hẹn (giờ VN, bao gồm) nếu khác null.
     * @param to     lọc theo ngày hẹn (giờ VN, bao gồm) nếu khác null.
     * @param q      tìm theo tên user (không phân biệt hoa thường) nếu khác null.
     */
    @Transactional(readOnly = true)
    public PageResponse<ConsultationRequestResponse> listAssigned(
            String expertUserId, ConsultationStatus status, LocalDate from, LocalDate to,
            String q, int page, int pageSize) {
        requireExpert(expertUserId);
        ConsultationStatus effective = status == null ? ConsultationStatus.PENDING_CONSULTATION : status;
        String needle = normalize(q);
        List<ConsultationRequestResponse> all = requests
                .findByExpertUserIdAndStatusOrderByCreatedAtDesc(expertUserId, effective).stream()
                .map(entity -> toResponse(entity, null))
                .filter(response -> withinDate(response, from, to))
                .filter(response -> matchesName(response, needle))
                .sorted(BY_APPOINTMENT)
                .toList();
        return PageResponse.of(all, page, pageSize);
    }

    @Transactional(readOnly = true)
    public PageResponse<ConsultationRequestResponse> listPool(
            String expertUserId, String q, int page, int pageSize) {
        ExpertProfileEntity expert = requireExpert(expertUserId);
        String needle = normalize(q);
        List<ConsultationRequestResponse> all = requests
                .findBySpecialtyAndAssignmentTypeAndStatusOrderByCreatedAtAsc(
                        expert.getSpecialty(), AssignmentType.RANDOM, ConsultationStatus.PENDING_EXPERT)
                .stream()
                .map(entity -> toResponse(entity, null))
                .filter(response -> matchesName(response, needle))
                .toList();
        return PageResponse.of(all, page, pageSize);
    }

    private static String normalize(String q) {
        return q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesName(ConsultationRequestResponse response, String needle) {
        return needle == null || (response.userDisplayName() != null
                && response.userDisplayName().toLowerCase(Locale.ROOT).contains(needle));
    }

    private static boolean withinDate(ConsultationRequestResponse response, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return true;
        }
        if (response.slot() == null) {
            return false;
        }
        LocalDate date = response.slot().slotDate();
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    @Transactional
    public ConsultationRequestResponse accept(String expertUserId, String requestId,
                                              AcceptConsultationRequest body) {
        ExpertProfileEntity expert = requireExpert(expertUserId);
        ConsultationRequestEntity entity = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu tư vấn."));
        if (entity.getAssignmentType() != AssignmentType.RANDOM
                || entity.getStatus() != ConsultationStatus.PENDING_EXPERT) {
            throw new BusinessException(ErrorCode.REQUEST_ALREADY_CLAIMED,
                    "Yêu cầu đã được tiếp nhận hoặc không ở trạng thái chờ chuyên gia.");
        }
        if (entity.getSpecialty() != expert.getSpecialty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Yêu cầu này thuộc chuyên khoa khác.");
        }
        if (expertUserId.equals(entity.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Bạn không thể tiếp nhận yêu cầu do chính mình tạo.");
        }
        AvailabilitySlotEntity slot = reserveSlot(expertUserId, body.slotDate(), body.startTime());

        entity.setExpertUserId(expertUserId);
        entity.setSlotId(slot.getId());
        entity.setStatus(ConsultationStatus.PENDING_CONSULTATION);
        requests.saveAndFlush(entity);
        return toResponse(entity, null);
    }

    @Transactional
    public ConsultationRequestResponse complete(String expertUserId, String requestId) {
        requireExpert(expertUserId);
        ConsultationRequestEntity entity = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu tư vấn."));
        if (!expertUserId.equals(entity.getExpertUserId())) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu tư vấn.");
        }
        if (entity.getStatus() != ConsultationStatus.PENDING_CONSULTATION) {
            throw new BusinessException(ErrorCode.INVALID_CONSULTATION_STATE,
                    "Chỉ hoàn thành được yêu cầu đang chờ tư vấn.");
        }
        entity.setStatus(ConsultationStatus.COMPLETED);
        video.stop(requestId);
        entity.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        requests.saveAndFlush(entity);
        return toResponse(entity, null);
    }

    // ----- Helpers -----

    /**
     * Chiếm một ô trong lưới mặc định của chuyên gia. Ô là ảo cho tới lúc có người đặt, nên
     * việc chiếm chỗ chính là chèn một dòng {@code BOOKED}: unique
     * {@code (expert, ngày, giờ bắt đầu)} là chốt chặn thật sự cho hai lượt đặt song song,
     * vì khóa bi quan không giữ được dòng chưa tồn tại.
     */
    private AvailabilitySlotEntity reserveSlot(String expertUserId, LocalDate date,
                                               LocalTime startTime) {
        if (!SlotGrid.isValidStart(startTime)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Khung giờ không hợp lệ. Lịch chia theo từng " + SlotGrid.SLOT_MINUTES
                            + " phút, từ " + SlotGrid.OPENING + " đến " + SlotGrid.CLOSING + ".");
        }
        LocalDateTime now = ConsultationClock.nowVietnam(clock);
        if (!SlotGrid.isWithinHorizon(date, now.toLocalDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chỉ đặt lịch được trong vòng " + SlotGrid.HORIZON_DAYS + " ngày tới.");
        }
        if (!date.atTime(startTime).isAfter(now)) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    "Khung giờ đã trôi qua. Vui lòng chọn khung giờ khác.");
        }
        if (dayOffs.existsByExpertUserIdAndOffDate(expertUserId, date)) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    "Chuyên gia nghỉ trong ngày này. Vui lòng chọn ngày khác.");
        }
        slots.findForUpdate(expertUserId, date, startTime).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    existing.getStatus() == SlotStatus.BOOKED
                            ? "Khung giờ đã được đặt. Vui lòng chọn khung khác."
                            : "Chuyên gia không nhận lịch vào khung giờ này.");
        });

        AvailabilitySlotEntity slot = new AvailabilitySlotEntity();
        slot.setExpertUserId(expertUserId);
        slot.setSlotDate(date);
        slot.setStartTime(startTime);
        slot.setEndTime(SlotGrid.endOf(startTime));
        slot.setStatus(SlotStatus.BOOKED);
        try {
            return slots.saveAndFlush(slot);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE,
                    "Khung giờ vừa được đặt. Vui lòng chọn khung khác.");
        }
    }

    /**
     * Trả ô về trạng thái trống bằng cách xóa dòng đã chiếm. Gọi sau khi yêu cầu đã bỏ trỏ
     * tới slot, nếu không khóa ngoại {@code consultation_requests.slot_id} sẽ chặn.
     */
    private void releaseSlot(String slotId) {
        if (slotId == null) {
            return;
        }
        slots.findById(slotId).ifPresent(slot -> {
            slots.delete(slot);
            slots.flush();
        });
    }

    private ExpertProfileEntity requireExpert(String expertUserId) {
        return experts.findById(expertUserId).orElseThrow(() ->
                new BusinessException(ErrorCode.FORBIDDEN, "Tài khoản của bạn chưa phải hồ sơ chuyên gia."));
    }

    /**
     * @param viewerUserId user đang xem để tính cờ canReview; null khi người xem là chuyên gia.
     */
    private ConsultationRequestResponse toResponse(ConsultationRequestEntity entity, String viewerUserId) {
        String expertName = entity.getExpertUserId() == null ? null
                : experts.findById(entity.getExpertUserId())
                        .map(ExpertProfileEntity::getFullName).orElse(null);
        SlotInfo slot = entity.getSlotId() == null ? null
                : slots.findById(entity.getSlotId())
                        .map(s -> new SlotInfo(s.getId(), s.getSlotDate(), s.getStartTime(), s.getEndTime()))
                        .orElse(null);
        String userName = users.findById(entity.getUserId())
                .map(user -> user.getDisplayName()).orElse(null);
        boolean reviewed = reviews.existsByRequestId(entity.getId());
        boolean canReview = viewerUserId != null
                && viewerUserId.equals(entity.getUserId())
                && entity.getStatus() == ConsultationStatus.COMPLETED
                && !reviewed;
        return new ConsultationRequestResponse(entity.getId(), entity.getUserId(), userName,
                entity.getExpertUserId(), expertName, entity.getSpecialty(), entity.getAssignmentType(),
                entity.getStatus(), slot, entity.getNote(), reviewed, canReview,
                entity.getCompletedAt(), entity.getVersion(), entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}

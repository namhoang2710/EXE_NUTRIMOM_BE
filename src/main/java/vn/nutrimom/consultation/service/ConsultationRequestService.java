package vn.nutrimom.consultation.service;

import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_APPOINTMENT_ACCEPTED;
import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_APPOINTMENT_CANCELLED;
import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_APPOINTMENT_COMPLETED;
import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_APPOINTMENT_CREATED;
import static vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_EXPERT_WAITING;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.config.FrontendProperties;
import vn.nutrimom.notification.service.AdminNotificationEvents;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.consultation.domain.AssignmentType;
import vn.nutrimom.consultation.domain.ConsultationTiming;
import vn.nutrimom.consultation.domain.AvailabilitySlotEntity;
import vn.nutrimom.consultation.domain.ConsultationRequestEntity;
import vn.nutrimom.consultation.domain.ConsultationStatus;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.domain.SlotGrid;
import vn.nutrimom.consultation.domain.SlotStatus;
import vn.nutrimom.consultation.domain.Specialty;
import vn.nutrimom.consultation.dto.PageResponse;
import vn.nutrimom.consultation.dto.RequestDtos.AcceptConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.ConsultationRequestResponse;
import vn.nutrimom.consultation.dto.RequestDtos.ExpertConsultationPage;
import vn.nutrimom.consultation.dto.RequestDtos.CreateConsultationRequest;
import vn.nutrimom.consultation.dto.RequestDtos.SlotInfo;
import vn.nutrimom.consultation.repository.AvailabilitySlotRepository;
import vn.nutrimom.consultation.repository.ConsultationRequestRepository;
import vn.nutrimom.consultation.repository.ConsultationReviewRepository;
import vn.nutrimom.consultation.repository.ExpertDayOffRepository;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;
import vn.nutrimom.consultation.video.ConsultationVideoService;
import vn.nutrimom.notification.domain.ActivityType;
import vn.nutrimom.notification.domain.ActivityVisibility;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.ActivityFeedService;
import vn.nutrimom.notification.service.NotificationService;

/** Tạo/theo dõi yêu cầu tư vấn (user) và tiếp nhận/hoàn thành (chuyên gia). */
@Service
public class ConsultationRequestService {
    private static final Logger log = LoggerFactory.getLogger(ConsultationRequestService.class);

    /** Nguồn của thông báo, để app tra ngược về yêu cầu tư vấn. */
    private static final String CONSULTATION_SOURCE = "CONSULTATION";
    /** Deep link vào console chuyên gia; khác màn hình user nên không dùng chung link. */
    private static final String EXPERT_INBOX_LINK = "nutrimom://expert/consultations";
    private static final DateTimeFormatter APPOINTMENT_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ConsultationRequestRepository requests;
    private final AvailabilitySlotRepository slots;
    private final ExpertDayOffRepository dayOffs;
    private final ExpertProfileRepository experts;
    private final ConsultationReviewRepository reviews;
    private final UserRepository users;
    private final AccessGuard guard;
    private final Clock clock;
    private final NotificationService notifications;
    private final ActivityFeedService activityFeed;
    private final ConsultationVideoService video;
    private final AdminNotificationEvents adminEvents;
    private final EmailService emailService;
    private final FrontendProperties frontendProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public ConsultationRequestService(ConsultationRequestRepository requests,
                                      AvailabilitySlotRepository slots,
                                      ExpertDayOffRepository dayOffs,
                                      ExpertProfileRepository experts,
                                      ConsultationReviewRepository reviews,
                                      UserRepository users,
                                      AccessGuard guard,
                                      Clock clock,
                                      NotificationService notifications,
                                      ActivityFeedService activityFeed,
                                      ConsultationVideoService video,
                                      AdminNotificationEvents adminEvents,
                                      EmailService emailService,
                                      FrontendProperties frontendProperties) {
        this.adminEvents = adminEvents;
        this.requests = requests;
        this.slots = slots;
        this.dayOffs = dayOffs;
        this.experts = experts;
        this.reviews = reviews;
        this.users = users;
        this.guard = guard;
        this.clock = clock;
        this.notifications = notifications;
        this.activityFeed = activityFeed;
        this.video = video;
        this.emailService = emailService;
        this.frontendProperties = frontendProperties;
    }

    // ----- User -----

    @Transactional
    public ConsultationRequestResponse create(String userId, CreateConsultationRequest request) {
        ConsultationRequestEntity entity = new ConsultationRequestEntity();
        entity.setUserId(userId);
        entity.setAssignmentType(request.assignmentType());
        entity.setNote(request.note());

        AvailabilitySlotEntity bookedSlot = null;
        if (request.assignmentType() == AssignmentType.DIRECT) {
            bookedSlot = createDirect(entity, request);
        } else {
            createRandom(entity, request);
        }
        requests.saveAndFlush(entity);

        adminEvents.record(bookedSlot != null ? ADMIN_APPOINTMENT_CREATED : ADMIN_EXPERT_WAITING, entity.getId());
        if (bookedSlot != null) {
            // DIRECT: user đã chọn sẵn chuyên gia và khung giờ, nên người cần biết là chuyên gia.
            notifyExpert(entity.getExpertUserId(), entity, "Bạn có lịch tư vấn mới",
                    "Một người dùng vừa đặt lịch tư vấn với bạn lúc "
                            + appointmentLabel(bookedSlot) + ".");
            sendBookingEmails(entity, bookedSlot);
        } else {
            broadcastPendingRequest(entity);
            notifyUser(entity,"Yêu cầu tư vấn đang chờ tiếp nhận",
                    ConsultationTiming.deadlineMessage(entity.getAcceptanceDeadlineAt(),OffsetDateTime.now(clock)));
            users.findIdsByRoleAndStatus(vn.nutrimom.auth.domain.UserRole.ADMIN, vn.nutrimom.auth.domain.UserStatus.ACTIVE)
                    .stream().sorted().forEach(adminId -> notifications.publishAdminOnce(adminId,
                        NotificationType.CONSULTATION,"Có yêu cầu đang chờ chuyên gia",
                        "Một yêu cầu tư vấn cần chuyên gia tiếp nhận.",
                        "nutrimom://admin/appointments/" + entity.getId(),"ADMIN_EXPERT_WAITING",entity.getId()));
        }
        return toResponse(entity, userId);
    }

    /**
     * Yêu cầu RANDOM chưa có chủ nên phát cho mọi chuyên gia ACTIVE cùng chuyên khoa, ai rảnh thì
     * nhận trước (mô hình broadcast + claim).
     *
     * <p>Bỏ qua chính người đặt nếu họ cũng là chuyên gia — {@link #accept} vốn đã cấm tự nhận
     * yêu cầu của mình, báo cho họ chỉ tạo thông báo không bấm được.</p>
     *
     * <p>Không nhắc chuyên khoa hay nội dung ghi chú trong body: thông báo này chỉ tới đúng các
     * chuyên gia thuộc chuyên khoa đó nên họ đã biết, còn ghi chú là thông tin sức khoẻ của user.</p>
     */
    private void broadcastPendingRequest(ConsultationRequestEntity entity) {
        experts.eligibleRecipients(entity.getSpecialty())
                .stream()
                .map(ExpertProfileEntity::getUserId)
                .filter(expertUserId -> !expertUserId.equals(entity.getUserId()))
                .forEach(expertUserId -> notifications.publish(expertUserId,
                        NotificationType.CONSULTATION,
                        "Có yêu cầu tư vấn mới đang chờ",
                        "Một yêu cầu tư vấn thuộc chuyên khoa của bạn đang chờ được tiếp nhận. Hạn nhận: "
                                +entity.getAcceptanceDeadlineAt().atZoneSameInstant(ConsultationTiming.ZONE)
                                .format(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"))+".",
                        EXPERT_INBOX_LINK, CONSULTATION_SOURCE, entity.getId()));
    }

    /** Trả về khung giờ vừa giữ, để người gọi dựng được thông báo có mốc hẹn cụ thể. */
    private AvailabilitySlotEntity createDirect(ConsultationRequestEntity entity,
                                                CreateConsultationRequest request) {
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
        entity.captureScheduledSlot(slot);
        entity.setStatus(ConsultationStatus.PENDING_CONSULTATION);
        return slot;
    }

    private void createRandom(ConsultationRequestEntity entity, CreateConsultationRequest request) {
        if (request.specialty() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chọn ngẫu nhiên cần chọn chuyên khoa.");
        }
        entity.setSpecialty(request.specialty());
        entity.setStatus(ConsultationStatus.PENDING_EXPERT);
        var today=ConsultationClock.nowVietnam(clock).toLocalDate();
        if(request.preferredDate()!=null && !SlotGrid.isWithinHorizon(request.preferredDate(),today))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Ngày mong muốn phải trong 30 ngày tới.");
        entity.setPreferredDate(request.preferredDate());
        entity.openAcceptanceV2(OffsetDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public PageResponse<ConsultationRequestResponse> listOwn(String userId, int page, int pageSize) {
        var result=requests.findByUserId(userId,org.springframework.data.domain.PageRequest.of(page-1,pageSize,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,"createdAt","id")));
        return pageResponses(result,userId,page,pageSize);
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
        String assignedExpert = entity.getExpertUserId();
        entity.setSlotId(null);
        entity.setStatus(ConsultationStatus.CANCELLED);
        video.stop(requestId);
        requests.saveAndFlush(entity);
        releaseSlot(slotId);
        adminEvents.record(ADMIN_APPOINTMENT_CANCELLED, entity.getId());
        // Chỉ báo khi đã có chuyên gia nhận. Yêu cầu RANDOM còn PENDING_EXPERT thì chưa chuyên gia
        // nào nhận nó, nên không có ai để báo — các chuyên gia khác chỉ thấy nó biến khỏi hàng chờ.
        if (assignedExpert != null) {
            notifyExpert(assignedExpert, entity, "Một buổi tư vấn đã bị huỷ",
                    "Người dùng đã huỷ yêu cầu tư vấn. Khung giờ tương ứng đã được mở lại.");
            recordActivity(entity, userId,
                    ActivityType.CONSULTATION_CANCELLED, "Yêu cầu tư vấn đã được huỷ");
        }
        return toResponse(entity, userId);
    }

    /** Thông báo cho người đặt; deep link vào màn hình yêu cầu tư vấn phía user. */
    private void notifyUser(ConsultationRequestEntity entity, String title, String body) {
        notifications.publish(entity.getUserId(), NotificationType.CONSULTATION, title, body,
                "nutrimom://consultations/" + entity.getId(), CONSULTATION_SOURCE, entity.getId());
    }

    /** Thông báo cho chuyên gia; deep link vào console của chuyên gia, không phải màn hình user. */
    private void notifyExpert(String expertUserId, ConsultationRequestEntity entity,
                              String title, String body) {
        notifications.publish(expertUserId, NotificationType.CONSULTATION, title, body,
                EXPERT_INBOX_LINK + "/" + entity.getId(), CONSULTATION_SOURCE, entity.getId());
    }

    /**
     * Ghi một dòng activity cho yêu cầu tư vấn.
     *
     * <p>Luôn {@link ActivityVisibility#OWNER_ONLY}: việc mẹ bầu đi tư vấn chuyên khoa nào là thông
     * tin y tế, không đẩy sang feed chung của nhóm gia đình. {@code title} vì thế cũng chỉ mô tả
     * trạng thái, không kèm chuyên khoa hay nội dung câu hỏi.</p>
     */
    private void recordActivity(ConsultationRequestEntity entity, String actorUserId,
                                ActivityType activityType, String title) {
        activityFeed.record(entity.getUserId(), actorUserId, null,
                activityType, title, ActivityVisibility.OWNER_ONLY);
    }

    /** Mốc hẹn dạng người đọc được, vd "09:00 ngày 02/10/2026". */
    private static String appointmentLabel(AvailabilitySlotEntity slot) {
        return slot.getStartTime() + " ngày " + slot.getSlotDate().format(APPOINTMENT_DATE);
    }

    /**
     * Gửi email thông báo chi tiết cho cả bệnh nhân và chuyên gia sau khi transaction commit.
     */
    private void sendBookingEmails(ConsultationRequestEntity entity,
                                   AvailabilitySlotEntity slot) {
        if (slot == null || entity == null || emailService == null) {
            return;
        }

        // 1. Thông tin bệnh nhân
        UserEntity patient = users.findById(entity.getUserId()).orElse(null);
        String patientName = (patient != null && patient.getDisplayName() != null && !patient.getDisplayName().isBlank())
                ? patient.getDisplayName() : "Khách hàng";
        String patientEmail = (patient != null) ? patient.getEmail() : null;

        // 2. Thông tin chuyên gia
        ExpertProfileEntity expert = (entity.getExpertUserId() != null)
                ? experts.findById(entity.getExpertUserId()).orElse(null) : null;
        String doctorName = (expert != null && expert.getFullName() != null && !expert.getFullName().isBlank())
                ? expert.getFullName() : "Chuyên gia NutriMom";
        UserEntity doctorUser = (entity.getExpertUserId() != null)
                ? users.findById(entity.getExpertUserId()).orElse(null) : null;
        String doctorEmail = (doctorUser != null) ? doctorUser.getEmail() : null;

        // 3. Chuyên khoa
        Specialty specialty = (entity.getSpecialty() != null)
                ? entity.getSpecialty()
                : (expert != null ? expert.getSpecialty() : Specialty.HEALTH);
        String specialtyLabel = switch (specialty) {
            case PSYCHOLOGY -> "Tâm lý";
            case OBSTETRICS -> "Sản khoa";
            case HEALTH -> "Sức khỏe & Dinh dưỡng";
        };

        // 4. Mốc thời gian chi tiết
        String timeLabel = slot.getStartTime() + " - " + slot.getEndTime()
                + " ngày " + slot.getSlotDate().format(APPOINTMENT_DATE);

        String note = entity.getNote();

        String baseUrl = (frontendProperties != null && frontendProperties.getBaseUrl() != null && !frontendProperties.getBaseUrl().isBlank())
                ? frontendProperties.getBaseUrl() : "http://localhost:5173";
        String patientConsultationLink = baseUrl + "/app/consultations/history?request=" + entity.getId();
        String doctorConsoleLink = baseUrl + "/expert?section=requests&request_focus=" + entity.getId();

        Runnable emailTask = () -> {
            if (patientEmail != null && !patientEmail.isBlank()) {
                try {
                    emailService.sendConsultationBookingConfirmationToPatient(
                            patientEmail, patientName, doctorName, specialtyLabel, timeLabel, note, patientConsultationLink);
                } catch (Exception ex) {
                    log.warn("Không gửi được email xác nhận đặt lịch cho bệnh nhân {}: {}", patientEmail, ex.getMessage());
                }
            }
            if (doctorEmail != null && !doctorEmail.isBlank()) {
                try {
                    emailService.sendConsultationBookingNoticeToDoctor(
                            doctorEmail, doctorName, patientName, specialtyLabel, timeLabel, note, doctorConsoleLink);
                } catch (Exception ex) {
                    log.warn("Không gửi được email thông báo lịch hẹn cho bác sĩ {}: {}", doctorEmail, ex.getMessage());
                }
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    CompletableFuture.runAsync(emailTask);
                }
            });
        } else {
            CompletableFuture.runAsync(emailTask);
        }
    }

    // ----- Expert -----

    /**
     * Danh sách buổi tư vấn được giao cho chuyên gia, sắp theo giờ hẹn mới nhất trước.
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
        return pageResponses(assignedPage(expertUserId,status,from,to,q,page,pageSize),null,page,pageSize);
    }
    private org.springframework.data.domain.Page<ConsultationRequestEntity> assignedPage(String expertUserId,
            ConsultationStatus status,LocalDate from,LocalDate to,String q,int page,int pageSize) {
        requireExpert(expertUserId);
        ConsultationStatus effective = status == null ? ConsultationStatus.PENDING_CONSULTATION : status;
        if(from!=null && to!=null && to.isBefore(from)) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Khoảng ngày không hợp lệ.");
        return requests.assignedPage(expertUserId,effective,from,to,searchPattern(q),
                org.springframework.data.domain.PageRequest.of(page-1,pageSize));
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ExpertConsultationPage assignedSchedule(String expertUserId,ConsultationStatus status,LocalDate from,LocalDate to,
            String q,int page,int pageSize,boolean includeNext) {
        var result=assignedPage(expertUserId,status,from,to,q,page,pageSize);
        var now=OffsetDateTime.now(clock);var local=now.atZoneSameInstant(ConsultationTiming.ZONE);
        var next=includeNext && (status==null || status==ConsultationStatus.PENDING_CONSULTATION)
            ?requests.nextAssigned(expertUserId,from,to,searchPattern(q),now,local.toLocalDate(),
                local.toLocalTime(),local.toLocalTime().isBefore(SlotGrid.OPENING),!local.toLocalTime().isBefore(SlotGrid.CLOSING),
                org.springframework.data.domain.PageRequest.of(0,1)).stream().findFirst().orElse(null):null;
        var entities=new java.util.ArrayList<>(result.getContent());
        if(next!=null && entities.stream().noneMatch(e->e.getId().equals(next.getId())))entities.add(next);
        var mapped=responses(entities,null);
        var nextResponse=next==null?null:mapped.stream().filter(r->r.id().equals(next.getId())).findFirst().orElse(null);
        return new ExpertConsultationPage(List.copyOf(mapped.subList(0,result.getNumberOfElements())),page,pageSize,
            result.getTotalElements(),result.getTotalPages(),nextResponse,now);
    }

    @Transactional(readOnly = true)
    public PageResponse<ConsultationRequestResponse> listPool(
            String expertUserId, String q, int page, int pageSize) {
        ExpertProfileEntity expert = requireExpert(expertUserId);
        var result=requests.poolPage(expert.getSpecialty(),expertUserId,searchPattern(q),
                org.springframework.data.domain.PageRequest.of(page-1,pageSize));
        return pageResponses(result,null,page,pageSize);
    }

    private static String searchPattern(String q) {
        if(q!=null && q.length()>100) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"Từ khóa tối đa 100 ký tự.");
        return "%"+(q==null?"":q.trim().toLowerCase(Locale.ROOT)).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
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
        if(entity.getPreferredDate()!=null && !entity.getPreferredDate().equals(body.slotDate()))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Cần xếp lịch đúng ngày mong muốn. Nếu ngày đã qua, người dùng cần hủy và tạo yêu cầu mới.");
        AvailabilitySlotEntity slot = reserveSlot(expertUserId, body.slotDate(), body.startTime());

        entity.setExpertUserId(expertUserId);
        entity.setSlotId(slot.getId());
        entity.captureScheduledSlot(slot);
        entity.recordAcceptance(OffsetDateTime.now(clock));
        entity.setStatus(ConsultationStatus.PENDING_CONSULTATION);
        requests.saveAndFlush(entity);
        adminEvents.record(ADMIN_APPOINTMENT_ACCEPTED, entity.getId());
        if(entity.getAcceptanceDeadlineAt()!=null && entity.getAcceptedAt().isAfter(entity.getAcceptanceDeadlineAt()))
            adminEvents.record(vn.nutrimom.notification.domain.AdminNotificationEvent.ADMIN_ACCEPTANCE_OVERDUE,entity.getId());
        // Khung giờ do chuyên gia chọn chứ không phải user, nên thông báo phải nói thẳng mốc hẹn —
        // bắt user mở app mới biết mình hẹn lúc nào là cách chắc chắn để họ lỡ buổi tư vấn.
        notifyUser(entity, "Chuyên gia đã tiếp nhận yêu cầu tư vấn",
                "Buổi tư vấn của bạn được xếp lúc " + appointmentLabel(slot)
                        + ". Vui lòng có mặt đúng giờ.");
        sendBookingEmails(entity, slot);
        recordActivity(entity, expertUserId,
                ActivityType.CONSULTATION_ACCEPTED, "Chuyên gia đã tiếp nhận yêu cầu tư vấn");
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
        entity.setCompletedAt(OffsetDateTime.now(clock));
        requests.saveAndFlush(entity);
        adminEvents.record(ADMIN_APPOINTMENT_COMPLETED, entity.getId());
        notifyUser(entity, "Buổi tư vấn đã hoàn tất",
                "Buổi tư vấn của bạn đã kết thúc. Hãy dành ít phút đánh giá chuyên gia nhé.");
        recordActivity(entity, expertUserId,
                ActivityType.CONSULTATION_COMPLETED, "Buổi tư vấn đã hoàn tất");
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

    private String acceptanceStatus(ConsultationRequestEntity entity) {
        if(entity.getAssignmentType()!=AssignmentType.RANDOM) return "NOT_APPLICABLE";
        if(entity.getStatus()==ConsultationStatus.CANCELLED) return "CANCELLED";
        var deadline=entity.getAcceptanceDeadlineAt();
        if(deadline==null) return "UNKNOWN";
        if(entity.getAcceptedAt()!=null) return entity.getAcceptedAt().isAfter(deadline)?"ACCEPTED_LATE":"ACCEPTED_ON_TIME";
        return OffsetDateTime.now(clock).isAfter(deadline)?"OVERDUE":"WAITING";
    }

    private ExpertProfileEntity requireExpert(String expertUserId) {
        return experts.findById(expertUserId).orElseThrow(() ->
                new BusinessException(ErrorCode.FORBIDDEN, "Tài khoản của bạn chưa phải hồ sơ chuyên gia."));
    }

    /**
     * @param viewerUserId user đang xem để tính cờ canReview; null khi người xem là chuyên gia.
     */
    private ConsultationRequestResponse toResponse(ConsultationRequestEntity entity, String viewerUserId) {
        return responses(List.of(entity),viewerUserId).get(0);
    }
    private PageResponse<ConsultationRequestResponse> pageResponses(org.springframework.data.domain.Page<ConsultationRequestEntity> result,
            String viewer,int page,int size) {
        return new PageResponse<>(responses(result.getContent(),viewer),page,size,result.getTotalElements(),result.getTotalPages());
    }
    private List<ConsultationRequestResponse> responses(List<ConsultationRequestEntity> entities,String viewerUserId) {
        if(entities.isEmpty()) return List.of();
        var names=new java.util.HashMap<String,String>();
        users.findDisplayNamesByIdIn(entities.stream().map(ConsultationRequestEntity::getUserId).distinct().toList()).forEach(u->names.put(u.getId(),u.getDisplayName()));
        var expertNames=new java.util.HashMap<String,String>();
        var expertIds=entities.stream().map(ConsultationRequestEntity::getExpertUserId).filter(java.util.Objects::nonNull).distinct().toList();
        if(!expertIds.isEmpty()) experts.findAllById(expertIds).forEach(e->expertNames.put(e.getUserId(),e.getFullName()));
        var slotInfos=new java.util.HashMap<String,SlotInfo>();
        var slotIds=entities.stream().map(ConsultationRequestEntity::getSlotId).filter(java.util.Objects::nonNull).distinct().toList();
        if(!slotIds.isEmpty()) slots.findAllById(slotIds).forEach(s->slotInfos.put(s.getId(),new SlotInfo(s.getId(),s.getSlotDate(),s.getStartTime(),s.getEndTime())));
        var reviewedIds=reviews.findByRequestIdIn(entities.stream().map(ConsultationRequestEntity::getId).toList()).stream()
                .map(vn.nutrimom.consultation.domain.ConsultationReviewEntity::getRequestId).collect(java.util.stream.Collectors.toSet());
        var serverTime=OffsetDateTime.now(clock);
        return entities.stream().map(entity->{
            boolean reviewed=reviewedIds.contains(entity.getId());
            boolean canReview=viewerUserId!=null && viewerUserId.equals(entity.getUserId()) && entity.getStatus()==ConsultationStatus.COMPLETED && !reviewed;
            return new ConsultationRequestResponse(entity.getId(),entity.getUserId(),names.get(entity.getUserId()),
                    entity.getExpertUserId(),expertNames.get(entity.getExpertUserId()),entity.getSpecialty(),entity.getAssignmentType(),
                    entity.getStatus(),slotInfos.get(entity.getSlotId()),entity.getNote(),reviewed,canReview,entity.getCompletedAt(),
                    entity.getVersion(),entity.getCreatedAt(),entity.getUpdatedAt(),entity.getPreferredDate(),entity.getAcceptanceOpenedAt(),
                    entity.getAcceptanceDeadlineAt(),entity.getAcceptedAt(),entity.getAcceptancePolicyVersion(),acceptanceStatus(entity),serverTime,
                    entity.getAcceptanceDeadlineAt()==null?null:ConsultationTiming.deadlineMessage(entity.getAcceptanceDeadlineAt(),serverTime));
        }).toList();
    }
}

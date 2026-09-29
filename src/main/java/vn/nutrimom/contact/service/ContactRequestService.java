package vn.nutrimom.contact.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.contact.domain.ContactRequestEntity;
import vn.nutrimom.contact.domain.ContactRequestStatus;
import vn.nutrimom.contact.dto.ContactDtos.ContactPage;
import vn.nutrimom.contact.dto.ContactDtos.ContactRequestResponse;
import vn.nutrimom.contact.dto.ContactDtos.CreateContactRequest;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.contact.repository.ContactRequestRepository;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.NotificationService;

/** User gửi, theo dõi và huỷ yêu cầu hỗ trợ của chính mình. */
@Service
public class ContactRequestService {
    /** Chống spam: số yêu cầu đang chờ tối đa mỗi user. */
    static final int MAX_PENDING_PER_USER = 3;
    private static final String NOT_FOUND_MESSAGE = "Không tìm thấy yêu cầu hỗ trợ.";

    private final ContactRequestRepository requests;
    private final AccessGuard guard;
    private final UserRepository users;
    private final NotificationService notifications;

    public ContactRequestService(ContactRequestRepository requests, AccessGuard guard,
                                 UserRepository users, NotificationService notifications) {
        this.requests = requests;
        this.guard = guard;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public ContactRequestResponse create(String userId, CreateContactRequest request) {
        if (requests.countByUserIdAndStatus(userId, ContactRequestStatus.PENDING) >= MAX_PENDING_PER_USER) {
            throw new BusinessException(ErrorCode.CONTACT_REQUEST_LIMIT_REACHED);
        }
        ContactRequestEntity entity = new ContactRequestEntity();
        entity.setUserId(userId);
        entity.setTopic(request.topic());
        entity.setMessage(request.message().trim());
        entity.setStatus(ContactRequestStatus.PENDING);
        requests.saveAndFlush(entity);
        notifyAdmins(entity);
        return toResponse(entity);
    }

    /**
     * Hộp thư hỗ trợ không thuộc về một admin cụ thể nên thông báo được phát cho mọi tài khoản
     * ADMIN đang hoạt động; ai xử lý trước thì bấm hoàn tất trước.
     *
     * <p>Body cố ý không nhắc lại nội dung thắc mắc: thông báo có thể hiện trên màn hình khoá của
     * máy admin, còn nội dung thì đã có sẵn trong hộp thư.</p>
     */
    private void notifyAdmins(ContactRequestEntity entity) {
        users.findIdsByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE)
                .forEach(adminUserId -> notifications.publish(adminUserId,
                        NotificationType.CONTACT,
                        "Có yêu cầu hỗ trợ mới",
                        "Một người dùng vừa gửi yêu cầu hỗ trợ. Mở hộp thư để xem và liên hệ lại.",
                        "nutrimom://admin/contact-requests/" + entity.getId(),
                        "CONTACT_REQUEST", entity.getId()));
    }

    @Transactional(readOnly = true)
    public ContactPage<ContactRequestResponse> listOwn(String userId, int page, int pageSize) {
        Page<ContactRequestEntity> result = requests.findByUserIdOrderByCreatedAtDescIdDesc(
                userId, PageRequest.of(page - 1, pageSize));
        return ContactPage.of(result, result.map(ContactRequestService::toResponse).getContent());
    }

    @Transactional(readOnly = true)
    public ContactRequestResponse detail(String userId, String id) {
        return toResponse(guard.requireOwned(requests.findByIdAndUserId(id, userId), NOT_FOUND_MESSAGE));
    }

    @Transactional
    public ContactRequestResponse cancel(String userId, String id) {
        guard.requireOwned(requests.findByIdAndUserId(id, userId), NOT_FOUND_MESSAGE);
        ContactRequestEntity entity = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE));
        if (entity.getStatus() != ContactRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_CONTACT_REQUEST_STATE,
                    "Chỉ huỷ được yêu cầu đang chờ xử lý.");
        }
        entity.setStatus(ContactRequestStatus.CANCELLED);
        entity.setCancelledAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(requests.saveAndFlush(entity));
    }

    static ContactRequestResponse toResponse(ContactRequestEntity entity) {
        return new ContactRequestResponse(entity.getId(), entity.getTopic(), entity.getMessage(),
                entity.getStatus(), entity.getCreatedAt(), entity.getCompletedAt(), entity.getCancelledAt());
    }
}

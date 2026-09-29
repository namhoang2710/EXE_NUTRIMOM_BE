package vn.nutrimom.contact.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.contact.domain.ContactRequestEntity;
import vn.nutrimom.contact.domain.ContactRequestStatus;
import vn.nutrimom.contact.domain.ContactTopic;
import vn.nutrimom.contact.dto.ContactDtos.AdminContactDetail;
import vn.nutrimom.contact.dto.ContactDtos.AdminContactSummary;
import vn.nutrimom.contact.dto.ContactDtos.ContactPage;
import vn.nutrimom.contact.dto.ContactDtos.ContactUserInfo;
import vn.nutrimom.contact.repository.ContactRequestRepository;
import vn.nutrimom.notification.domain.ActivityType;
import vn.nutrimom.notification.domain.ActivityVisibility;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.ActivityFeedService;
import vn.nutrimom.notification.service.NotificationService;

/** Admin duyệt hộp thư hỗ trợ: xem thông tin user để gọi điện, rồi đánh dấu hoàn tất. */
@Service
public class AdminContactService {
    private static final int PREVIEW_LENGTH = 120;
    private static final String NOT_FOUND_MESSAGE = "Không tìm thấy yêu cầu hỗ trợ.";

    private final ContactRequestRepository requests;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ActivityFeedService activityFeed;

    public AdminContactService(ContactRequestRepository requests, UserRepository users,
                               NotificationService notifications, ActivityFeedService activityFeed) {
        this.requests = requests;
        this.users = users;
        this.notifications = notifications;
        this.activityFeed = activityFeed;
    }

    /**
     * @param status lọc trạng thái; null → tất cả.
     * @param topic  lọc chủ đề; null → tất cả.
     * @param q      tìm theo tên hiển thị hoặc SĐT của user (không phân biệt hoa thường).
     */
    @Transactional(readOnly = true)
    public ContactPage<AdminContactSummary> list(ContactRequestStatus status, ContactTopic topic, String q,
                                                 int page, int pageSize) {
        String pattern = q == null || q.isBlank() ? null
                : "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        Page<ContactRequestEntity> result = requests.searchForAdmin(status, topic, pattern,
                PageRequest.of(page - 1, pageSize));
        List<String> ownerIds = result.getContent().stream()
                .map(ContactRequestEntity::getUserId).distinct().toList();
        Map<String, UserEntity> owners = ownerIds.isEmpty() ? Map.of()
                : users.findAllWithRolesByIdIn(ownerIds).stream()
                        .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        List<AdminContactSummary> items = result.getContent().stream()
                .map(entity -> toSummary(entity, owners.get(entity.getUserId())))
                .toList();
        return ContactPage.of(result, items);
    }

    @Transactional(readOnly = true)
    public AdminContactDetail detail(String id) {
        return toDetail(requests.findById(id).orElseThrow(AdminContactService::notFound));
    }

    @Transactional
    public AdminContactDetail complete(String adminUserId, String id) {
        ContactRequestEntity entity = requests.findByIdForUpdate(id).orElseThrow(AdminContactService::notFound);
        if (entity.getStatus() != ContactRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_CONTACT_REQUEST_STATE,
                    "Chỉ hoàn tất được yêu cầu đang chờ xử lý.");
        }
        entity.setStatus(ContactRequestStatus.COMPLETED);
        entity.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setCompletedBy(adminUserId);
        requests.saveAndFlush(entity);
        // Báo cho người gửi biết đã được xử lý (task 18). Không nhắc lại nội dung thắc mắc trong
        // body vì thông báo có thể hiện trên màn hình khoá.
        notifications.publish(entity.getUserId(), NotificationType.CONTACT,
                "Yêu cầu hỗ trợ đã được xử lý",
                "Đội hỗ trợ đã hoàn tất yêu cầu của bạn. Mở ứng dụng để xem lại.",
                "nutrimom://contact-requests/" + entity.getId(), "CONTACT_REQUEST", entity.getId());
        activityFeed.record(entity.getUserId(), adminUserId, null,
                ActivityType.CONTACT_COMPLETED, "Yêu cầu hỗ trợ đã được xử lý",
                ActivityVisibility.OWNER_ONLY);
        return toDetail(entity);
    }

    private AdminContactDetail toDetail(ContactRequestEntity entity) {
        ContactUserInfo user = users.findById(entity.getUserId())
                .map(owner -> new ContactUserInfo(owner.getId(), owner.getDisplayName(), owner.getPhone(),
                        owner.getEmail(), owner.getGender(), owner.getDateOfBirth()))
                .orElse(null);
        return new AdminContactDetail(entity.getId(), entity.getTopic(), entity.getMessage(), entity.getStatus(),
                entity.getCreatedAt(), entity.getCompletedAt(), entity.getCompletedBy(), entity.getCancelledAt(),
                user);
    }

    private static AdminContactSummary toSummary(ContactRequestEntity entity, UserEntity owner) {
        return new AdminContactSummary(entity.getId(), entity.getTopic(), preview(entity.getMessage()),
                entity.getStatus(), entity.getUserId(),
                owner == null ? null : owner.getDisplayName(),
                owner == null ? null : owner.getPhone(),
                entity.getCreatedAt(), entity.getCompletedAt());
    }

    private static String preview(String message) {
        return message.length() <= PREVIEW_LENGTH ? message : message.substring(0, PREVIEW_LENGTH) + "…";
    }

    /** Coi {@code %}, {@code _}, {@code [} trong từ khoá là ký tự thường (query dùng {@code ESCAPE '\'}). */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("[", "\\[");
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, NOT_FOUND_MESSAGE);
    }
}

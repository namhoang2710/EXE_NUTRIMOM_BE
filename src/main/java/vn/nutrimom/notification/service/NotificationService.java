package vn.nutrimom.notification.service;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.config.NotificationProperties;
import vn.nutrimom.notification.domain.NotificationEntity;
import vn.nutrimom.notification.domain.NotificationAudience;
import vn.nutrimom.notification.domain.NotificationCategory;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.domain.PushDeviceEntity;
import vn.nutrimom.notification.dto.NotificationDtos.NotificationResponse;
import vn.nutrimom.notification.repository.NotificationRepository;
import vn.nutrimom.notification.repository.PushDeviceRepository;
import vn.nutrimom.user.domain.UserPreferenceEntity;
import vn.nutrimom.user.repository.UserPreferenceRepository;

/**
 * Hộp thông báo in-app và việc đẩy push kèm theo (spec mục 18).
 *
 * <p>{@link #publish} là cửa vào duy nhất để sinh thông báo. Module khác gọi nó thay vì tự ghi bảng,
 * nhờ đó quy tắc về tuỳ chọn người dùng và quiet hours chỉ nằm ở một chỗ. Module calendar/reminders
 * (spec mục 10) sau này cũng chỉ cần gọi hàm này với {@link NotificationType#REMINDER}.</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String NOT_FOUND_MESSAGE = "Không tìm thấy thông báo.";

    private final NotificationRepository notifications;
    private final PushDeviceRepository devices;
    private final UserPreferenceRepository preferences;
    private final PushTokenCipher cipher;
    private final PushSender pushSender;
    private final NotificationProperties properties;
    private final AccessGuard guard;
    private final UserRepository users;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final vn.nutrimom.admin.realtime.AdminRealtimeRepository realtime;

    public NotificationService(NotificationRepository notifications,
                               PushDeviceRepository devices,
                               UserPreferenceRepository preferences,
                               PushTokenCipher cipher,
                               PushSender pushSender,
                               NotificationProperties properties,
                               AccessGuard guard, UserRepository users,
                               org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc,
                               vn.nutrimom.admin.realtime.AdminRealtimeRepository realtime) {
        this.notifications = notifications;
        this.devices = devices;
        this.preferences = preferences;
        this.cipher = cipher;
        this.pushSender = pushSender;
        this.properties = properties;
        this.guard = guard;
        this.users = users;
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    /**
     * Ghi một thông báo cho {@code userId} rồi đẩy push nếu người dùng còn cho phép.
     *
     * <p>Thông báo <em>luôn</em> được ghi kể cả khi người dùng tắt push hoặc đang trong quiet hours:
     * hộp in-app là bản ghi chính thức, push chỉ là kênh đánh động.</p>
     */
    @Transactional
    public NotificationResponse publish(String userId, NotificationType type, String title,
                                        String body, String deepLink,
                                        String sourceType, String sourceId) {
        NotificationEntity entity = new NotificationEntity();
        entity.setUserId(userId);
        entity.setType(type);
        entity.setTitle(title);
        entity.setBody(body);
        entity.setDeepLink(deepLink);
        entity.setSourceType(sourceType);
        entity.setSourceId(sourceId);
        notifications.saveAndFlush(entity);
        schedulePush(userId, title, body, deepLink);
        return toResponse(entity);
    }

    /** Durable recipient deduplication for scheduler retries; confirmation can correct a provisional notice. */
    @Transactional
    public void publishExpertTimingOnce(String userId,String requestId,String title,String body) {
        var at=OffsetDateTime.now(ZoneOffset.UTC);
        String key="EXPERT_ARRIVAL:"+userId+":"+requestId;
        var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id",java.util.UUID.randomUUID().toString()).addValue("user",userId)
                .addValue("source",requestId).addValue("title",title).addValue("body",body)
                .addValue("link","nutrimom://expert/consultations/"+requestId).addValue("key",key).addValue("at",at);
        int inserted=jdbc.update("""
                insert into app.notifications(id,user_id,type,audience,category,title,body,deep_link,source_type,source_id,
                  dedup_key,created_at,updated_at,version)
                values(:id,:user,'CONSULTATION','USER','APPOINTMENT',:title,:body,:link,'CONSULTATION',:source,:key,:at,:at,0)
                on conflict do nothing
                """,p);
        if(inserted==1) schedulePush(userId,title,body,"nutrimom://expert/consultations/"+requestId);
        else jdbc.update("""
                update app.notifications set title=:title,body=:body,updated_at=:at,version=version+1
                where dedup_key=:key and (title<>:title or body<>:body)
                """,p);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public CursorPage<NotificationResponse> list(String userId, String cursor, int limit,
                                                 boolean unreadOnly) {
        return list(userId, cursor, limit, unreadOnly, null, null);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public CursorPage<NotificationResponse> list(String userId, String cursor, int limit,
            boolean unreadOnly, NotificationAudience audience, NotificationCategory category) {
        if (limit < 1 || limit > 50) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        boolean allowAdmin = adminAccess(userId, audience);
        FeedCursor from = FeedCursor.decode(cursor);
        // Lấy dư một dòng để biết còn trang sau mà không phải đếm tổng.
        Pageable window = PageRequest.of(0, limit + 1);
        List<NotificationEntity> rows = notifications.findVisiblePage(userId, allowAdmin,
                audience, category, unreadOnly, from.at(), from.id(), window);

        boolean hasMore = rows.size() > limit;
        List<NotificationEntity> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? cursorOf(page.get(page.size() - 1)) : null;
        return new CursorPage<>(page.stream().map(NotificationService::toResponse).toList(),
                nextCursor, hasMore, audience==NotificationAudience.ADMIN ? realtime.notificationRevision(userId) : null);
    }

    /** Idempotent: đã đọc rồi thì giữ nguyên mốc cũ, không ghi đè bằng thời điểm gọi lại. */
    @Transactional
    public NotificationResponse markRead(String userId, String id) {
        NotificationEntity entity = guard.requireOwned(
                notifications.findByIdAndUserId(id, userId), NOT_FOUND_MESSAGE);
        if (entity.getAudience() == NotificationAudience.ADMIN) adminAccess(userId, NotificationAudience.ADMIN);
        if (entity.getReadAt() == null) {
            entity.setReadAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            notifications.saveAndFlush(entity);
        }
        return toResponse(entity);
    }

    /** Idempotent: chỉ chạm dòng chưa đọc, nên lần gọi thứ hai trả về 0. */
    @Transactional
    public int markAllRead(String userId) {
        return markAllRead(userId, null, null);
    }

    @Transactional
    public int markAllRead(String userId, NotificationAudience audience, NotificationCategory category) {
        return notifications.markVisibleRead(userId, adminAccess(userId, audience), audience, category,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** Dùng cho {@code unread_notification_count} của dashboard mẹ bầu (spec mục 05). */
    @Transactional(readOnly = true)
    public long unreadCount(String userId) {
        return unreadCount(userId, null, null);
    }

    @Transactional(readOnly = true)
    public long unreadCount(String userId, NotificationAudience audience, NotificationCategory category) {
        return notifications.countVisibleUnread(userId, adminAccess(userId, audience), audience, category);
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public vn.nutrimom.notification.dto.NotificationDtos.UnreadCountResponse unreadCountResponse(
            String userId,NotificationAudience audience,NotificationCategory category) {
        long count=unreadCount(userId,audience,category);
        return new vn.nutrimom.notification.dto.NotificationDtos.UnreadCountResponse(count,
                audience==NotificationAudience.ADMIN ? realtime.notificationRevision(userId) : null);
    }

    private boolean adminAccess(String userId, NotificationAudience audience) {
        boolean allowed = audience != NotificationAudience.USER && users.isActiveAdmin(userId);
        if (audience == NotificationAudience.ADMIN && !allowed) throw new BusinessException(ErrorCode.FORBIDDEN);
        return allowed;
    }

    // ----- Push -----
    /** Atomic upsert avoids duplicate push, transaction poisoning and recipient row-lock deadlocks. */
    @Transactional
    public NotificationResponse publishAdminOnce(String userId, NotificationType type, String title,
            String body, String deepLink, String sourceType, String sourceId) {
        if (type != NotificationType.CONTACT && type != NotificationType.CONSULTATION) {
            throw new IllegalArgumentException("Unsupported admin event type");
        }
        if (!users.isActiveAdmin(userId)) return null;
        String key = (type == NotificationType.CONTACT ? "ADMIN_CONTACT_NEW:" : "ADMIN_EXPERT_WAITING:")
                + userId + ":" + sourceId;
        var at = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var p = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", java.util.UUID.randomUUID().toString()).addValue("user", userId)
                .addValue("type", type.name()).addValue("title", title).addValue("body", body)
                .addValue("link", deepLink).addValue("sourceType", sourceType).addValue("sourceId", sourceId)
                .addValue("key", key).addValue("at", at)
                .addValue("category", type == NotificationType.CONTACT ? "SUPPORT" : "APPOINTMENT");
        int inserted = jdbc.update("""
                insert into app.notifications(id,user_id,type,title,body,deep_link,source_type,source_id,
                                              dedup_key,created_at,updated_at,version,audience,category)
                values (:id,:user,:type,:title,:body,:link,:sourceType,:sourceId,:key,:at,:at,0,'ADMIN',:category)
                on conflict do nothing
                """, p);
        var entity = notifications.findByDedupKey(key).orElseThrow();
        if (inserted == 1) schedulePush(userId,title,body,deepLink);
        return toResponse(entity);
    }


    /**
     * Dựng sẵn danh sách push trong transaction (còn đọc được DB) nhưng chỉ gửi sau khi commit.
     *
     * <p>Gửi sau commit vì hai lẽ: thông báo chưa chắc tồn tại nếu transaction rollback, và khi thay
     * {@link LoggingPushSender} bằng gateway thật thì một lời gọi mạng chậm sẽ giữ connection DB.</p>
     */
    private void schedulePush(String userId, String title, String body, String deepLink) {
        if (!properties.isPushEnabled() || !pushAllowedNow(userId)) {
            return;
        }
        List<PushMessage> messages = devices.findByUserIdAndActiveTrue(userId).stream()
                .map(device -> toPushMessage(device, title, body, deepLink))
                .flatMap(Optional::stream)
                .toList();
        if (messages.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deliver(messages);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(messages);
            }
        });
    }

    /**
     * Dựng bản push cho một thiết bị; thiết bị nào không giải mã được token thì bỏ qua đúng thiết bị
     * đó thay vì làm đổ cả lượt gửi.
     *
     * <p>Token không giải mã được thường là vì {@code NUTRIMOM_PUSH_TOKEN_KEY} đã xoay mà các dòng
     * cũ vẫn mã hoá bằng key trước. Nếu để ngoại lệ thoát ra, nó không chỉ mất push mà còn kéo đổ cả
     * thao tác nghiệp vụ đã gọi {@link #publish} — một thiết bị hỏng của một người sẽ chặn luôn
     * những người còn lại trong cùng lượt fan-out, và người dùng mất cả yêu cầu vừa gửi.</p>
     *
     * <p>Không tự vô hiệu hoá thiết bị ở đây: nếu key sai ở mức toàn cục thì mọi thiết bị đều hỏng,
     * tự tắt hết sẽ che mất lỗi cấu hình và bắt người dùng đăng ký lại. Ghi log rồi để người vận
     * hành quyết.</p>
     *
     * <p>Chỉ log id thiết bị — không log token, cũng không log message của ngoại lệ.</p>
     */
    private Optional<PushMessage> toPushMessage(PushDeviceEntity device, String title, String body,
                                                String deepLink) {
        try {
            return Optional.of(new PushMessage(cipher.decrypt(device.getPushTokenCipher()),
                    device.getPlatform(), title, body, deepLink));
        } catch (RuntimeException ex) {
            log.warn("Skipping device {}: push token could not be decrypted", device.getId());
            return Optional.empty();
        }
    }

    /** Một thiết bị lỗi không được chặn các thiết bị còn lại, và không được làm hỏng request. */
    private void deliver(List<PushMessage> messages) {
        for (PushMessage message : messages) {
            try {
                pushSender.send(message);
            } catch (RuntimeException ex) {
                log.warn("Push delivery failed platform={}", message.platform(), ex);
            }
        }
    }

    /** Tôn trọng tuỳ chọn đã có sẵn ở {@code app.user_preferences} (spec mục 03). */
    private boolean pushAllowedNow(String userId) {
        UserPreferenceEntity prefs = preferences.findById(userId).orElse(null);
        if (prefs == null) {
            return true;
        }
        if (!prefs.isNotificationEnabled() || !prefs.isPushEnabled()) {
            return false;
        }
        return !inQuietHours(prefs);
    }

    private boolean inQuietHours(UserPreferenceEntity prefs) {
        LocalTime from = prefs.getQuietHoursStart();
        LocalTime to = prefs.getQuietHoursEnd();
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        LocalTime now = LocalTime.now(zone(prefs.getTimezone()));
        return from.isBefore(to)
                ? !now.isBefore(from) && now.isBefore(to)
                // Khoảng vắt qua nửa đêm, vd 22:00 -> 06:30.
                : !now.isBefore(from) || now.isBefore(to);
    }

    private ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    private static String cursorOf(NotificationEntity entity) {
        return new FeedCursor(entity.getCreatedAt(), entity.getId()).encode();
    }

    static NotificationResponse toResponse(NotificationEntity entity) {
        return new NotificationResponse(entity.getId(), entity.getType(), entity.getTitle(),
                entity.getBody(), entity.getDeepLink(), entity.getSourceType(), entity.getSourceId(),
                entity.getReadAt(), entity.getCreatedAt(), entity.getAudience(), entity.getCategory());
    }
}

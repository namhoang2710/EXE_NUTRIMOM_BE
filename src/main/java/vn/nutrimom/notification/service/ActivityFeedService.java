package vn.nutrimom.notification.service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.api.CursorPage;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyGroupStatus;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.notification.domain.ActivityEventEntity;
import vn.nutrimom.notification.domain.ActivityType;
import vn.nutrimom.notification.domain.ActivityVisibility;
import vn.nutrimom.notification.dto.NotificationDtos.ActivityEventResponse;
import vn.nutrimom.notification.repository.ActivityEventRepository;

/**
 * Activity feed (spec mục 18 "GET /activity-feed").
 *
 * <p>Caller thấy hai nhóm: hoạt động của chính mình (mọi mức hiển thị) và hoạt động
 * {@link ActivityVisibility#FAMILY} của những thai kỳ mà caller là thành viên gia đình đang hoạt
 * động <em>và</em> có scope {@link FamilyScope#ACTIVITY_FEED}. Bỏ scope ở
 * {@code PATCH /family-members/{id}} là mất quyền xem ngay lần gọi kế tiếp, đúng yêu cầu "Family
 * scope thay đổi có hiệu lực ngay" của spec mục 22.</p>
 */
@Service
public class ActivityFeedService {

    private final ActivityEventRepository events;
    private final FamilyMemberRepository members;
    private final FamilyGroupRepository groups;

    public ActivityFeedService(ActivityEventRepository events,
                               FamilyMemberRepository members,
                               FamilyGroupRepository groups) {
        this.events = events;
        this.members = members;
        this.groups = groups;
    }

    /**
     * Ghi lại một hoạt động. Gọi từ các module nghiệp vụ, song song với
     * {@code NotificationService.publish}.
     *
     * <p>{@code title} phải là chuỗi cố định do phía gọi dựng sẵn — không nối nội dung hồ sơ y tế,
     * câu hỏi tư vấn hay tin nhắn vào, vì chuỗi này hiển thị cho cả nhóm gia đình khi
     * {@code visibility = FAMILY}.</p>
     */
    @Transactional
    public void record(String subjectUserId, String actorUserId, String pregnancyId,
                       ActivityType type, String title, ActivityVisibility visibility) {
        ActivityEventEntity event = new ActivityEventEntity();
        event.setSubjectUserId(subjectUserId);
        event.setActorUserId(actorUserId);
        event.setPregnancyId(pregnancyId);
        event.setType(type);
        event.setTitle(title);
        event.setVisibility(visibility);
        events.saveAndFlush(event);
    }

    @Transactional(readOnly = true)
    public CursorPage<ActivityEventResponse> feed(String userId, String cursor, int limit) {
        FeedCursor from = FeedCursor.decode(cursor);
        // Lấy dư một dòng để biết còn trang sau mà không phải đếm tổng.
        Pageable window = PageRequest.of(0, limit + 1);
        Set<String> sharedPregnancyIds = sharedPregnancyIds(userId);

        List<ActivityEventEntity> rows = sharedPregnancyIds.isEmpty()
                ? events.findOwnFeed(userId, from.at(), from.id(), window)
                : events.findSharedFeed(userId, sharedPregnancyIds, ActivityVisibility.FAMILY,
                        from.at(), from.id(), window);

        boolean hasMore = rows.size() > limit;
        List<ActivityEventEntity> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? new FeedCursor(page.get(page.size() - 1).getCreatedAt(),
                        page.get(page.size() - 1).getId()).encode()
                : null;
        return new CursorPage<>(page.stream().map(ActivityFeedService::toResponse).toList(),
                nextCursor, hasMore);
    }

    /** Block {@code activity_feed} của dashboard partner; chỉ hoạt động chia sẻ được cho gia đình. */
    @Transactional(readOnly = true)
    public List<ActivityEventResponse> forPregnancy(String pregnancyId, int limit) {
        return events.findByPregnancyIdAndVisibilityOrderByCreatedAtDescIdDesc(
                        pregnancyId, ActivityVisibility.FAMILY, PageRequest.of(0, limit))
                .stream()
                .map(ActivityFeedService::toResponse)
                .toList();
    }

    /** Thai kỳ mà caller được chia sẻ activity feed; rỗng nghĩa là chỉ xem được feed của chính mình. */
    private Set<String> sharedPregnancyIds(String userId) {
        return members.findByUserIdAndStatusOrderByCreatedAtDesc(userId, FamilyMemberStatus.ACTIVE)
                .stream()
                .filter(member -> member.getScopes().contains(FamilyScope.ACTIVITY_FEED))
                .map(member -> groups.findByIdAndStatus(
                                member.getFamilyGroupId(), FamilyGroupStatus.ACTIVE)
                        .map(FamilyGroupEntity::getPregnancyId)
                        .orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    static ActivityEventResponse toResponse(ActivityEventEntity event) {
        return new ActivityEventResponse(event.getId(), event.getType(), event.getTitle(),
                event.getActorUserId(), event.getPregnancyId(), event.getCreatedAt());
    }
}

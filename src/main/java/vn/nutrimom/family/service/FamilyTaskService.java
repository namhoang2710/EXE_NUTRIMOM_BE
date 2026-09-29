package vn.nutrimom.family.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.domain.FamilyTaskEntity;
import vn.nutrimom.family.domain.FamilyTaskPriority;
import vn.nutrimom.family.domain.FamilyTaskStatus;
import vn.nutrimom.family.dto.CreateFamilyTaskRequest;
import vn.nutrimom.family.dto.FamilyTaskResponse;
import vn.nutrimom.family.dto.UpdateFamilyTaskRequest;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.family.repository.FamilyTaskRepository;
import vn.nutrimom.notification.domain.ActivityType;
import vn.nutrimom.notification.domain.ActivityVisibility;
import vn.nutrimom.notification.domain.NotificationType;
import vn.nutrimom.notification.service.ActivityFeedService;
import vn.nutrimom.notification.service.NotificationService;

@Service
public class FamilyTaskService {
    private final FamilyTaskRepository tasks;
    private final FamilyGroupService groupService;
    private final FamilyMemberRepository members;
    private final AccessGuard guard;
    private final NotificationService notifications;
    private final ActivityFeedService activityFeed;

    public FamilyTaskService(FamilyTaskRepository tasks,
                             FamilyGroupService groupService,
                             FamilyMemberRepository members,
                             AccessGuard guard,
                             NotificationService notifications,
                             ActivityFeedService activityFeed) {
        this.tasks = tasks;
        this.groupService = groupService;
        this.members = members;
        this.guard = guard;
        this.notifications = notifications;
        this.activityFeed = activityFeed;
    }

    @Transactional(readOnly = true)
    public List<FamilyTaskResponse> list(
            String userId, String assigneeId, FamilyTaskStatus status) {
        FamilyGroupEntity group = authorize(userId).group();
        return tasks.findByFamilyGroupIdAndDeletedAtIsNullOrderByDueAtAsc(group.getId()).stream()
                .filter(task -> assigneeId == null || assigneeId.equals(task.getAssigneeId()))
                .filter(task -> status == null || status == task.getStatus())
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public FamilyTaskResponse create(String userId, CreateFamilyTaskRequest request) {
        FamilyGroupEntity group = authorize(userId).group();
        FamilyTaskEntity task = new FamilyTaskEntity();
        task.setFamilyGroupId(group.getId());
        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        task.setPriority(request.priority());
        task.setDueAt(request.dueAt());
        task.setAssigneeId(resolveAssignee(group, request.assigneeId()));
        task.setStatus(FamilyTaskStatus.TODO);
        tasks.saveAndFlush(task);
        announceAssignment(group, task, null, userId);
        return toResponse(task);
    }

    /**
     * Báo cho người được giao việc và ghi một dòng activity chung của nhóm (task 18).
     *
     * <p>Chỉ báo khi người được giao thực sự đổi, nên gọi được từ cả lúc tạo ({@code previousAssigneeId}
     * null) lẫn lúc giao lại; sửa tiêu đề hay hạn chót mà không đổi người thì không làm phiền ai.</p>
     *
     * <p>{@code assigneeId} là id của bản ghi family_member, không phải user id, nên phải tra ngược
     * để biết gửi thông báo cho tài khoản nào. Tự giao việc cho mình thì bỏ qua — không ai cần được
     * báo về thao tác vừa tự làm.</p>
     *
     * <p>Activity ở mức {@link ActivityVisibility#FAMILY}: việc nhà là thông tin chung của nhóm,
     * không phải dữ liệu y tế. Tiêu đề vẫn chỉ nói "được giao một việc mới", không kèm tiêu đề việc
     * do người dùng tự nhập.</p>
     */
    private void announceAssignment(FamilyGroupEntity group, FamilyTaskEntity task,
                                    String previousAssigneeId, String actorUserId) {
        if (task.getAssigneeId() == null || task.getAssigneeId().equals(previousAssigneeId)) {
            return;
        }
        String assigneeUserId = members.findById(task.getAssigneeId())
                .map(FamilyMemberEntity::getUserId)
                .orElse(null);
        if (assigneeUserId == null || assigneeUserId.equals(actorUserId)) {
            return;
        }
        notifications.publish(assigneeUserId, NotificationType.FAMILY,
                "Bạn được giao một việc mới",
                "Một thành viên trong nhóm gia đình vừa giao cho bạn một việc. Mở ứng dụng để xem.",
                "nutrimom://family/tasks/" + task.getId(), "FAMILY_TASK", task.getId());
        activityFeed.record(group.getOwnerUserId(), actorUserId, group.getPregnancyId(),
                ActivityType.FAMILY_TASK_ASSIGNED, "Một việc mới đã được giao trong nhóm gia đình",
                ActivityVisibility.FAMILY);
    }

    @Transactional
    public FamilyTaskResponse update(
            String userId, String taskId, UpdateFamilyTaskRequest request) {
        FamilyGroupEntity group = authorize(userId).group();
        FamilyTaskEntity task = guard.requireOwned(
                tasks.findByIdAndFamilyGroupIdAndDeletedAtIsNull(taskId, group.getId()),
                "Family task was not found.");
        if (request.version() == null || request.version() != task.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Family task was updated elsewhere. Reload and try again.");
        }
        // Ghi lại trước khi sửa để biết có thực sự đổi người phụ trách / trạng thái hay không.
        String previousAssigneeId = task.getAssigneeId();
        FamilyTaskStatus previousStatus = task.getStatus();
        if (request.title() != null) {
            task.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        if (request.dueAt() != null) {
            task.setDueAt(request.dueAt());
        }
        if (request.assigneeId() != null) {
            task.setAssigneeId(resolveAssignee(group, request.assigneeId()));
        }
        if (request.status() != null) {
            task.setStatus(request.status());
        }
        tasks.saveAndFlush(task);
        announceAssignment(group, task, previousAssigneeId, userId);
        announceCompletion(group, task, previousStatus, userId);
        return toResponse(task);
    }

    /**
     * Việc chuyển sang {@link FamilyTaskStatus#COMPLETED} thì báo cho chủ nhóm — mẹ bầu là người
     * cần biết việc nhà đã xong mà không phải tự vào kiểm tra.
     *
     * <p>Chỉ bắn ở lần đổi trạng thái đầu tiên: sửa tiếp một việc đã COMPLETED (đổi tiêu đề, hạn
     * chót) không được bắn lại. Chủ nhóm tự bấm hoàn thành thì cũng không tự báo mình, nhưng dòng
     * activity vẫn ghi để cả nhóm thấy.</p>
     */
    private void announceCompletion(FamilyGroupEntity group, FamilyTaskEntity task,
                                    FamilyTaskStatus previousStatus, String actorUserId) {
        if (task.getStatus() != FamilyTaskStatus.COMPLETED
                || previousStatus == FamilyTaskStatus.COMPLETED) {
            return;
        }
        if (!group.getOwnerUserId().equals(actorUserId)) {
            notifications.publish(group.getOwnerUserId(), NotificationType.FAMILY,
                    "Một việc trong nhóm đã hoàn thành",
                    "Một thành viên vừa đánh dấu hoàn thành một việc trong nhóm gia đình.",
                    "nutrimom://family/tasks/" + task.getId(), "FAMILY_TASK", task.getId());
        }
        activityFeed.record(group.getOwnerUserId(), actorUserId, group.getPregnancyId(),
                ActivityType.FAMILY_TASK_COMPLETED, "Một việc trong nhóm gia đình đã hoàn thành",
                ActivityVisibility.FAMILY);
    }

    @Transactional
    public void delete(String userId, String taskId) {
        FamilyGroupEntity group = authorize(userId).group();
        FamilyTaskEntity task = guard.requireOwned(
                tasks.findByIdAndFamilyGroupIdAndDeletedAtIsNull(taskId, group.getId()),
                "Family task was not found.");
        task.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        tasks.saveAndFlush(task);
    }

    /** 2 guard: (1) membership row-level → 404; (2) scope FAMILY_TASKS cho member → 403. */
    private FamilyGroupService.GroupAccess authorize(String userId) {
        FamilyGroupService.GroupAccess access = groupService.requireAccessibleGroup(userId);
        if (!access.owner()) {
            guard.requireScope(access.membership().getScopes().contains(FamilyScope.FAMILY_TASKS));
        }
        return access;
    }

    /** assignee_id là family_member id ACTIVE trong cùng group; blank → null (chưa giao). */
    private String resolveAssignee(FamilyGroupEntity group, String assigneeId) {
        if (assigneeId == null || assigneeId.isBlank()) {
            return null;
        }
        FamilyMemberEntity assignee = members.findById(assigneeId.trim()).orElse(null);
        if (assignee == null
                || !assignee.getFamilyGroupId().equals(group.getId())
                || assignee.getStatus() != FamilyMemberStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "assignee_id must be an active member of this family group.");
        }
        return assignee.getId();
    }

    private FamilyTaskResponse toResponse(FamilyTaskEntity task) {
        return new FamilyTaskResponse(
                task.getId(), task.getFamilyGroupId(), task.getTitle(), task.getDescription(),
                priorityName(task.getPriority()), task.getDueAt(), task.getAssigneeId(),
                task.getStatus().name(), task.getVersion(),
                task.getCreatedAt(), task.getUpdatedAt());
    }

    private String priorityName(FamilyTaskPriority priority) {
        return priority == null ? null : priority.name();
    }
}

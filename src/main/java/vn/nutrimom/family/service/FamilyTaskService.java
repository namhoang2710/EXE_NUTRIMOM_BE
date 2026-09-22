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

@Service
public class FamilyTaskService {
    private final FamilyTaskRepository tasks;
    private final FamilyGroupService groupService;
    private final FamilyMemberRepository members;
    private final AccessGuard guard;

    public FamilyTaskService(FamilyTaskRepository tasks,
                             FamilyGroupService groupService,
                             FamilyMemberRepository members,
                             AccessGuard guard) {
        this.tasks = tasks;
        this.groupService = groupService;
        this.members = members;
        this.guard = guard;
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
        return toResponse(task);
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
        return toResponse(task);
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

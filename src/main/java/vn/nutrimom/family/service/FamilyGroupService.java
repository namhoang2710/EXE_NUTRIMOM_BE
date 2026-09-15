package vn.nutrimom.family.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyGroupStatus;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.dto.CreateFamilyGroupRequest;
import vn.nutrimom.family.dto.FamilyGroupResponse;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.pregnancy.domain.PregnancyEntity;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class FamilyGroupService {
    private final FamilyGroupRepository groups;
    private final FamilyMemberRepository members;
    private final PregnancyRepository pregnancies;

    public FamilyGroupService(FamilyGroupRepository groups,
                              FamilyMemberRepository members,
                              PregnancyRepository pregnancies) {
        this.groups = groups;
        this.members = members;
        this.pregnancies = pregnancies;
    }

    @Transactional
    public FamilyGroupResponse create(String userId, CreateFamilyGroupRequest request) {
        PregnancyEntity pregnancy = resolveOwnedActivePregnancy(userId, request);
        FamilyGroupEntity existing = groups.findByPregnancyIdAndStatus(
                pregnancy.getId(), FamilyGroupStatus.ACTIVE).orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        FamilyGroupEntity group = new FamilyGroupEntity();
        group.setPregnancyId(pregnancy.getId());
        group.setOwnerUserId(userId);
        group.setStatus(FamilyGroupStatus.ACTIVE);
        groups.saveAndFlush(group);
        return toResponse(group);
    }

    @Transactional(readOnly = true)
    public List<FamilyGroupResponse> getAccessibleGroups(String userId) {
        return accessibleGroupEntities(userId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<FamilyGroupEntity> accessibleGroupEntities(String userId) {
        Map<String, FamilyGroupEntity> accessible = new LinkedHashMap<>();
        groups.findByOwnerUserIdAndStatusOrderByCreatedAtDesc(
                        userId, FamilyGroupStatus.ACTIVE)
                .forEach(group -> accessible.put(group.getId(), group));
        members.findByUserIdAndStatusOrderByCreatedAtDesc(
                        userId, FamilyMemberStatus.ACTIVE)
                .stream()
                .map(member -> groups.findByIdAndStatus(
                        member.getFamilyGroupId(), FamilyGroupStatus.ACTIVE).orElse(null))
                .filter(java.util.Objects::nonNull)
                .forEach(group -> accessible.putIfAbsent(group.getId(), group));
        return List.copyOf(accessible.values());
    }

    @Transactional(readOnly = true)
    public FamilyGroupEntity requireOwnedActiveGroup(String userId) {
        return groups.findByOwnerUserIdAndStatusOrderByCreatedAtDesc(
                        userId, FamilyGroupStatus.ACTIVE)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FAMILY_GROUP_NOT_FOUND, "Create a family group for the active pregnancy first."));
    }

    @Transactional(readOnly = true)
    public FamilyGroupEntity requireOwnedActiveGroup(String userId, String groupId) {
        FamilyGroupEntity group = groups.findByIdAndStatus(groupId, FamilyGroupStatus.ACTIVE)
                .orElseThrow(this::notFound);
        if (!group.getOwnerUserId().equals(userId)) {
            throw notFound();
        }
        return group;
    }

    private PregnancyEntity resolveOwnedActivePregnancy(
            String userId, CreateFamilyGroupRequest request) {
        String requestedPregnancyId = request == null ? null : request.pregnancyId();
        if (requestedPregnancyId == null || requestedPregnancyId.isBlank()) {
            return pregnancies.findByOwnerUserIdAndStatus(userId, PregnancyStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACTIVE_PREGNANCY_NOT_FOUND, "An active pregnancy is required to create a family group."));
        }
        PregnancyEntity pregnancy = pregnancies.findByIdAndOwnerUserId(
                        requestedPregnancyId.trim(), userId)
                .orElseThrow(this::notFound);
        if (pregnancy.getStatus() != PregnancyStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.PREGNANCY_NOT_ACTIVE, "Only an active pregnancy can have an active family group.");
        }
        return pregnancy;
    }

    private FamilyGroupResponse toResponse(FamilyGroupEntity group) {
        return new FamilyGroupResponse(
                group.getId(), group.getPregnancyId(), group.getOwnerUserId(),
                group.getStatus().name(), group.getCreatedAt(), group.getUpdatedAt());
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Family group was not found.");
    }
}

package vn.nutrimom.family.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.dto.FamilyMemberResponse;
import vn.nutrimom.family.dto.UpdateFamilyMemberRequest;
import vn.nutrimom.family.repository.FamilyMemberRepository;

@Service
public class FamilyMemberService {
    private final FamilyMemberRepository members;
    private final FamilyGroupService groupService;

    public FamilyMemberService(FamilyMemberRepository members,
                               FamilyGroupService groupService) {
        this.members = members;
        this.groupService = groupService;
    }

    @Transactional(readOnly = true)
    public List<FamilyMemberResponse> getAccessibleMembers(String userId) {
        List<FamilyGroupEntity> accessibleGroups = groupService.accessibleGroupEntities(userId);
        List<String> ownedGroupIds = accessibleGroups.stream()
                .filter(group -> group.getOwnerUserId().equals(userId))
                .map(FamilyGroupEntity::getId)
                .toList();
        Map<String, FamilyMemberEntity> visible = new LinkedHashMap<>();
        if (!ownedGroupIds.isEmpty()) {
            members.findByFamilyGroupIdInAndStatusOrderByCreatedAtAsc(
                            ownedGroupIds, FamilyMemberStatus.ACTIVE)
                    .forEach(member -> visible.put(member.getId(), member));
        }
        members.findByUserIdAndStatusOrderByCreatedAtDesc(
                        userId, FamilyMemberStatus.ACTIVE)
                .forEach(member -> visible.putIfAbsent(member.getId(), member));
        return visible.values().stream().map(this::toResponse).toList();
    }

    @Transactional
    public FamilyMemberResponse update(
            String userId, String memberId, UpdateFamilyMemberRequest request) {
        FamilyMemberEntity member = loadOwnedMember(userId, memberId);
        if (request.version() != member.getVersion()) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "Family membership was updated elsewhere. Reload and try again.");
        }
        member.setScopes(request.scopes());
        members.saveAndFlush(member);
        return toResponse(member);
    }

    @Transactional
    public void revoke(String userId, String memberId) {
        FamilyMemberEntity member = loadOwnedMember(userId, memberId);
        if (member.getStatus() != FamilyMemberStatus.REVOKED) {
            member.setStatus(FamilyMemberStatus.REVOKED);
            members.saveAndFlush(member);
        }
    }

    private FamilyMemberEntity loadOwnedMember(String userId, String memberId) {
        FamilyMemberEntity member = members.findById(memberId)
                .orElseThrow(this::notFound);
        groupService.requireOwnedActiveGroup(userId, member.getFamilyGroupId());
        return member;
    }

    private FamilyMemberResponse toResponse(FamilyMemberEntity member) {
        return new FamilyMemberResponse(
                member.getId(), member.getFamilyGroupId(), member.getUserId(),
                member.getRelationship().name(), member.getMembershipRole().name(),
                scopeNames(member.getScopes()), member.getStatus().name(),
                member.getVersion(), member.getCreatedAt(), member.getUpdatedAt());
    }

    private List<String> scopeNames(Set<FamilyScope> scopes) {
        return scopes.stream().map(Enum::name).sorted().toList();
    }

    private BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "Family member was not found.");
    }
}

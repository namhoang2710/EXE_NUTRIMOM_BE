package vn.nutrimom.user.service;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.family.domain.FamilyGroupStatus;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyMembershipRole;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;
import vn.nutrimom.pregnancy.domain.PregnancyStatus;
import vn.nutrimom.pregnancy.repository.PregnancyRepository;

@Service
public class UserPersonaService {
    private static final List<UserRole> STAFF_ROLE_PRIORITY = List.of(
            UserRole.EXPERT,
            UserRole.ADMIN,
            UserRole.CONTENT_PUBLISHER,
            UserRole.CONTENT_REVIEWER,
            UserRole.CONTENT_EDITOR);

    private final PregnancyRepository pregnancies;
    private final FamilyMemberRepository familyMembers;
    private final FamilyGroupRepository familyGroups;

    public UserPersonaService(PregnancyRepository pregnancies,
                              FamilyMemberRepository familyMembers,
                              FamilyGroupRepository familyGroups) {
        this.pregnancies = pregnancies;
        this.familyMembers = familyMembers;
        this.familyGroups = familyGroups;
    }

    @Transactional(readOnly = true)
    public String derive(UserEntity user) {
        Set<UserRole> systemRoles = user.getRoles();
        if (systemRoles != null) {
            for (UserRole role : STAFF_ROLE_PRIORITY) {
                if (systemRoles.contains(role)) {
                    return role.name();
                }
            }
        }
        if (pregnancies.existsByOwnerUserIdAndStatus(user.getId(), PregnancyStatus.ACTIVE)) {
            return "MOM";
        }
        List<FamilyMemberEntity> memberships = familyMembers
                .findByUserIdAndStatusOrderByCreatedAtDesc(
                        user.getId(), FamilyMemberStatus.ACTIVE)
                .stream()
                .filter(member -> familyGroups.existsByIdAndStatus(
                        member.getFamilyGroupId(), FamilyGroupStatus.ACTIVE))
                .toList();
        if (memberships.stream().anyMatch(member ->
                member.getMembershipRole() == FamilyMembershipRole.PARTNER)) {
            return FamilyMembershipRole.PARTNER.name();
        }
        if (!memberships.isEmpty()) {
            return FamilyMembershipRole.FAMILY_MEMBER.name();
        }
        return UserRole.USER.name();
    }
}

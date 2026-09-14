package vn.nutrimom.family.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.service.PhoneNormalizer;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.family.domain.FamilyGroupEntity;
import vn.nutrimom.family.domain.FamilyGroupStatus;
import vn.nutrimom.family.domain.FamilyInvitationEntity;
import vn.nutrimom.family.domain.FamilyMemberEntity;
import vn.nutrimom.family.domain.FamilyMemberStatus;
import vn.nutrimom.family.domain.FamilyScope;
import vn.nutrimom.family.dto.AcceptFamilyInvitationRequest;
import vn.nutrimom.family.dto.CreateFamilyInvitationRequest;
import vn.nutrimom.family.dto.FamilyInvitationResponse;
import vn.nutrimom.family.dto.FamilyMemberResponse;
import vn.nutrimom.family.repository.FamilyGroupRepository;
import vn.nutrimom.family.repository.FamilyInvitationRepository;
import vn.nutrimom.family.repository.FamilyMemberRepository;

@Service
public class FamilyInvitationService {
    private static final int DEFAULT_EXPIRY_HOURS = 48;

    private final FamilyInvitationRepository invitations;
    private final FamilyMemberRepository members;
    private final FamilyGroupRepository groups;
    private final FamilyGroupService groupService;
    private final UserRepository users;
    private final PhoneNormalizer phoneNormalizer;
    private final InvitationTokenService tokens;

    public FamilyInvitationService(FamilyInvitationRepository invitations,
                                   FamilyMemberRepository members,
                                   FamilyGroupRepository groups,
                                   FamilyGroupService groupService,
                                   UserRepository users,
                                   PhoneNormalizer phoneNormalizer,
                                   InvitationTokenService tokens) {
        this.invitations = invitations;
        this.members = members;
        this.groups = groups;
        this.groupService = groupService;
        this.users = users;
        this.phoneNormalizer = phoneNormalizer;
        this.tokens = tokens;
    }

    @Transactional
    public FamilyInvitationResponse create(
            String userId, CreateFamilyInvitationRequest request) {
        FamilyGroupEntity group = groupService.requireOwnedActiveGroup(userId);
        String phone = normalizePhone(request.invitedPhone());
        String email = normalizeEmail(request.invitedEmail());
        if ((phone == null) == (email == null)) {
            throw validation("Provide exactly one of invited_phone or invited_email.");
        }

        InvitationTokenService.TokenMaterial token = tokens.generate();
        FamilyInvitationEntity invitation = new FamilyInvitationEntity();
        invitation.setFamilyGroupId(group.getId());
        invitation.setInvitedPhone(phone);
        invitation.setInvitedEmail(email);
        invitation.setTokenHash(token.tokenHash());
        invitation.setRelationship(request.relationship());
        invitation.setScopes(request.scopes());
        invitation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(
                request.expiresInHours() == null
                        ? DEFAULT_EXPIRY_HOURS : request.expiresInHours()));
        invitations.saveAndFlush(invitation);
        return new FamilyInvitationResponse(
                invitation.getId(), invitation.getFamilyGroupId(),
                invitation.getInvitedPhone(), invitation.getInvitedEmail(),
                token.rawToken(), invitation.getRelationship().name(),
                scopeNames(invitation.getScopes()), invitation.getExpiresAt(),
                invitation.getCreatedAt());
    }

    @Transactional
    public FamilyMemberResponse accept(
            String userId, AcceptFamilyInvitationRequest request) {
        UserEntity user = users.findByIdForUpdate(userId)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                        "The authenticated account is unavailable."));
        FamilyInvitationEntity invitation = invitations
                .findByTokenHashForUpdate(tokens.hash(request.token()))
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "INVALID_INVITATION_TOKEN",
                        "Invitation token is invalid."));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (invitation.getAcceptedAt() != null) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "INVITATION_ALREADY_USED", "Invitation token has already been used.");
        }
        if (!invitation.getExpiresAt().isAfter(now)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVITATION_EXPIRED", "Invitation token has expired.");
        }

        FamilyGroupEntity group = groups.findByIdAndStatus(
                        invitation.getFamilyGroupId(), FamilyGroupStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "FAMILY_GROUP_NOT_FOUND",
                        "The invitation's family group is unavailable."));
        if (group.getOwnerUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "OWNER_ALREADY_IN_GROUP",
                    "The pregnancy owner does not need a family membership.");
        }
        requireMatchingInviteTarget(invitation, user);
        if (members.findByFamilyGroupIdAndUserIdAndStatus(
                group.getId(), userId, FamilyMemberStatus.ACTIVE).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "FAMILY_MEMBER_EXISTS",
                    "The account already has an active membership in this group.");
        }

        FamilyMemberEntity member = new FamilyMemberEntity();
        member.setFamilyGroupId(group.getId());
        member.setUserId(userId);
        member.setRelationship(invitation.getRelationship());
        member.setMembershipRole(invitation.getRelationship().membershipRole());
        member.setScopes(invitation.getScopes());
        member.setStatus(FamilyMemberStatus.ACTIVE);
        members.saveAndFlush(member);

        invitation.setAcceptedAt(now);
        invitations.save(invitation);
        if (user.getOnboardingStatus() != OnboardingStatus.COMPLETED) {
            user.setOnboardingStatus(OnboardingStatus.COMPLETED);
            users.saveAndFlush(user);
        }
        return toMemberResponse(member);
    }

    private void requireMatchingInviteTarget(
            FamilyInvitationEntity invitation, UserEntity user) {
        boolean phoneMatches = invitation.getInvitedPhone() != null
                && invitation.getInvitedPhone().equals(user.getPhone());
        boolean emailMatches = invitation.getInvitedEmail() != null
                && user.getEmail() != null
                && invitation.getInvitedEmail().equalsIgnoreCase(user.getEmail());
        if (!phoneMatches && !emailMatches) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "INVITATION_TARGET_MISMATCH",
                    "Invitation token belongs to a different account.");
        }
    }

    private String normalizePhone(String phone) {
        return phone == null || phone.isBlank()
                ? null : phoneNormalizer.normalizeVietnamesePhone(phone);
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank()
                ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private FamilyMemberResponse toMemberResponse(FamilyMemberEntity member) {
        return new FamilyMemberResponse(
                member.getId(), member.getFamilyGroupId(), member.getUserId(),
                member.getRelationship().name(), member.getMembershipRole().name(),
                scopeNames(member.getScopes()), member.getStatus().name(),
                member.getVersion(), member.getCreatedAt(), member.getUpdatedAt());
    }

    private List<String> scopeNames(Set<FamilyScope> scopes) {
        return scopes.stream().map(Enum::name).sorted().toList();
    }

    private BusinessException validation(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT,
                "VALIDATION_ERROR", message);
    }
}

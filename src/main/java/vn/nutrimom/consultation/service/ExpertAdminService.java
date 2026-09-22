package vn.nutrimom.consultation.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.service.PhoneNormalizer;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.common.security.AccessGuard;
import vn.nutrimom.consultation.domain.ExpertProfileEntity;
import vn.nutrimom.consultation.domain.ExpertStatus;
import vn.nutrimom.consultation.dto.ExpertDtos.AdminExpertResponse;
import vn.nutrimom.consultation.dto.ExpertDtos.CreateExpertRequest;
import vn.nutrimom.consultation.dto.ExpertDtos.UpdateExpertRequest;
import vn.nutrimom.consultation.repository.ExpertProfileRepository;

/** Admin CRUD chuyên gia và cấp tài khoản (role EXPERT) cho chuyên gia. */
@Service
public class ExpertAdminService {
    private final ExpertProfileRepository experts;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PhoneNormalizer phoneNormalizer;
    private final AccessGuard guard;

    public ExpertAdminService(ExpertProfileRepository experts, UserRepository users,
                              PasswordEncoder passwordEncoder, PhoneNormalizer phoneNormalizer,
                              AccessGuard guard) {
        this.experts = experts;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.phoneNormalizer = phoneNormalizer;
        this.guard = guard;
    }

    @Transactional
    public AdminExpertResponse create(CreateExpertRequest request) {
        String phone = phoneNormalizer.normalizeVietnamesePhone(request.phone());
        if (users.existsByPhone(phone)) {
            throw duplicatePhone();
        }
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.fullName().trim());
        user.setStatus(UserStatus.ACTIVE);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user.setTermsAcceptedAt(now);
        user.setPrivacyAcceptedAt(now);
        user.getRoles().add(UserRole.EXPERT);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw duplicatePhone();
        }

        ExpertProfileEntity profile = new ExpertProfileEntity();
        profile.setUserId(user.getId());
        applyProfileFields(profile, request);
        experts.saveAndFlush(profile);
        return toResponse(user, profile);
    }

    @Transactional(readOnly = true)
    public List<AdminExpertResponse> list() {
        return experts.findAll().stream()
                .map(profile -> toResponse(requireUser(profile.getUserId()), profile))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminExpertResponse detail(String userId) {
        ExpertProfileEntity profile = guard.requireOwned(
                experts.findById(userId), "Không tìm thấy chuyên gia.");
        return toResponse(requireUser(userId), profile);
    }

    @Transactional
    public AdminExpertResponse update(String userId, UpdateExpertRequest request) {
        ExpertProfileEntity profile = guard.requireOwned(
                experts.findById(userId), "Không tìm thấy chuyên gia.");
        if (request.version() == null || request.version() != profile.getVersion()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "Hồ sơ chuyên gia đã được cập nhật ở nơi khác. Vui lòng tải lại.");
        }
        UserEntity user = requireUser(userId);
        if (request.fullName() != null) {
            profile.setFullName(request.fullName().trim());
            user.setDisplayName(request.fullName().trim());
            users.saveAndFlush(user);
        }
        if (request.specialty() != null) {
            profile.setSpecialty(request.specialty());
        }
        if (request.title() != null) {
            profile.setTitle(request.title());
        }
        if (request.workplace() != null) {
            profile.setWorkplace(request.workplace());
        }
        if (request.yearsOfExperience() != null) {
            profile.setYearsOfExperience(request.yearsOfExperience());
        }
        if (request.bio() != null) {
            profile.setBio(request.bio());
        }
        if (request.status() != null) {
            profile.setStatus(request.status());
        }
        experts.saveAndFlush(profile);
        return toResponse(user, profile);
    }

    /** Xóa mềm: chuyển hồ sơ sang INACTIVE (không hiển thị, không nhận đặt lịch mới). */
    @Transactional
    public void deactivate(String userId) {
        ExpertProfileEntity profile = guard.requireOwned(
                experts.findById(userId), "Không tìm thấy chuyên gia.");
        profile.setStatus(ExpertStatus.INACTIVE);
        experts.saveAndFlush(profile);
    }

    private void applyProfileFields(ExpertProfileEntity profile, CreateExpertRequest request) {
        profile.setFullName(request.fullName().trim());
        profile.setSpecialty(request.specialty());
        profile.setTitle(request.title());
        profile.setWorkplace(request.workplace());
        profile.setYearsOfExperience(request.yearsOfExperience());
        profile.setBio(request.bio());
        profile.setStatus(ExpertStatus.ACTIVE);
    }

    private UserEntity requireUser(String userId) {
        return users.findById(userId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tài khoản chuyên gia."));
    }

    private BusinessException duplicatePhone() {
        return new BusinessException(ErrorCode.PHONE_ALREADY_EXISTS, "Số điện thoại này đã được đăng ký.");
    }

    private AdminExpertResponse toResponse(UserEntity user, ExpertProfileEntity profile) {
        return new AdminExpertResponse(profile.getUserId(), user.getPhone(), profile.getFullName(),
                profile.getSpecialty(), profile.getTitle(), profile.getWorkplace(),
                profile.getYearsOfExperience(), profile.getBio(), profile.getAvatarKey(),
                profile.getAvatarUrl(), profile.getStatus(), profile.getAverageRating(),
                profile.getRatingCount(), profile.getVersion(), profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}

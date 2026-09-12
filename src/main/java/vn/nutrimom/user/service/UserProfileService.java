package vn.nutrimom.user.service;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.user.dto.UpdateProfileRequest;
import vn.nutrimom.user.dto.UserProfileResponse;

@Service
public class UserProfileService {

    /** Thứ tự ưu tiên khi suy ra persona chính từ nhiều system role. */
    private static final List<UserRole> ROLE_PRIORITY = List.of(
            UserRole.ADMIN, UserRole.EXPERT, UserRole.CONTENT_PUBLISHER,
            UserRole.CONTENT_REVIEWER, UserRole.CONTENT_EDITOR, UserRole.USER);

    private final UserRepository users;

    public UserProfileService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(String userId) {
        return toResponse(loadUser(userId));
    }

    @Transactional
    public UserProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
        UserEntity user = loadUser(userId);
        if (request.version() == null || request.version() != user.getVersion()) {
            throw versionConflict();
        }

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            user.setEmail(request.email().isBlank() ? null : request.email().trim());
        }
        if (request.dateOfBirth() != null) {
            user.setDateOfBirth(request.dateOfBirth());
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }
        if (request.avatarKey() != null) {
            user.setAvatarKey(request.avatarKey().isBlank() ? null : request.avatarKey().trim());
        }

        // Đã có hồ sơ cơ bản (display_name) thì đẩy onboarding sang bước cần ngữ cảnh.
        // Bước ->COMPLETED do luồng pregnancy/family (nhánh sau) đảm nhận.
        if (user.getOnboardingStatus() == OnboardingStatus.PROFILE_REQUIRED
                && user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            user.setOnboardingStatus(OnboardingStatus.CONTEXT_REQUIRED);
        }

        users.saveAndFlush(user);
        return toResponse(user);
    }

    private UserEntity loadUser(String userId) {
        return users.findById(userId).orElseThrow(() -> new BusinessException(
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Phiên đăng nhập không hợp lệ."));
    }

    private UserProfileResponse toResponse(UserEntity user) {
        return new UserProfileResponse(
                user.getId(),
                user.getPhone(),
                user.getEmail(),
                user.getDisplayName(),
                derivePrimaryRole(user.getRoles()),
                user.getGender() == null ? null : user.getGender().name(),
                user.getDateOfBirth(),
                user.getAvatarKey(),
                null,
                user.getOnboardingStatus().name(),
                user.getCreatedAt(),
                user.getVersion());
    }

    private String derivePrimaryRole(Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return UserRole.USER.name();
        }
        return ROLE_PRIORITY.stream()
                .filter(roles::contains)
                .findFirst()
                .orElse(UserRole.USER)
                .name();
    }

    private BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                "Hồ sơ đã được cập nhật ở nơi khác. Vui lòng tải lại và thử lại.");
    }
}

package vn.nutrimom.user.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.user.dto.UpdateProfileRequest;
import vn.nutrimom.user.dto.UserProfileResponse;

@Service
public class UserProfileService {

    private static final int MAX_REASONABLE_AGE = 120;

    private final UserRepository users;
    private final UserPersonaService personaService;

    public UserProfileService(UserRepository users, UserPersonaService personaService) {
        this.users = users;
        this.personaService = personaService;
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
            if (request.displayName().isBlank()) {
                throw validation("display_name must not be blank.");
            }
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            user.setEmail(request.email().isBlank() ? null : request.email().trim());
        }
        if (request.dateOfBirth() != null) {
            if (request.dateOfBirth().isBefore(
                    LocalDate.now(ZoneOffset.UTC).minusYears(MAX_REASONABLE_AGE))) {
                throw validation("date_of_birth is outside the supported age range.");
            }
            user.setDateOfBirth(request.dateOfBirth());
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }
        if (request.avatarKey() != null) {
            user.setAvatarKey(request.avatarKey().isBlank() ? null : request.avatarKey().trim());
        }

        // A complete basic profile is enough to enter MAIN/HOME; pregnancy is optional.
        if ((user.getOnboardingStatus() == OnboardingStatus.PROFILE_REQUIRED
                || user.getOnboardingStatus() == OnboardingStatus.CONTEXT_REQUIRED)
                && user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            user.setOnboardingStatus(OnboardingStatus.COMPLETED);
        }

        users.saveAndFlush(user);
        return toResponse(user);
    }

    private UserEntity loadUser(String userId) {
        return users.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ."));
    }

    private UserProfileResponse toResponse(UserEntity user) {
        return new UserProfileResponse(
                user.getId(),
                user.getPhone(),
                user.getEmail(),
                user.getDisplayName(),
                deriveSalutation(user),
                personaService.derive(user),
                user.getGender() == null ? null : user.getGender().name(),
                user.getDateOfBirth(),
                user.getAvatarKey(),
                null,
                user.getOnboardingStatus().name(),
                user.getCreatedAt(),
                user.getVersion());
    }

    private String deriveSalutation(UserEntity user) {
        if (user.getGender() == null || user.getGender() == vn.nutrimom.auth.domain.Gender.OTHER) {
            return user.getDisplayName();
        }
        return user.getGender() == vn.nutrimom.auth.domain.Gender.MALE ? "Anh" : "Chị";
    }

    private BusinessException versionConflict() {
        return new BusinessException(ErrorCode.VERSION_CONFLICT, "Hồ sơ đã được cập nhật ở nơi khác. Vui lòng tải lại và thử lại.");
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}

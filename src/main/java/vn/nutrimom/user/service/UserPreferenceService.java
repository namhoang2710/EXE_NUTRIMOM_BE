package vn.nutrimom.user.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.user.domain.UserPreferenceEntity;
import vn.nutrimom.user.dto.UpdatePreferencesRequest;
import vn.nutrimom.user.dto.UserPreferencesResponse;
import vn.nutrimom.user.repository.UserPreferenceRepository;

@Service
public class UserPreferenceService {
    private final UserPreferenceRepository preferences;
    private final UserRepository users;

    public UserPreferenceService(UserPreferenceRepository preferences, UserRepository users) {
        this.preferences = preferences;
        this.users = users;
    }

    @Transactional
    public UserPreferencesResponse getPreferences(String userId) {
        requireActiveUserForUpdate(userId);
        return toResponse(preferences.findById(userId).orElseGet(() -> createDefaults(userId)));
    }

    @Transactional
    public UserPreferencesResponse updatePreferences(String userId, UpdatePreferencesRequest request) {
        requireActiveUserForUpdate(userId);
        UserPreferenceEntity preference = preferences.findById(userId)
                .orElseGet(() -> createDefaults(userId));
        if (request.version() != preference.getVersion()) {
            throw versionConflict();
        }
        if (request.language() != null) {
            preference.setLanguage(request.language().trim());
        }
        if (request.timezone() != null) {
            String timezone = request.timezone().trim();
            if (timezone.isEmpty()) {
                throw validation("Timezone must not be blank");
            }
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException ex) {
                throw validation("Timezone is not a valid IANA timezone");
            }
            preference.setTimezone(timezone);
        }
        if (request.notificationEnabled() != null) {
            preference.setNotificationEnabled(request.notificationEnabled());
        }
        if (request.pushEnabled() != null) {
            preference.setPushEnabled(request.pushEnabled());
        }
        if (request.emailEnabled() != null) {
            preference.setEmailEnabled(request.emailEnabled());
        }
        if (request.smsEnabled() != null) {
            preference.setSmsEnabled(request.smsEnabled());
        }
        if (request.preferredReminderTime() != null) {
            preference.setPreferredReminderTime(request.preferredReminderTime());
        }
        preferences.saveAndFlush(preference);
        return toResponse(preference);
    }

    private UserPreferenceEntity createDefaults(String userId) {
        UserPreferenceEntity preference = new UserPreferenceEntity();
        preference.setUserId(userId);
        return preferences.saveAndFlush(preference);
    }

    private void requireActiveUserForUpdate(String userId) {
        users.findByIdForUpdate(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "The authenticated account is unavailable."));
    }

    private UserPreferencesResponse toResponse(UserPreferenceEntity preference) {
        return new UserPreferencesResponse(
                preference.getLanguage(), preference.getTimezone(),
                preference.isNotificationEnabled(), preference.isPushEnabled(),
                preference.isEmailEnabled(), preference.isSmsEnabled(),
                preference.getPreferredReminderTime(), preference.getVersion());
    }

    private BusinessException versionConflict() {
        return new BusinessException(ErrorCode.VERSION_CONFLICT, "Preferences were updated elsewhere. Reload and try again.");
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}

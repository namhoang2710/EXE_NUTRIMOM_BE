package vn.nutrimom.bootstrap;

import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.OnboardingStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserRole;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.service.PhoneNormalizer;

@Component
@ConditionalOnProperty(name = "app.bootstrap.admin-user-enabled", havingValue = "true")
public class AdminUserInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminUserInitializer.class);
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,72}$");

    private final String phone;
    private final String password;
    private final String displayName;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PhoneNormalizer phoneNormalizer;

    public AdminUserInitializer(
            @Value("${app.bootstrap.admin-phone:}") String phone,
            @Value("${app.bootstrap.admin-password:}") String password,
            @Value("${app.bootstrap.admin-display-name:NutriMom Admin}") String displayName,
            UserRepository users,
            PasswordEncoder encoder,
            PhoneNormalizer phoneNormalizer) {
        this.phone = phone;
        this.password = password;
        this.displayName = displayName;
        this.users = users;
        this.encoder = encoder;
        this.phoneNormalizer = phoneNormalizer;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalStateException(
                    "NUTRIMOM_ADMIN_PHONE is required when admin bootstrap is enabled");
        }
        validatePassword();

        String normalizedPhone = phoneNormalizer.normalizeVietnamesePhone(phone);
        UserEntity existing = users.findByPhone(normalizedPhone).orElse(null);
        if (existing != null) {
            synchronizeExistingUser(existing);
            return;
        }

        UserEntity admin = new UserEntity();
        admin.setPhone(normalizedPhone);
        admin.setPasswordHash(encoder.encode(password));
        admin.setDisplayName(normalizedDisplayName());
        admin.setStatus(UserStatus.ACTIVE);
        admin.setRoles(Set.of(UserRole.ADMIN));
        admin.setOnboardingStatus(OnboardingStatus.COMPLETED);
        users.save(admin);
        log.warn("Created configured local/dev admin user {}. Disable admin bootstrap outside local development.",
                normalizedPhone);
    }

    private void synchronizeExistingUser(UserEntity user) {
        boolean statusUpdated = user.getStatus() != UserStatus.ACTIVE;
        boolean roleUpdated = user.getRoles().add(UserRole.ADMIN);
        boolean passwordUpdated = user.getPasswordHash() == null
                || !encoder.matches(password, user.getPasswordHash());

        if (statusUpdated) {
            user.setStatus(UserStatus.ACTIVE);
        }
        if (passwordUpdated) {
            user.setPasswordHash(encoder.encode(password));
        }
        if (statusUpdated || roleUpdated || passwordUpdated) {
            users.save(user);
            log.warn(
                    "Updated configured local/dev admin user {} (activated={}, promoted={}, passwordUpdated={}).",
                    user.getPhone(), statusUpdated, roleUpdated, passwordUpdated);
        } else {
            log.debug("Configured local/dev admin user {} is already synchronized.", user.getPhone());
        }
    }

    private void validatePassword() {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalStateException(
                    "NUTRIMOM_ADMIN_PASSWORD must contain 8-72 characters, including a letter and a digit");
        }
    }

    private String normalizedDisplayName() {
        return displayName == null || displayName.isBlank() ? "NutriMom Admin" : displayName.trim();
    }
}

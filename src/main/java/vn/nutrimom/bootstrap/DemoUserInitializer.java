package vn.nutrimom.bootstrap;

import java.util.Set;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.service.PhoneNormalizer;

@Component
public class DemoUserInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoUserInitializer.class);
    private final boolean enabled;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PhoneNormalizer phoneNormalizer;

    public DemoUserInitializer(@Value("${app.bootstrap.demo-user-enabled:false}") boolean enabled,
            UserRepository users, PasswordEncoder encoder, PhoneNormalizer phoneNormalizer) {
        this.enabled = enabled;
        this.users = users;
        this.encoder = encoder;
        this.phoneNormalizer = phoneNormalizer;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        String phone = phoneNormalizer.normalizeVietnamesePhone("0901234567");
        if (users.existsByPhone(phone)) return;
        UserEntity demo = new UserEntity();
        demo.setPhone(phone);
        demo.setPasswordHash(encoder.encode("NutriMom@123"));
        demo.setDisplayName("Mẹ Bầu Demo");
        demo.setStatus(UserStatus.ACTIVE);
        demo.setRoles(Set.of(UserRole.USER));
        users.save(demo);
        log.warn("Created local demo user {}. Disable demo user outside local development.", phone);
    }
}

package vn.nutrimom.auth.service;

import java.time.*;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.dto.*;
import vn.nutrimom.auth.repository.*;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.user.service.UserPersonaService;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final PhoneNormalizer phoneNormalizer;
    private final TokenService tokenService;
    private final UserPersonaService personaService;
    private final vn.nutrimom.common.email.EmailService emailService;
    private final String magicLinkBaseUrl;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager,
                       PhoneNormalizer phoneNormalizer, TokenService tokenService,
                       UserPersonaService personaService,
                       vn.nutrimom.common.email.EmailService emailService,
                       @org.springframework.beans.factory.annotation.Value("${app.auth.magic-link-base-url:http://localhost:5173}") String magicLinkBaseUrl) {
        this.userRepository = users;
        this.refreshTokenRepository = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.phoneNormalizer = phoneNormalizer;
        this.tokenService = tokenService;
        this.personaService = personaService;
        this.emailService = emailService;
        this.magicLinkBaseUrl = magicLinkBaseUrl;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String phone = null;
        if (request.phone() != null && !request.phone().isBlank()) {
            phone = phoneNormalizer.normalizeVietnamesePhone(request.phone());
            if (userRepository.existsByPhone(phone)) throw duplicatePhone();
        }

        String email = null;
        if (request.email() != null && !request.email().isBlank()) {
            email = request.email().trim().toLowerCase();
            if (userRepository.existsByEmailIgnoreCase(email)) {
                throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email này đã được đăng ký.");
            }
        }

        if (phone == null && email == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Vui lòng nhập email hoặc số điện thoại để đăng ký.");
        }

        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setStatus(UserStatus.ACTIVE);
        OffsetDateTime acceptedAt = OffsetDateTime.now(ZoneOffset.UTC);
        user.setTermsAcceptedAt(acceptedAt);
        user.setPrivacyAcceptedAt(acceptedAt);
        user.getRoles().add(UserRole.USER);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (phone != null && userRepository.existsByPhone(phone)) throw duplicatePhone();
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email hoặc số điện thoại này đã được sử dụng.");
        }

        if (email != null) {
            try {
                String activationUrl = magicLinkBaseUrl + "/login";
                emailService.sendRegistrationConfirmation(email, user.getDisplayName(), activationUrl);
            } catch (Exception ex) {
                // Email sending failure should not prevent user registration
            }
        }

        return issueSession(user, request.deviceId());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String input = request.phone() != null ? request.phone().trim() : "";
        String usernameToAuth;
        UserEntity user;

        if (input.contains("@")) {
            usernameToAuth = input.toLowerCase();
            user = userRepository.findByEmailIgnoreCase(usernameToAuth).orElseThrow(this::invalidCredentials);
        } else {
            usernameToAuth = phoneNormalizer.normalizeVietnamesePhone(input);
            user = userRepository.findByPhone(usernameToAuth).orElseThrow(this::invalidCredentials);
        }

        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(usernameToAuth, request.password()));
        } catch (AuthenticationException ex) {
            throw invalidCredentials();
        }

        if (user.getStatus() == UserStatus.DISABLED || user.getStatus() == UserStatus.LOCKED) {
            throw invalidCredentials();
        }
        return issueSession(user, request.deviceId());
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshTokenEntity current = refreshTokenRepository
                .findByTokenHashForUpdate(tokenService.hash(request.refreshToken()))
                .orElseThrow(this::invalidRefreshToken);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!current.isUsableAt(now)) throw invalidRefreshToken();
        if (current.getDeviceId() != null
                && (request.deviceId() == null
                || request.deviceId().isBlank()
                || !current.getDeviceId().equals(request.deviceId().trim()))) {
            throw invalidRefreshToken();
        }
        UserEntity user = userRepository.findById(current.getUserId())
                .filter(value -> value.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(this::invalidRefreshToken);
        return toAuthResponse(user, tokenService.rotate(current, user, request.deviceId()).bundle());
    }

    @Transactional
    public LogoutResponse logout(LogoutRequest request) {
        refreshTokenRepository.findByTokenHashForUpdate(tokenService.hash(request.refreshToken()))
                .ifPresent(token -> {
                    if (token.getRevokedAt() == null) {
                        token.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
                        refreshTokenRepository.save(token);
                    }
                });
        return new LogoutResponse(true);
    }

    @Transactional(readOnly = true)
    public UserResponse me(String userId) {
        return userRepository.findById(userId).map(this::toUserResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ."));
    }

    public AuthResponse issueSession(UserEntity user, String deviceId) {
        return toAuthResponse(user, tokenService.issue(user, deviceId).bundle());
    }

    private AuthResponse toAuthResponse(UserEntity user, TokenBundle token) {
        return new AuthResponse(token.accessToken(), token.accessExpiresIn(),
                token.refreshToken(), token.refreshExpiresIn(), token.tokenType(), toUserResponse(user));
    }

    private UserResponse toUserResponse(UserEntity user) {
        List<String> roles = user.getRoles().stream().map(Enum::name).sorted().toList();
        return new UserResponse(user.getId(), user.getPhone(), user.getDisplayName(),
                personaService.derive(user), roles, user.getStatus().name(),
                user.getOnboardingStatus().name(),
                user.getCreatedAt());
    }

    private BusinessException duplicatePhone() {
        return new BusinessException(ErrorCode.PHONE_ALREADY_EXISTS, "Số điện thoại này đã được đăng ký.");
    }
    private BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Tài khoản hoặc mật khẩu không đúng.");
    }
    private BusinessException invalidRefreshToken() {
        return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token không hợp lệ hoặc đã hết hạn.");
    }
}

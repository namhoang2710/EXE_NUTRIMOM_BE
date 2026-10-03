package vn.nutrimom.auth.service;

import java.time.*;
import java.util.List;
import java.util.Optional;
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

        OffsetDateTime acceptedAt = OffsetDateTime.now(ZoneOffset.UTC);
        user.setTermsAcceptedAt(acceptedAt);
        user.setPrivacyAcceptedAt(acceptedAt);
        user.getRoles().add(UserRole.USER);

        String rawActivationToken = null;
        if (email != null) {
            user.setStatus(UserStatus.PENDING_ACTIVATION);
            rawActivationToken = tokenService.generateSecureRandomToken();
            user.setEmailActivationTokenHash(tokenService.hash(rawActivationToken));
            user.setEmailActivationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        } else {
            user.setStatus(UserStatus.ACTIVE);
        }

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (phone != null && userRepository.existsByPhone(phone)) throw duplicatePhone();
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email hoặc số điện thoại này đã được sử dụng.");
        }

        if (email != null && rawActivationToken != null) {
            try {
                String activationUrl = magicLinkBaseUrl + "/auth/activate?token=" + rawActivationToken;
                emailService.sendRegistrationConfirmation(email, user.getDisplayName(), activationUrl);
            } catch (Exception ex) {
                // Email sending failure logged inside EmailService
            }
            // Return AuthResponse without tokens so client is NOT authenticated before activation
            return new AuthResponse(null, 0, null, 0, null, toUserResponse(user));
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

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        if (user.getStatus() == UserStatus.PENDING_ACTIVATION) {
            throw new BusinessException(ErrorCode.ACCOUNT_PENDING_ACTIVATION,
                    "Tài khoản của bạn chưa được kích hoạt. Vui lòng kiểm tra email để kích hoạt tài khoản trước khi đăng nhập.");
        }

        if (user.getStatus() == UserStatus.DISABLED || user.getStatus() == UserStatus.LOCKED) {
            throw invalidCredentials();
        }

        return issueSession(user, request.deviceId());
    }

    @Transactional
    public AuthResponse activateAccount(vn.nutrimom.auth.dto.ActivateAccountRequest request) {
        String tokenHash = tokenService.hash(request.token().trim());
        UserEntity user = userRepository.findByEmailActivationTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ACTIVATION_TOKEN,
                        "Mã kích hoạt tài khoản không hợp lệ hoặc đã được sử dụng."));

        if (user.getEmailActivationExpiresAt() != null
                && user.getEmailActivationExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BusinessException(ErrorCode.ACTIVATION_TOKEN_EXPIRED,
                    "Liên kết kích hoạt đã hết hạn. Vui lòng yêu cầu gửi lại email kích hoạt.");
        }

        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setEmailActivationTokenHash(null);
        user.setEmailActivationExpiresAt(null);
        userRepository.save(user);

        return issueSession(user, request.deviceId());
    }

    @Transactional
    public vn.nutrimom.auth.dto.ResendActivationResponse resendActivation(vn.nutrimom.auth.dto.ResendActivationRequest request) {
        String email = request.email().trim().toLowerCase();
        Optional<UserEntity> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return new vn.nutrimom.auth.dto.ResendActivationResponse(true, "Nếu email tồn tại trong hệ thống, liên kết kích hoạt đã được gửi.");
        }
        UserEntity user = userOpt.get();
        if (user.getStatus() == UserStatus.ACTIVE) {
            return new vn.nutrimom.auth.dto.ResendActivationResponse(false, "Tài khoản này đã được kích hoạt trước đó. Bạn có thể đăng nhập ngay.");
        }

        String rawActivationToken = tokenService.generateSecureRandomToken();
        user.setEmailActivationTokenHash(tokenService.hash(rawActivationToken));
        user.setEmailActivationExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        userRepository.save(user);

        String activationUrl = magicLinkBaseUrl + "/auth/activate?token=" + rawActivationToken;
        emailService.sendRegistrationConfirmation(email, user.getDisplayName(), activationUrl);

        return new vn.nutrimom.auth.dto.ResendActivationResponse(true, "Email kích hoạt mới đã được gửi tới " + email);
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

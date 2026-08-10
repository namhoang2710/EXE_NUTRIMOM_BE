package vn.nutrimom.auth.service;

import java.time.*;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.dto.*;
import vn.nutrimom.auth.repository.*;
import vn.nutrimom.common.exception.BusinessException;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final PhoneNormalizer phoneNormalizer;
    private final TokenService tokenService;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager,
                       PhoneNormalizer phoneNormalizer, TokenService tokenService) {
        this.userRepository = users;
        this.refreshTokenRepository = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.phoneNormalizer = phoneNormalizer;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String phone = phoneNormalizer.normalizeVietnamesePhone(request.phone());
        if (userRepository.existsByPhone(phone)) throw duplicatePhone();
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setStatus(UserStatus.ACTIVE);
        user.getRoles().add(UserRole.USER);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw duplicatePhone();
        }
        return issueSession(user, request.deviceId());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String phone = phoneNormalizer.normalizeVietnamesePhone(request.phone());
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(phone, request.password()));
        } catch (AuthenticationException ex) {
            throw invalidCredentials();
        }
        UserEntity user = userRepository.findByPhone(phone).orElseThrow(this::invalidCredentials);
        return issueSession(user, request.deviceId());
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshTokenEntity current = refreshTokenRepository
                .findByTokenHashForUpdate(tokenService.hash(request.refreshToken()))
                .orElseThrow(this::invalidRefreshToken);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!current.isUsableAt(now)) throw invalidRefreshToken();
        if (current.getDeviceId() != null && request.deviceId() != null
                && !request.deviceId().isBlank()
                && !current.getDeviceId().equals(request.deviceId().trim())) throw invalidRefreshToken();
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
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED", "Phiên đăng nhập không hợp lệ."));
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
                roles, user.getStatus().name(), user.getCreatedAt());
    }

    private BusinessException duplicatePhone() {
        return new BusinessException(HttpStatus.CONFLICT, "PHONE_ALREADY_EXISTS",
                "Số điện thoại này đã được đăng ký.");
    }
    private BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Số điện thoại hoặc mật khẩu không đúng.");
    }
    private BusinessException invalidRefreshToken() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Refresh token không hợp lệ hoặc đã hết hạn.");
    }
}

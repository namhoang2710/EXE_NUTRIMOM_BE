package vn.nutrimom.auth.service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.dto.*;
import vn.nutrimom.auth.repository.MagicLoginTokenRepository;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;

@Service
public class MagicLinkService {
    private static final Logger log = LoggerFactory.getLogger(MagicLinkService.class);

    private final MagicLoginTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final AuthService authService;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String baseUrl;

    public MagicLinkService(
            MagicLoginTokenRepository tokenRepository,
            UserRepository userRepository,
            TokenService tokenService,
            AuthService authService,
            EmailService emailService,
            @Value("${app.auth.magic-link-base-url:http://localhost:5173/auth/verify}") String baseUrl) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.authService = authService;
        this.emailService = emailService;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public MagicLinkResponse requestMagicLink(MagicLinkRequest request, String remoteAddress) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Kiểm tra cooldown 45s nếu vừa mới gửi
        tokenRepository.findTopByEmailAndStatusOrderByCreatedAtDesc(email, MagicLoginTokenStatus.PENDING)
                .ifPresent(previous -> {
                    if (previous.getCreatedAt().plusSeconds(45).isAfter(now)) {
                        throw new BusinessException(ErrorCode.OTP_RESEND_TOO_SOON,
                                "Vui lòng chờ ít giây trước khi yêu cầu liên kết mới.");
                    }
                    previous.setStatus(MagicLoginTokenStatus.EXPIRED);
                    tokenRepository.saveAndFlush(previous);
                });

        // Tạo 32-byte secure random token
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        // Lưu bản ghi token dạng hash SHA-256
        MagicLoginTokenEntity tokenEntity = new MagicLoginTokenEntity();
        tokenEntity.setEmail(email);
        tokenEntity.setTokenHash(tokenService.hash(rawToken));
        tokenEntity.setStatus(MagicLoginTokenStatus.PENDING);
        tokenEntity.setDeviceId(request.deviceId());
        tokenEntity.setExpiresAt(now.plusMinutes(15));
        tokenRepository.saveAndFlush(tokenEntity);

        // Tạo đường link đăng nhập
        String loginUrl = baseUrl + "?token=" + rawToken;
        log.info("🔗 [MAGIC LINK] Đăng nhập cho {}: {}", email, loginUrl);

        // Gửi email
        emailService.sendMagicLink(email, loginUrl);

        return new MagicLinkResponse(
                true,
                "Liên kết đăng nhập đã được gửi tới " + email + ". Vui lòng kiểm tra hộp thư của bạn.",
                loginUrl
        );
    }

    @Transactional
    public AuthResponse verifyMagicLink(MagicLinkVerifyRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String tokenHash = tokenService.hash(request.token().trim());

        MagicLoginTokenEntity tokenEntity = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                        "Liên kết đăng nhập không hợp lệ hoặc đã hết hạn."));

        if (!tokenEntity.isUsableAt(now)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                    "Liên kết đăng nhập đã hết hạn hoặc đã được sử dụng. Vui lòng yêu cầu liên kết mới.");
        }

        // Đánh dấu token đã sử dụng (chỉ dùng 1 lần)
        tokenEntity.setStatus(MagicLoginTokenStatus.USED);
        tokenEntity.setUsedAt(now);
        tokenRepository.saveAndFlush(tokenEntity);

        // Tìm hoặc tự động tạo tài khoản theo email
        String email = tokenEntity.getEmail();
        UserEntity user = userRepository.findByEmailIgnoreCase(email)
                .orElseGet(() -> {
                    UserEntity newUser = new UserEntity();
                    newUser.setEmail(email);
                    String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
                    newUser.setDisplayName(localPart);
                    newUser.setStatus(UserStatus.ACTIVE);
                    newUser.setOnboardingStatus(OnboardingStatus.PROFILE_REQUIRED);
                    newUser.setTermsAcceptedAt(now);
                    newUser.setPrivacyAcceptedAt(now);
                    newUser.getRoles().add(UserRole.USER);
                    return userRepository.saveAndFlush(newUser);
                });

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE, "Tài khoản của bạn đang bị khóa hoặc vô hiệu hóa.");
        }

        return authService.issueSession(user, request.deviceId());
    }
}

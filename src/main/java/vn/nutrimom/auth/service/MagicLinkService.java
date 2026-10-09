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
                                "Vui lòng chờ ít giây trước khi yêu cầu mã xác thực mới.");
                    }
                    previous.setStatus(MagicLoginTokenStatus.EXPIRED);
                    tokenRepository.saveAndFlush(previous);
                });

        // Tạo mã xác thực 6 chữ số ngẫu nhiên (không trùng token đang chờ)
        String rawCode;
        String tokenHash;
        do {
            int codeNum = secureRandom.nextInt(1_000_000);
            rawCode = String.format("%06d", codeNum);
            tokenHash = tokenService.hash(rawCode);
        } while (tokenRepository.findByTokenHash(tokenHash).isPresent());

        // Lưu bản ghi token dạng hash SHA-256
        MagicLoginTokenEntity tokenEntity = new MagicLoginTokenEntity();
        tokenEntity.setEmail(email);
        tokenEntity.setTokenHash(tokenHash);
        tokenEntity.setStatus(MagicLoginTokenStatus.PENDING);
        tokenEntity.setDeviceId(request.deviceId());
        tokenEntity.setExpiresAt(now.plusMinutes(10));
        tokenRepository.saveAndFlush(tokenEntity);

        // Tạo đường link đăng nhập dự phòng nếu người dùng muốn mở trực tiếp
        String loginUrl = baseUrl + "?token=" + rawCode;
        // Cố ý KHÔNG log rawCode lẫn email đầy đủ: mã này còn hiệu lực 10 phút và mở thẳng được
        // tài khoản, nên ghi nó ra log là biến mọi người đọc được log thành người đăng nhập được.
        log.info("magic_link_otp_issued email={} ttl_minutes=10", maskEmail(email));

        // Gửi email chứa mã xác thực 6 chữ số
        emailService.sendEmailOtp(email, rawCode, loginUrl);

        return new MagicLinkResponse(
                true,
                "Mã xác thực 6 chữ số đã được gửi tới " + email + ". Vui lòng kiểm tra hộp thư của bạn.",
                loginUrl,
                rawCode
        );
    }

    @Transactional
    public AuthResponse verifyMagicLink(MagicLinkVerifyRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String codeOrToken = request.resolveCodeOrToken();
        if (codeOrToken == null || codeOrToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Vui lòng cung cấp mã xác thực hợp lệ.");
        }

        String tokenHash = tokenService.hash(codeOrToken);

        MagicLoginTokenEntity tokenEntity = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                        "Mã xác thực không hợp lệ hoặc đã hết hạn."));

        if (!tokenEntity.isUsableAt(now)) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                    "Mã xác thực đã hết hạn hoặc đã được sử dụng. Vui lòng yêu cầu mã mới.");
        }

        if (request.email() != null && !request.email().isBlank()
                && !tokenEntity.getEmail().equalsIgnoreCase(request.email().trim())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                    "Mã xác thực không khớp với email yêu cầu.");
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
                    newUser.setEmailVerifiedAt(now);
                    newUser.setOnboardingStatus(OnboardingStatus.PROFILE_REQUIRED);
                    newUser.setTermsAcceptedAt(now);
                    newUser.setPrivacyAcceptedAt(now);
                    newUser.getRoles().add(UserRole.USER);
                    return userRepository.saveAndFlush(newUser);
                });

        if (user.getStatus() == UserStatus.PENDING_ACTIVATION) {
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(now);
            user.setEmailActivationTokenHash(null);
            user.setEmailActivationExpiresAt(null);
            userRepository.saveAndFlush(user);
        } else if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE, "Tài khoản của bạn đang bị khóa hoặc vô hiệu hóa.");
        }

        return authService.issueSession(user, request.deviceId());
    }

    /** Giữ ký tự đầu và domain: đủ để đối chiếu khi hỗ trợ, không chép nguyên định danh vào log. */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}

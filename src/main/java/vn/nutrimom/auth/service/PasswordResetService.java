package vn.nutrimom.auth.service;

import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.nutrimom.auth.domain.PasswordResetTokenEntity;
import vn.nutrimom.auth.domain.PasswordResetTokenStatus;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.dto.PasswordResetDtos.ChangePasswordRequest;
import vn.nutrimom.auth.dto.PasswordResetDtos.ForgotPasswordRequest;
import vn.nutrimom.auth.dto.PasswordResetDtos.ForgotPasswordResponse;
import vn.nutrimom.auth.dto.PasswordResetDtos.ResetPasswordRequest;
import vn.nutrimom.auth.dto.PasswordResetDtos.ResetPasswordResponse;
import vn.nutrimom.auth.dto.PasswordResetDtos.ValidateResetTokenRequest;
import vn.nutrimom.auth.dto.PasswordResetDtos.ValidateResetTokenResponse;
import vn.nutrimom.auth.repository.PasswordResetTokenRepository;
import vn.nutrimom.auth.repository.RefreshTokenRepository;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.common.email.EmailService;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.config.FrontendProperties;
import vn.nutrimom.config.PasswordResetProperties;

/**
 * Quên mật khẩu qua email và đổi mật khẩu khi đang đăng nhập.
 *
 * <p>Ba điều cố ý:</p>
 * <ul>
 *   <li>{@link #requestReset} trả <strong>cùng một câu</strong> cho mọi email, và gửi thư ở luồng
 *   riêng sau khi commit. Nếu gửi thư ngay trong request, email có thật sẽ chậm hơn hẳn email không
 *   tồn tại, và chỉ cần đo thời gian là dò được ai đã đăng ký.</li>
 *   <li>Token là 32 byte ngẫu nhiên, DB chỉ giữ SHA-256. Không log token, không log email đầy đủ.</li>
 *   <li>Đặt lại thành công thì huỷ mọi refresh token: ai đang giữ phiên cũ (có thể chính là kẻ
 *   đã đoán được mật khẩu) bị đăng xuất. Access token còn sống tối đa {@code access-token-ttl}.</li>
 * </ul>
 */
@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final String GENERIC_MESSAGE =
            "Nếu email đã được đăng ký, chúng tôi đã gửi hướng dẫn đặt lại mật khẩu. Vui lòng kiểm tra hộp thư.";
    private static final int TOKEN_BYTES = 32;

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final EmailService emailService;
    private final FrontendProperties frontend;
    private final PasswordResetProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    /** Một luồng là đủ: thư quên mật khẩu hiếm, và gửi tuần tự giữ được thứ tự cooldown. */
    private final ExecutorService mailExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "password-reset-mail");
        thread.setDaemon(true);
        return thread;
    });

    public PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens,
                                RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                                TokenService tokenService, EmailService emailService,
                                FrontendProperties frontend, PasswordResetProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.emailService = emailService;
        this.frontend = frontend;
        this.properties = properties;
    }

    @PreDestroy
    void shutdown() {
        mailExecutor.shutdown();
    }

    @Transactional
    public ForgotPasswordResponse requestReset(ForgotPasswordRequest request, String remoteAddress) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Optional<UserEntity> found = users.findByEmailIgnoreCase(email)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE
                        || user.getStatus() == UserStatus.PENDING_ACTIVATION);
        if (found.isEmpty()) {
            log.info("password_reset_ignored reason=no_eligible_account email={}", maskEmail(email));
            return accepted();
        }
        UserEntity user = found.get();
        if (throttled(user.getId(), now)) {
            log.info("password_reset_ignored reason=throttled user={}", user.getId());
            return accepted();
        }

        tokens.revokePending(user.getId(), PasswordResetTokenStatus.PENDING, PasswordResetTokenStatus.REVOKED);
        String rawToken = newRawToken();
        PasswordResetTokenEntity token = new PasswordResetTokenEntity();
        token.setUserId(user.getId());
        token.setTokenHash(tokenService.hash(rawToken));
        token.setStatus(PasswordResetTokenStatus.PENDING);
        token.setExpiresAt(now.plus(properties.getTtl()));
        token.setRequestIpHash(remoteAddress == null ? null : tokenService.hash(remoteAddress));
        tokens.saveAndFlush(token);
        log.info("password_reset_issued user={} ttl_minutes={}", user.getId(), properties.getTtl().toMinutes());

        String to = user.getEmail();
        String name = user.getDisplayName();
        String link = frontend.resetPasswordUrl(rawToken);
        long ttlMinutes = properties.getTtl().toMinutes();
        afterCommit(() -> emailService.sendPasswordReset(to, name, link, ttlMinutes));
        return accepted();
    }

    @Transactional(readOnly = true)
    public ValidateResetTokenResponse validate(ValidateResetTokenRequest request) {
        PasswordResetTokenEntity token = tokens.findByTokenHash(tokenService.hash(request.token().trim()))
                .orElseThrow(this::invalidToken);
        UserEntity user = usableToken(token, OffsetDateTime.now(ZoneOffset.UTC));
        return new ValidateResetTokenResponse(true, token.getExpiresAt(), maskEmail(user.getEmail()));
    }

    @Transactional
    public ResetPasswordResponse reset(ResetPasswordRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // Khoá dòng token: hai request đặt lại cùng lúc bằng một link thì chỉ một cái thắng.
        PasswordResetTokenEntity token = tokens.findByTokenHashForUpdate(tokenService.hash(request.token().trim()))
                .orElseThrow(this::invalidToken);
        usableToken(token, now);
        UserEntity user = users.findByIdForUpdate(token.getUserId()).orElseThrow(this::invalidToken);

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        if (user.getStatus() == UserStatus.PENDING_ACTIVATION) {
            // Mở được link trong hộp thư là đã chứng minh sở hữu email, như magic link đang làm.
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(now);
            user.setEmailActivationTokenHash(null);
            user.setEmailActivationExpiresAt(null);
        }
        users.saveAndFlush(user);

        token.setStatus(PasswordResetTokenStatus.USED);
        token.setUsedAt(now);
        tokens.saveAndFlush(token);
        tokens.revokePending(user.getId(), PasswordResetTokenStatus.PENDING, PasswordResetTokenStatus.REVOKED);
        refreshTokens.revokeAllActiveByUserId(user.getId(), now);
        log.info("password_reset_completed user={}", user.getId());

        notifyPasswordChanged(user);
        return new ResetPasswordResponse(true, "Đặt lại mật khẩu thành công. Vui lòng đăng nhập bằng mật khẩu mới.");
    }

    /**
     * Đổi mật khẩu khi đang đăng nhập. Bắt buộc nhập đúng mật khẩu hiện tại: chỉ có access token
     * (ví dụ máy bị mượn) thì không đủ để chiếm tài khoản.
     *
     * <p>Tài khoản tạo bằng magic link chưa có mật khẩu: không cho đặt ở đây (vì không có gì để xác
     * nhận), mà hướng sang quên mật khẩu — luồng đó chứng minh sở hữu email.</p>
     */
    @Transactional
    public void changePassword(String userId, ChangePasswordRequest request) {
        UserEntity user = users.findByIdForUpdate(userId)
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_UNAVAILABLE));
        if (user.getPasswordHash() == null) {
            throw new BusinessException(ErrorCode.PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_INCORRECT);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Mật khẩu mới phải khác mật khẩu hiện tại.",
                    java.util.Map.of("new_password", "Mật khẩu mới phải khác mật khẩu hiện tại."));
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        users.saveAndFlush(user);
        tokens.revokePending(user.getId(), PasswordResetTokenStatus.PENDING, PasswordResetTokenStatus.REVOKED);
        refreshTokens.revokeAllActiveByUserId(user.getId(), now);
        log.info("password_changed user={}", user.getId());
        notifyPasswordChanged(user);
    }

    private UserEntity usableToken(PasswordResetTokenEntity token, OffsetDateTime now) {
        if (token.getStatus() != PasswordResetTokenStatus.PENDING) throw invalidToken();
        if (token.isExpiredAt(now)) throw new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
        return users.findById(token.getUserId())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE
                        || user.getStatus() == UserStatus.PENDING_ACTIVATION)
                .orElseThrow(this::invalidToken);
    }

    private boolean throttled(String userId, OffsetDateTime now) {
        boolean tooSoon = tokens.findTopByUserIdOrderByCreatedAtDesc(userId)
                .map(last -> last.getCreatedAt().plus(properties.getCooldown()).isAfter(now))
                .orElse(false);
        return tooSoon || tokens.countByUserIdAndCreatedAtAfter(userId, now.minusHours(1)) >= properties.getMaxPerHour();
    }

    private void notifyPasswordChanged(UserEntity user) {
        if (user.getEmail() == null) return;
        String to = user.getEmail();
        String name = user.getDisplayName();
        afterCommit(() -> emailService.sendPasswordChanged(to, name));
    }

    /** Gửi thư sau khi commit, ở luồng riêng: rollback thì không có thư, và request không phải chờ SMTP. */
    private void afterCommit(Runnable mail) {
        Runnable safe = () -> {
            try {
                mail.run();
            } catch (RuntimeException ex) {
                log.warn("password_mail_failed error={}", ex.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mailExecutor.execute(safe);
                }
            });
        } else {
            mailExecutor.execute(safe);
        }
    }

    private String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ForgotPasswordResponse accepted() {
        return new ForgotPasswordResponse(true, GENERIC_MESSAGE);
    }

    private BusinessException invalidToken() {
        return new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}

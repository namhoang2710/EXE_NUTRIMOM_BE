package vn.nutrimom.user.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.UserEntity;
import vn.nutrimom.auth.domain.UserStatus;
import vn.nutrimom.auth.repository.RefreshTokenRepository;
import vn.nutrimom.auth.repository.UserRepository;
import vn.nutrimom.auth.service.OtpService;
import vn.nutrimom.auth.service.OtpVerificationException;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.user.dto.DeleteAccountRequest;
import vn.nutrimom.user.dto.DeleteAccountResponse;

@Service
public class UserAccountService {
    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    public UserAccountService(UserRepository users, RefreshTokenRepository refreshTokens,
                              PasswordEncoder passwordEncoder, OtpService otpService) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.otpService = otpService;
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public DeleteAccountResponse deleteAccount(String userId, DeleteAccountRequest request) {
        UserEntity user = users.findByIdForUpdate(userId)
                .filter(value -> value.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED,
                        "The authenticated account is unavailable."));

        reauthenticate(user, request);
        OffsetDateTime disabledAt = OffsetDateTime.now(ZoneOffset.UTC);
        String deletionRequestId = UUID.randomUUID().toString();
        refreshTokens.revokeAllActiveByUserId(userId, disabledAt);
        user.setStatus(UserStatus.DISABLED);
        users.saveAndFlush(user);
        log.info("Account disabled: deletionRequestId={}, userId={}, reason={}",
                deletionRequestId, userId, request.reason().trim());
        return new DeleteAccountResponse(
                deletionRequestId, null, disabledAt, UserStatus.DISABLED.name());
    }

    private void reauthenticate(UserEntity user, DeleteAccountRequest request) {
        if (request.password() != null && !request.password().isBlank()
                && user.getPasswordHash() != null
                && passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return;
        }
        boolean hasOtp = request.otpChallengeId() != null && !request.otpChallengeId().isBlank()
                && request.otpCode() != null && !request.otpCode().isBlank();
        if (hasOtp) {
            otpService.verifyReauthentication(
                    user, request.otpChallengeId().trim(), request.otpCode());
            return;
        }
        ErrorCode code = request.password() == null || request.password().isBlank()
                ? ErrorCode.REAUTHENTICATION_REQUIRED : ErrorCode.INVALID_REAUTHENTICATION;
        throw new BusinessException(code,
                "A valid password or login OTP is required to delete the account.");
    }
}

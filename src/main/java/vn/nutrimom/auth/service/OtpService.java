package vn.nutrimom.auth.service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.auth.domain.*;
import vn.nutrimom.auth.dto.*;
import vn.nutrimom.auth.repository.*;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.config.OtpProperties;

@Service
public class OtpService {
    private final OtpChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final PhoneNormalizer phoneNormalizer;
    private final OtpDeliveryGateway deliveryGateway;
    private final OtpProperties properties;
    private final AuthService authService;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(OtpChallengeRepository challenges, UserRepository users,
                      PhoneNormalizer phoneNormalizer, OtpDeliveryGateway deliveryGateway,
                      OtpProperties properties, AuthService authService) {
        this.challengeRepository = challenges;
        this.userRepository = users;
        this.phoneNormalizer = phoneNormalizer;
        this.deliveryGateway = deliveryGateway;
        this.properties = properties;
        this.authService = authService;
    }

    @PostConstruct
    void validateConfiguration() {
        if (properties.getCodeLength() != 6)
            throw new IllegalStateException("app.otp.code-length must be 6 for the v1 API contract");
        if (properties.getMaxAttempts() < 1 || properties.getMaxAttempts() > 10)
            throw new IllegalStateException("app.otp.max-attempts must be between 1 and 10");
        if (properties.getHmacSecret() == null
                || properties.getHmacSecret().getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("NUTRIMOM_OTP_HMAC_SECRET must contain at least 32 UTF-8 bytes");
    }

    @Transactional
    public OtpChallengeResponse request(OtpChallengeRequest request, String remoteAddress) {
        String phone = phoneNormalizer.normalizeVietnamesePhone(request.phone());
        boolean accountExists = userRepository.existsByPhone(phone);
        if (request.purpose() == OtpPurpose.LOGIN && !accountExists)
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND, "Không tìm thấy tài khoản với số điện thoại này.");
        if (request.purpose() == OtpPurpose.REGISTER && accountExists)
            throw new BusinessException(ErrorCode.PHONE_ALREADY_EXISTS, "Số điện thoại này đã được đăng ký.");
        if (request.purpose() == OtpPurpose.REGISTER && !Boolean.TRUE.equals(request.acceptedTerms()))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TERMS_NOT_ACCEPTED",
                    "Bạn cần đồng ý Điều khoản sử dụng và Chính sách bảo mật.");

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        challengeRepository.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone, request.purpose())
                .filter(previous -> previous.getStatus() == OtpStatus.PENDING)
                .ifPresent(previous -> {
                    if (previous.getResendAvailableAt().isAfter(now))
                        throw new BusinessException(ErrorCode.OTP_RESEND_TOO_SOON, "Vui lòng chờ trước khi yêu cầu mã OTP mới.");
                    previous.setStatus(OtpStatus.SUPERSEDED);
                    challengeRepository.saveAndFlush(previous);
                });

        String rawCode = generateCode();
        String deliveryChannel = deliveryGateway.deliver(phone, rawCode);
        OtpChallengeEntity challenge = new OtpChallengeEntity();
        challenge.setPhone(phone);
        challenge.setPurpose(request.purpose());
        challenge.setCodeHash(hmac(phone + ":" + rawCode));
        challenge.setStatus(OtpStatus.PENDING);
        challenge.setAttempts(0);
        challenge.setMaxAttempts(properties.getMaxAttempts());
        challenge.setDeviceId(normalizeOptional(request.deviceId()));
        challenge.setRequestIpHash(remoteAddress == null ? null : hmac(remoteAddress));
        challenge.setTermsAccepted(Boolean.TRUE.equals(request.acceptedTerms()));
        challenge.setExpiresAt(now.plus(properties.getTtl()));
        challenge.setResendAvailableAt(now.plus(properties.getResendCooldown()));
        try {
            challengeRepository.saveAndFlush(challenge);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.OTP_REQUEST_IN_PROGRESS, "Một yêu cầu OTP khác đang được xử lý. Vui lòng thử lại sau.");
        }

        return new OtpChallengeResponse(challenge.getId(), maskPhone(phone), deliveryChannel,
                properties.getTtl().toSeconds(), properties.getResendCooldown().toSeconds(),
                properties.isExposeDebugCode() ? rawCode : null);
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public OtpVerifyResponse verify(OtpVerifyRequest request) {
        OtpChallengeEntity challenge = challengeRepository.findByIdForUpdate(request.challengeId())
                .orElseThrow(() -> invalid(ErrorCode.OTP_CHALLENGE_NOT_FOUND, "Yêu cầu OTP không tồn tại."));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (challenge.getStatus() != OtpStatus.PENDING)
            throw invalid(ErrorCode.OTP_CHALLENGE_USED, "Yêu cầu OTP đã hết hiệu lực.");
        if (!challenge.getExpiresAt().isAfter(now)) {
            challenge.setStatus(OtpStatus.EXPIRED);
            challengeRepository.save(challenge);
            throw invalid(ErrorCode.OTP_EXPIRED, "Mã OTP đã hết hạn.");
        }
        if (!constantTimeEquals(challenge.getCodeHash(),
                hmac(challenge.getPhone() + ":" + request.code()))) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
                challenge.setStatus(OtpStatus.EXPIRED);
                challengeRepository.save(challenge);
                throw invalid(ErrorCode.OTP_ATTEMPTS_EXCEEDED,
                        "Bạn đã nhập sai OTP quá số lần cho phép. Vui lòng yêu cầu mã mới.");
            }
            challengeRepository.save(challenge);
            throw invalid(ErrorCode.INVALID_OTP, "Mã OTP không chính xác.");
        }
        if (challenge.getDeviceId() != null
                && (request.deviceId() == null
                || request.deviceId().isBlank()
                || !challenge.getDeviceId().equals(request.deviceId().trim())))
            throw invalid(ErrorCode.OTP_DEVICE_MISMATCH, "Mã OTP không thuộc thiết bị này.");

        boolean newUser = challenge.getPurpose() == OtpPurpose.REGISTER;
        UserEntity user;
        if (newUser) {
            if (userRepository.existsByPhone(challenge.getPhone()))
                throw new BusinessException(ErrorCode.PHONE_ALREADY_EXISTS, "Số điện thoại này đã được đăng ký.");
            user = new UserEntity();
            user.setPhone(challenge.getPhone());
            user.setDisplayName(normalizeDisplayName(request.displayName()));
            user.setStatus(UserStatus.ACTIVE);
            user.setRoles(Set.of(UserRole.USER));
            user.setTermsAcceptedAt(now);
            user.setPrivacyAcceptedAt(now);
            userRepository.saveAndFlush(user);
        } else {
            user = userRepository.findByPhone(challenge.getPhone())
                    .filter(value -> value.getStatus() == UserStatus.ACTIVE)
                    .orElseThrow(() -> invalid(ErrorCode.ACCOUNT_UNAVAILABLE,
                            "Tài khoản không tồn tại hoặc đã bị khóa."));
        }

        challenge.setStatus(OtpStatus.VERIFIED);
        challenge.setVerifiedAt(now);
        challengeRepository.save(challenge);
        return new OtpVerifyResponse(newUser, authService.issueSession(user, request.deviceId()));
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public void verifyReauthentication(UserEntity user, String challengeId, String code) {
        OtpChallengeEntity challenge = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> invalid(ErrorCode.OTP_CHALLENGE_NOT_FOUND, "OTP challenge was not found."));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (challenge.getPurpose() != OtpPurpose.LOGIN
                || !challenge.getPhone().equals(user.getPhone())) {
            throw invalid(ErrorCode.OTP_REAUTHENTICATION_MISMATCH,
                    "OTP challenge does not belong to the authenticated account.");
        }
        if (challenge.getStatus() != OtpStatus.PENDING) {
            throw invalid(ErrorCode.OTP_CHALLENGE_USED, "OTP challenge is no longer valid.");
        }
        if (!challenge.getExpiresAt().isAfter(now)) {
            challenge.setStatus(OtpStatus.EXPIRED);
            challengeRepository.save(challenge);
            throw invalid(ErrorCode.OTP_EXPIRED, "OTP code has expired.");
        }
        if (!constantTimeEquals(challenge.getCodeHash(),
                hmac(challenge.getPhone() + ":" + code))) {
            challenge.setAttempts(challenge.getAttempts() + 1);
            if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
                challenge.setStatus(OtpStatus.EXPIRED);
            }
            challengeRepository.save(challenge);
            throw invalid(ErrorCode.INVALID_OTP, "OTP code is invalid.");
        }
        challenge.setStatus(OtpStatus.VERIFIED);
        challenge.setVerifiedAt(now);
        challengeRepository.save(challenge);
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, properties.getCodeLength());
        return String.format("%0" + properties.getCodeLength() + "d", secureRandom.nextInt(bound));
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }
    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "******" + phone.substring(phone.length() - 3);
    }
    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private String normalizeDisplayName(String value) {
        return value == null || value.isBlank() ? "Thành viên NutriMom" : value.trim();
    }
    private OtpVerificationException invalid(ErrorCode code, String message) {
        return new OtpVerificationException(code, message);
    }
}

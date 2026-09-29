package vn.nutrimom.notification.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import vn.nutrimom.config.NotificationProperties;

/**
 * Mã hoá push token trước khi ghi DB (spec mục 18 "token được mã hóa", mục 21 "cột nhạy cảm mã hóa
 * ứng dụng/KMS khi cần").
 *
 * <p>Dùng AES-256-GCM: mỗi lần mã hoá sinh IV 12 byte ngẫu nhiên, ghép {@code IV || ciphertext||tag}
 * rồi base64. Vì vậy cùng một token mã hoá hai lần ra hai chuỗi khác nhau — không so sánh trực tiếp
 * được, nên kèm {@link #hash(String)} (SHA-256 hex, tất định) làm khoá tra cứu.</p>
 *
 * <p>Không dùng hash một chiều thay cho mã hoá vì vẫn phải lấy lại token thô để gửi push.</p>
 */
@Component
public class PushTokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public PushTokenCipher(NotificationProperties properties) {
        this.key = new SecretKeySpec(decodeKey(properties.getPushTokenKey()), "AES");
    }

    /** Trả về base64 của {@code IV || ciphertext}; dùng để lưu vào {@code push_token_cipher}. */
    public String encrypt(String plainToken) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainToken.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            // Không đưa token vào message/exception để tránh lọt ra log.
            throw new IllegalStateException("Failed to encrypt push token", ex);
        }
    }

    /** Giải mã chuỗi do {@link #encrypt(String)} sinh ra, để gửi push. */
    public String decrypt(String encryptedToken) {
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedToken);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH));
            byte[] decrypted = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt push token", ex);
        }
    }

    /** SHA-256 hex tất định của token thô — khoá tra cứu/khử trùng, không phải để xác thực. */
    public String hash(String plainToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(plainToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Fail-fast lúc khởi tạo bean, giống cách {@code SecurityConfig.jwtSecretKey()} kiểm JWT secret. */
    private static byte[] decodeKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "NUTRIMOM_PUSH_TOKEN_KEY must be set to a base64-encoded 32-byte key");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("NUTRIMOM_PUSH_TOKEN_KEY must be valid base64", ex);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "NUTRIMOM_PUSH_TOKEN_KEY must decode to exactly 32 bytes (AES-256)");
        }
        return decoded;
    }
}

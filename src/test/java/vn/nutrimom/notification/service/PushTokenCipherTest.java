package vn.nutrimom.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import vn.nutrimom.config.NotificationProperties;

/**
 * Khoá hợp đồng của lớp mã hoá push token (spec mục 18 "token được mã hóa").
 *
 * <p>Ba tính chất phải giữ: giải mã được (vì còn phải gửi push), ciphertext không tất định (IV ngẫu
 * nhiên, nên không suy ra được hai user dùng chung token từ việc nhìn cột cipher), và hash thì tất
 * định (vì đó là khoá tra cứu).</p>
 */
class PushTokenCipherTest {

    private static final String TOKEN = "fcm-token-abcdefghijklmnopqrstuvwxyz-0123456789";

    private final PushTokenCipher cipher = new PushTokenCipher(properties(key32()));

    @Test
    void encryptThenDecryptReturnsTheOriginalToken() {
        assertThat(cipher.decrypt(cipher.encrypt(TOKEN))).isEqualTo(TOKEN);
    }

    @Test
    void encryptingTheSameTokenTwiceGivesDifferentCiphertext() {
        String first = cipher.encrypt(TOKEN);
        String second = cipher.encrypt(TOKEN);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second)).isEqualTo(TOKEN);
    }

    @Test
    void ciphertextDoesNotContainThePlainToken() {
        assertThat(cipher.encrypt(TOKEN)).doesNotContain(TOKEN);
    }

    @Test
    void hashIsStableForTheSameTokenAndDiffersForAnother() {
        assertThat(cipher.hash(TOKEN)).isEqualTo(cipher.hash(TOKEN)).hasSize(64);
        assertThat(cipher.hash(TOKEN)).isNotEqualTo(cipher.hash(TOKEN + "x"));
    }

    @Test
    void missingOrWrongSizedKeyFailsFastAtStartup() {
        assertThatThrownBy(() -> new PushTokenCipher(properties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NUTRIMOM_PUSH_TOKEN_KEY");

        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new PushTokenCipher(properties(shortKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");

        assertThatThrownBy(() -> new PushTokenCipher(properties("not base64 !!!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    private static String key32() {
        return Base64.getEncoder().encodeToString("nutrimom-unit-test-push-key-32by".getBytes());
    }

    private static NotificationProperties properties(String key) {
        NotificationProperties properties = new NotificationProperties();
        properties.setPushTokenKey(key);
        return properties;
    }
}

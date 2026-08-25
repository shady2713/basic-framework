package com.basicframework.framework.security.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.security.config.SecurityProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    @Test
    void encrypt_usesRandomIvAndBindsCiphertextToContext() {
        CredentialCipher cipher = cipher();

        String first = cipher.encrypt("TOP-SECRET", "sms-channel");
        String second = cipher.encrypt("TOP-SECRET", "sms-channel");

        assertThat(first).isNotEqualTo(second).doesNotContain("TOP-SECRET");
        assertThat(cipher.decrypt(first, "sms-channel")).isEqualTo("TOP-SECRET");
        assertThatThrownBy(() -> cipher.decrypt(first, "file-config"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("凭据解密失败");
    }

    @Test
    void decrypt_rejectsUnknownVersionWithoutEchoingCiphertext() {
        CredentialCipher cipher = cipher();

        assertThatThrownBy(() -> cipher.decrypt("v2.secret.value", "sms-channel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的凭据密文版本")
                .hasMessageNotContaining("secret");
    }

    @Test
    void keyedDigest_isStableAndContextSeparated() {
        CredentialCipher cipher = cipher();

        String digest = cipher.keyedDigest("RECOVERY-CODE", "mfa:recovery-code");

        assertThat(digest).isEqualTo(cipher.keyedDigest("RECOVERY-CODE", "mfa:recovery-code"));
        assertThat(digest)
                .isNotEqualTo(cipher.keyedDigest("RECOVERY-CODE", "another-context"))
                .doesNotContain("RECOVERY-CODE");
    }

    @Test
    void operation_withoutKey_failsClosed() {
        CredentialCipher cipher = new CredentialCipher(new SecurityProperties());

        assertThatThrownBy(() -> cipher.encrypt("secret", "sms-channel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("凭据主密钥未配置");
    }

    private static CredentialCipher cipher() {
        SecurityProperties properties = new SecurityProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new CredentialCipher(properties);
    }
}

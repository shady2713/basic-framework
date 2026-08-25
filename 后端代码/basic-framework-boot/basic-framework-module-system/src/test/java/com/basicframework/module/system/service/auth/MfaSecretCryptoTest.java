package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.security.config.SecurityProperties;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.system.config.MfaProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MfaSecretCryptoTest {

    @Test
    void encrypt_usesRandomIvAndBindsCiphertextToUser() {
        MfaSecretCrypto crypto = crypto();

        String first = crypto.encrypt("TOP-SECRET", 1L);
        String second = crypto.encrypt("TOP-SECRET", 1L);

        assertThat(first).isNotEqualTo(second).doesNotContain("TOP-SECRET");
        assertThat(crypto.decrypt(first, 1L)).isEqualTo("TOP-SECRET");
        assertThatThrownBy(() -> crypto.decrypt(first, 2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recoveryCodeHash_isStableWithoutExposingPlaintext() {
        MfaSecretCrypto crypto = crypto();

        String hash = crypto.recoveryCodeHash("ABCDEFGHIJKLMNOP");

        assertThat(hash).isEqualTo(crypto.recoveryCodeHash("ABCDEFGHIJKLMNOP"));
        assertThat(hash).doesNotContain("ABCDEFGHIJKLMNOP");
    }

    private static MfaSecretCrypto crypto() {
        MfaProperties properties = new MfaProperties();
        properties.setEnabled(true);
        SecurityProperties securityProperties = new SecurityProperties();
        securityProperties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new MfaSecretCrypto(properties, new CredentialCipher(securityProperties));
    }
}

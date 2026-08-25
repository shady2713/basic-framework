package com.basicframework.module.system.service.auth;

import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.system.config.MfaProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/** MFA 密钥加密与恢复码摘要。 */
@Component
public class MfaSecretCrypto {

    private static final String RECOVERY_CODE_CONTEXT = "mfa:recovery-code";

    private final CredentialCipher credentialCipher;

    public MfaSecretCrypto(MfaProperties properties, CredentialCipher credentialCipher) {
        this.credentialCipher = credentialCipher;
        if (properties.isEnabled()) {
            credentialCipher.requireConfigured();
        }
    }

    public String encrypt(String plaintext, Long userId) {
        return credentialCipher.encrypt(plaintext, context(userId));
    }

    public String decrypt(String value, Long userId) {
        return credentialCipher.decrypt(value, context(userId));
    }

    public String recoveryCodeHash(String normalizedCode) {
        return credentialCipher.keyedDigest(normalizedCode, RECOVERY_CODE_CONTEXT);
    }

    static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String context(Long userId) {
        return "mfa:user:" + userId;
    }
}

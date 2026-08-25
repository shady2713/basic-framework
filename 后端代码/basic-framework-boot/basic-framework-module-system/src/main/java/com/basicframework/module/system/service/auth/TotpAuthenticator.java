package com.basicframework.module.system.service.auth;

import cn.hutool.core.codec.Base32;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** RFC 6238 TOTP（HMAC-SHA1、30 秒、6 位），允许前后一个时间窗口。 */
@Component
public class TotpAuthenticator {

    private static final String HMAC_SHA_1 = "HmacSHA1";
    private static final int SECRET_BYTES = 20;
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS_MODULUS = 1_000_000;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public TotpAuthenticator() {
        this(Clock.systemUTC());
    }

    TotpAuthenticator(Clock clock) {
        this.clock = clock;
    }

    public String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return Base32.encode(secret);
    }

    public OptionalLong verify(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        long currentStep = clock.instant().getEpochSecond() / PERIOD_SECONDS;
        for (long step = currentStep - 1; step <= currentStep + 1; step++) {
            if (MfaSecretCrypto.constantTimeEquals(generateCode(base32Secret, step), code)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    String generateCode(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_1);
            mac.init(new SecretKeySpec(Base32.decode(base32Secret), HMAC_SHA_1));
            byte[] hash =
                    mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % DIGITS_MODULUS);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("TOTP 计算失败", exception);
        }
    }
}

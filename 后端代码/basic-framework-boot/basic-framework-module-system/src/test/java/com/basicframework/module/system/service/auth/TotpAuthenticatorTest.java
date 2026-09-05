package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;

import cn.hutool.core.codec.Base32;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TotpAuthenticatorTest {

    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void generateCode_matchesRfc6238TruncatedSixDigitVector() {
        TotpAuthenticator authenticator = new TotpAuthenticator(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

        assertThat(authenticator.generateCode(RFC_SECRET, 1)).isEqualTo("287082");
        assertThat(authenticator.verify(RFC_SECRET, "287082")).hasValue(1L);
    }

    @Test
    void verify_rejectsMalformedCode() {
        TotpAuthenticator authenticator = new TotpAuthenticator(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

        assertThat(authenticator.verify(RFC_SECRET, "12345")).isEmpty();
        assertThat(authenticator.verify(RFC_SECRET, "12A456")).isEmpty();
        assertThat(authenticator.verify(RFC_SECRET, null)).isEmpty();
    }

    @Test
    void generateSecret_hasRequiredEntropyLength() {
        TotpAuthenticator authenticator = new TotpAuthenticator(Clock.systemUTC());

        String secret = authenticator.generateSecret();

        assertThat(Base32.decode(secret)).hasSize(20);
    }

    @Test
    void verify_acceptsOnlyAdjacentTimeWindows() {
        TotpAuthenticator authenticator = new TotpAuthenticator(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

        assertThat(authenticator.verify(RFC_SECRET, authenticator.generateCode(RFC_SECRET, 0)))
                .hasValue(0L);
        assertThat(authenticator.verify(RFC_SECRET, authenticator.generateCode(RFC_SECRET, 2)))
                .hasValue(2L);
        assertThat(authenticator.verify(RFC_SECRET, authenticator.generateCode(RFC_SECRET, 3)))
                .isEmpty();
    }
}

package com.basicframework.module.system.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/** MFA 配置。TOTP 密钥由 security starter 的统一凭据主密钥保护。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "basic-framework.security.mfa")
public class MfaProperties {

    private static final Duration MIN_CHALLENGE_TTL = Duration.ofSeconds(1);
    private static final Duration MAX_CHALLENGE_TTL = Duration.ofMinutes(10);
    private static final Duration MIN_STEP_UP_TTL = Duration.ofSeconds(1);
    private static final Duration MAX_STEP_UP_TTL = Duration.ofMinutes(15);

    private boolean enabled;

    @NotBlank
    private String issuer = "basic-framework";

    /** 一次性登录、注册或 WebAuthn ceremony 挑战的有效窗口。 */
    @NotNull
    private Duration challengeTtl = Duration.ofMinutes(5);

    /** 已完成 MFA 的 access token 可执行高风险操作的有效窗口。 */
    @NotNull
    private Duration stepUpTtl = Duration.ofMinutes(5);

    @Valid
    @NotNull
    private WebAuthn webauthn = new WebAuthn();

    @AssertTrue(message = "MFA challenge-ttl 必须在 1 秒到 10 分钟之间")
    public boolean isChallengeTtlValid() {
        return isWithin(challengeTtl, MIN_CHALLENGE_TTL, MAX_CHALLENGE_TTL);
    }

    @AssertTrue(message = "MFA step-up-ttl 必须在 1 秒到 15 分钟之间")
    public boolean isStepUpTtlValid() {
        return isWithin(stepUpTtl, MIN_STEP_UP_TTL, MAX_STEP_UP_TTL);
    }

    @AssertTrue(message = "WebAuthn 启用时必须配置合法 RP ID 与精确 HTTPS Origin（localhost 可用 HTTP）")
    public boolean isWebAuthnConfigurationValid() {
        if (!webauthn.enabled) {
            return true;
        }
        if (!enabled || !isValidRpId(webauthn.rpId) || !StringUtils.hasText(webauthn.rpName)) {
            return false;
        }
        return !webauthn.allowedOrigins.isEmpty()
                && webauthn.allowedOrigins.stream().allMatch(origin -> isValidOrigin(origin, webauthn.rpId));
    }

    private static boolean isValidRpId(String rpId) {
        if (!StringUtils.hasText(rpId) || rpId.length() > 253) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(rpId)) {
            return true;
        }
        String[] labels = rpId.split("\\.", -1);
        return labels.length >= 2
                && Arrays.stream(labels)
                        .allMatch(
                                label -> label.length() <= 63 && label.matches("(?i)[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"));
    }

    private static boolean isWithin(Duration value, Duration minimum, Duration maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private static boolean isValidOrigin(String origin, String rpId) {
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            boolean secureScheme = "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && "localhost".equalsIgnoreCase(host));
            boolean hostMatches = host != null
                    && (host.equalsIgnoreCase(rpId) || host.toLowerCase().endsWith("." + rpId.toLowerCase()));
            return secureScheme
                    && hostMatches
                    && uri.getUserInfo() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty())
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Data
    public static class WebAuthn {

        private boolean enabled;

        private String rpId;

        @NotBlank
        private String rpName = "basic-framework";

        private Set<String> allowedOrigins = new LinkedHashSet<>();
    }
}

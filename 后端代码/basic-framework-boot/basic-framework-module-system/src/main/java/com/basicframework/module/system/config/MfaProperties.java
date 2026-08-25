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

    private boolean enabled;

    @NotBlank
    private String issuer = "basic-framework";

    @NotNull
    private Duration challengeTtl = Duration.ofMinutes(5);

    @NotNull
    private Duration stepUpTtl = Duration.ofMinutes(5);

    @Valid
    @NotNull
    private WebAuthn webauthn = new WebAuthn();

    @AssertTrue(message = "MFA step-up-ttl 必须大于 0")
    public boolean isStepUpTtlValid() {
        return stepUpTtl != null && !stepUpTtl.isZero() && !stepUpTtl.isNegative();
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

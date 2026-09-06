package com.basicframework.module.system.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
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

    /** 一次性登录或注册挑战的有效窗口。 */
    @NotNull
    private Duration challengeTtl = Duration.ofMinutes(5);

    /** 已完成 MFA 的 access token 可执行高风险操作的有效窗口。 */
    @NotNull
    private Duration stepUpTtl = Duration.ofMinutes(5);

    @AssertTrue(message = "MFA challenge-ttl 必须在 1 秒到 10 分钟之间")
    public boolean isChallengeTtlValid() {
        return isWithin(challengeTtl, MIN_CHALLENGE_TTL, MAX_CHALLENGE_TTL);
    }

    @AssertTrue(message = "MFA step-up-ttl 必须在 1 秒到 15 分钟之间")
    public boolean isStepUpTtlValid() {
        return isWithin(stepUpTtl, MIN_STEP_UP_TTL, MAX_STEP_UP_TTL);
    }

    private static boolean isWithin(Duration value, Duration minimum, Duration maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}

package com.basicframework.module.system.service.auth.dto;

import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** Redis 中的短期一次性 MFA 挑战。 */
@Data
@ToString(exclude = {"encryptedTotpSecret", "accessTokenHash"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaChallengeDTO {

    private Long userId;
    private String username;
    private Integer loginLogType;
    private MfaChallengePurposeEnum purpose;
    private String encryptedTotpSecret;
    private byte[] webAuthnUserHandle;
    private String webAuthnRequestJson;
    private String accessTokenHash;
    private Long factorId;
}

package com.basicframework.module.system.service.auth.dto;

import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** 登录结果；MFA 完成前绝不包含 access/refresh token。 */
@Data
@ToString(exclude = {"accessToken", "refreshToken", "mfaToken", "recoveryCodes"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthLoginResultDTO {

    private Long userId;
    private String accessToken;
    private String refreshToken;
    private LocalDateTime expiresTime;
    private Boolean mfaRequired;
    private Boolean mfaEnrollmentRequired;
    private String mfaToken;
    private List<String> mfaMethods;
    private List<String> recoveryCodes;

    public static AuthLoginResultDTO token(UserSessionDO token) {
        return AuthLoginResultDTO.builder()
                .userId(token.getUserId())
                .accessToken(token.getAccessToken())
                .refreshToken(token.getRefreshToken())
                .expiresTime(token.getAccessExpiresTime())
                .mfaRequired(false)
                .mfaEnrollmentRequired(false)
                .build();
    }
}

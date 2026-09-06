package com.basicframework.module.system.service.auth;

import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_ALREADY_CONFIGURED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CODE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/** 覆盖登录期首次注册的验证码防御、重复注册并发冲突与既有因子短路。 */
@ExtendWith(MockitoExtension.class)
class MfaRequiredEnrollmentCredentialsTest {

    @Mock
    private MfaFactorMapper factorMapper;

    @Mock
    private MfaSecretCrypto secretCrypto;

    @Mock
    private TotpAuthenticator totpAuthenticator;

    private MfaRequiredEnrollmentCredentials credentials;

    @BeforeEach
    void setUp() {
        credentials = new MfaRequiredEnrollmentCredentials(factorMapper, secretCrypto, totpAuthenticator);
    }

    @Test
    void completeTotp_rejectsInvalidCodesWithoutPersistingTheFactor() {
        when(factorMapper.selectEnabledByUserIdAndType(1L, MfaFactorTypeEnum.TOTP.getType()))
                .thenReturn(null);
        when(secretCrypto.decrypt("ciphertext", 1L)).thenReturn("BASE32SECRET");
        when(totpAuthenticator.verify("BASE32SECRET", "000000")).thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> credentials.completeTotp(enrollmentChallenge(), "000000"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_CODE_INVALID.getCode()));

        verify(factorMapper, never()).insert(any(MfaFactorDO.class));
    }

    @Test
    void completeTotp_mapsConcurrentDuplicateRegistrationToAlreadyConfigured() {
        when(factorMapper.selectEnabledByUserIdAndType(1L, MfaFactorTypeEnum.TOTP.getType()))
                .thenReturn(null);
        when(secretCrypto.decrypt("ciphertext", 1L)).thenReturn("BASE32SECRET");
        when(totpAuthenticator.verify("BASE32SECRET", "123456")).thenReturn(OptionalLong.of(100L));
        when(factorMapper.insert(any(MfaFactorDO.class))).thenThrow(new DuplicateKeyException("duplicate factor"));

        assertThatThrownBy(() -> credentials.completeTotp(enrollmentChallenge(), "123456"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_ALREADY_CONFIGURED.getCode()));
    }

    @Test
    void beginTotp_rejectsUsersThatAlreadyOwnAnEnabledTotpFactor() {
        when(factorMapper.selectEnabledByUserIdAndType(1L, MfaFactorTypeEnum.TOTP.getType()))
                .thenReturn(MfaFactorDO.builder().id(9L).build());

        assertThatThrownBy(() -> credentials.beginTotp(1L))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_ALREADY_CONFIGURED.getCode()));

        verifyNoInteractions(totpAuthenticator, secretCrypto);
    }

    private static MfaChallengeDTO enrollmentChallenge() {
        return MfaChallengeDTO.builder()
                .userId(1L)
                .username("admin")
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(MfaChallengePurposeEnum.REQUIRED_ENROLLMENT)
                .encryptedTotpSecret("ciphertext")
                .build();
    }
}

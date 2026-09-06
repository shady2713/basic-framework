package com.basicframework.module.system.service.auth;

import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_CODE_INVALID;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_NOT_CONFIGURED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.dal.mysql.auth.MfaRecoveryCodeMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 覆盖已注册凭据验证的缺因子防御与恢复码归一化失败分支。 */
@ExtendWith(MockitoExtension.class)
class MfaCredentialVerifierTest {

    @Mock
    private MfaFactorMapper factorMapper;

    @Mock
    private MfaRecoveryCodeMapper recoveryCodeMapper;

    @Mock
    private MfaSecretCrypto secretCrypto;

    @Mock
    private TotpAuthenticator totpAuthenticator;

    private MfaCredentialVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new MfaCredentialVerifier(factorMapper, recoveryCodeMapper, secretCrypto, totpAuthenticator);
    }

    @Test
    void verifyTotp_rejectsUsersWithoutAnEnabledTotpFactor() {
        when(factorMapper.selectEnabledByUserIdAndType(1L, MfaFactorTypeEnum.TOTP.getType()))
                .thenReturn(null);

        assertThatThrownBy(() -> verifier.verifyTotp(1L, "123456"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_NOT_CONFIGURED.getCode()));

        verifyNoInteractions(totpAuthenticator);
    }

    @Test
    void consumeRecoveryCode_rejectsBlankInputBeforeTouchingAnyStore() {
        assertThatThrownBy(() -> verifier.consumeRecoveryCode(1L, "  "))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_CODE_INVALID.getCode()));

        verifyNoInteractions(recoveryCodeMapper, secretCrypto);
    }

    @Test
    void consumeRecoveryCode_rejectsCodesOutsideTheBase32Alphabet() {
        assertThatThrownBy(() -> verifier.consumeRecoveryCode(1L, "ABCD-EFGH-IJKL-MM01"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_CODE_INVALID.getCode()));

        verifyNoInteractions(recoveryCodeMapper, secretCrypto);
    }

    @Test
    void consumeRecoveryCode_normalizesCaseAndSeparatorsBeforeHashing() {
        when(secretCrypto.recoveryCodeHash("ABCD2345EFGH67YZ")).thenReturn("code-hash");
        when(recoveryCodeMapper.consume(eq(1L), eq("code-hash"), any(LocalDateTime.class)))
                .thenReturn(1);

        verifier.consumeRecoveryCode(1L, "abcd-2345-efgh-67yz");

        verify(recoveryCodeMapper).consume(eq(1L), eq("code-hash"), any(LocalDateTime.class));
    }

    @Test
    void consumeRecoveryCode_rejectsCodesAlreadyConsumedOrUnknown() {
        when(secretCrypto.recoveryCodeHash("ABCD2345EFGH67YZ")).thenReturn("code-hash");
        when(recoveryCodeMapper.consume(eq(1L), eq("code-hash"), any(LocalDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> verifier.consumeRecoveryCode(1L, "ABCD-2345-EFGH-67YZ"))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_CODE_INVALID.getCode()));
    }
}

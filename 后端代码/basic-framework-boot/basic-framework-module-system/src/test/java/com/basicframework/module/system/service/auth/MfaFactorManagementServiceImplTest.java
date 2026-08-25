package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.dal.redis.auth.MfaChallengeRedisDAO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import com.basicframework.module.system.service.permission.PermissionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MfaFactorManagementServiceImplTest {

    @InjectMocks
    private MfaFactorManagementServiceImpl service;

    @Mock
    private MfaProperties properties;

    @Mock
    private MfaProperties.WebAuthn webAuthnProperties;

    @Mock
    private MfaChallengeRedisDAO challengeRedisDAO;

    @Mock
    private MfaFactorMapper factorMapper;

    @Mock
    private MfaSecretCrypto secretCrypto;

    @Mock
    private MfaRecoveryCodeManager recoveryCodeManager;

    @Mock
    private TotpAuthenticator totpAuthenticator;

    @Mock
    private WebAuthnService webAuthnService;

    @Mock
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        when(properties.isEnabled()).thenReturn(true);
        org.mockito.Mockito.lenient().when(properties.getWebauthn()).thenReturn(webAuthnProperties);
    }

    @Test
    void getFactors_returnsSafeSummaries() {
        when(factorMapper.selectEnabledByUserId(1L))
                .thenReturn(List.of(
                        factor(10L, MfaFactorTypeEnum.TOTP, "TOTP"),
                        factor(11L, MfaFactorTypeEnum.WEBAUTHN, "1234567890abcdef")));

        assertThat(service.getFactors(1L))
                .extracting("id", "type", "name")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10L, "TOTP", "动态验证码"),
                        org.assertj.core.groups.Tuple.tuple(11L, "WEBAUTHN", "安全密钥 · 90abcdef"));
    }

    @Test
    void completeTotpEnrollment_insertsNewFactorAndRotatesRecoveryCodes() {
        MfaChallengeDTO challenge = managementChallenge(MfaChallengePurposeEnum.TOTP_MANAGEMENT_ENROLLMENT);
        challenge.setEncryptedTotpSecret("ciphertext");
        when(challengeRedisDAO.getAndDelete("enrollment-token")).thenReturn(challenge);
        when(secretCrypto.decrypt("ciphertext", 1L)).thenReturn("BASE32SECRET");
        when(totpAuthenticator.verify("BASE32SECRET", "123456")).thenReturn(OptionalLong.of(100L));
        when(recoveryCodeManager.replace(any(), any())).thenReturn(List.of("ABCD-EFGH-IJKL-MNOP"));

        List<String> codes = service.completeTotpEnrollment(1L, "enrollment-token", "123456");

        assertThat(codes).containsExactly("ABCD-EFGH-IJKL-MNOP");
        ArgumentCaptor<MfaFactorDO> captor = ArgumentCaptor.forClass(MfaFactorDO.class);
        verify(factorMapper).insert(captor.capture());
        assertThat(captor.getValue())
                .extracting(MfaFactorDO::getUserId, MfaFactorDO::getSecretCiphertext, MfaFactorDO::getLastUsedStep)
                .containsExactly(1L, "ciphertext", 100L);
    }

    @Test
    void completeTotpEnrollment_rotatesOnlyFactorCapturedByChallenge() {
        MfaChallengeDTO challenge = managementChallenge(MfaChallengePurposeEnum.TOTP_MANAGEMENT_ENROLLMENT);
        challenge.setEncryptedTotpSecret("new-ciphertext");
        challenge.setFactorId(12L);
        when(challengeRedisDAO.getAndDelete("enrollment-token")).thenReturn(challenge);
        when(secretCrypto.decrypt("new-ciphertext", 1L)).thenReturn("BASE32SECRET");
        when(totpAuthenticator.verify("BASE32SECRET", "123456")).thenReturn(OptionalLong.of(101L));
        when(factorMapper.rotateTotpSecret(
                        org.mockito.ArgumentMatchers.eq(12L),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(MfaFactorTypeEnum.TOTP.getType()),
                        org.mockito.ArgumentMatchers.eq("new-ciphertext"),
                        org.mockito.ArgumentMatchers.eq(101L),
                        any()))
                .thenReturn(1);
        when(recoveryCodeManager.replace(any(), any())).thenReturn(List.of("ABCD-EFGH-IJKL-MNOP"));

        service.completeTotpEnrollment(1L, "enrollment-token", "123456");

        verify(factorMapper)
                .rotateTotpSecret(
                        org.mockito.ArgumentMatchers.eq(12L),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(MfaFactorTypeEnum.TOTP.getType()),
                        org.mockito.ArgumentMatchers.eq("new-ciphertext"),
                        org.mockito.ArgumentMatchers.eq(101L),
                        any());
        verify(factorMapper, never()).insert(any(MfaFactorDO.class));
    }

    @Test
    void completeTotpEnrollment_rejectsChallengeBoundToAnotherUser() {
        MfaChallengeDTO challenge = managementChallenge(MfaChallengePurposeEnum.TOTP_MANAGEMENT_ENROLLMENT);
        when(challengeRedisDAO.getAndDelete("enrollment-token")).thenReturn(challenge);

        assertThatThrownBy(() -> service.completeTotpEnrollment(2L, "enrollment-token", "123456"))
                .hasMessageContaining("MFA 验证已失效");
        verify(secretCrypto, never()).decrypt(anyString(), any());
    }

    @Test
    void beginWebAuthnEnrollment_reusesExistingUserHandle() {
        when(webAuthnProperties.isEnabled()).thenReturn(true);
        byte[] userHandle = new byte[] {4, 5, 6};
        MfaFactorDO existing = factor(11L, MfaFactorTypeEnum.WEBAUTHN, "credential");
        existing.setUserHandle(userHandle);
        when(factorMapper.selectEnabledByUserIdAndTypeList(1L, MfaFactorTypeEnum.WEBAUTHN.getType()))
                .thenReturn(List.of(existing));
        when(webAuthnService.startRegistration(1L, "user", userHandle))
                .thenReturn(new WebAuthnService.CeremonyOptions("browser-json", "server-json"));

        MfaWebAuthnOptionsDTO result = service.beginWebAuthnEnrollment(1L, "user");

        assertThat(result.getOptionsJson()).isEqualTo("browser-json");
        ArgumentCaptor<MfaChallengeDTO> captor = ArgumentCaptor.forClass(MfaChallengeDTO.class);
        verify(challengeRedisDAO).set(anyString(), captor.capture());
        assertThat(captor.getValue().getWebAuthnUserHandle()).isSameAs(userHandle);
    }

    @Test
    void completeWebAuthnEnrollment_persistsVerifiedCredential() {
        when(webAuthnProperties.isEnabled()).thenReturn(true);
        MfaChallengeDTO challenge = managementChallenge(MfaChallengePurposeEnum.WEBAUTHN_MANAGEMENT_ENROLLMENT);
        challenge.setWebAuthnRequestJson("server-json");
        challenge.setWebAuthnUserHandle(new byte[] {7});
        when(challengeRedisDAO.getAndDelete("ceremony-token")).thenReturn(challenge);
        WebAuthnService.RegistrationOutcome registration = registrationResult();
        when(webAuthnService.finishRegistration("server-json", "credential-json"))
                .thenReturn(registration);

        service.completeWebAuthnEnrollment(1L, "ceremony-token", "credential-json");

        ArgumentCaptor<MfaFactorDO> captor = ArgumentCaptor.forClass(MfaFactorDO.class);
        verify(factorMapper).insert(captor.capture());
        assertThat(captor.getValue().getCredentialId()).containsExactly(1, 2, 3);
        assertThat(captor.getValue().getUserHandle()).containsExactly(7);
    }

    @Test
    void removeFactor_rejectsLastFactorForSuperAdmin() {
        MfaFactorDO factor = factor(10L, MfaFactorTypeEnum.TOTP, "TOTP");
        when(factorMapper.selectEnabledByIdAndUserId(10L, 1L)).thenReturn(factor);
        when(factorMapper.selectEnabledByUserIdForUpdate(1L)).thenReturn(List.of(factor));
        when(permissionService.hasAnyRoles(1L, RoleCodeEnum.SUPER_ADMIN.getCode()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.removeFactor(1L, 10L)).hasMessageContaining("必须保留至少一个 MFA 因子");
        verify(factorMapper, never()).deleteEnabledByIdAndUserId(any(), any());
    }

    @Test
    void removeFactor_deletesRecoveryCodesWhenOrdinaryUserOptsOut() {
        MfaFactorDO factor = factor(10L, MfaFactorTypeEnum.TOTP, "TOTP");
        when(factorMapper.selectEnabledByIdAndUserId(10L, 1L)).thenReturn(factor);
        when(factorMapper.selectEnabledByUserIdForUpdate(1L)).thenReturn(List.of(factor));
        when(factorMapper.deleteEnabledByIdAndUserId(10L, 1L)).thenReturn(1);

        service.removeFactor(1L, 10L);

        verify(recoveryCodeManager).deleteByUserId(1L);
    }

    @Test
    void resetRecoveryCodes_requiresAnEnabledFactor() {
        when(factorMapper.selectEnabledByUserIdForUpdate(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.resetRecoveryCodes(1L)).hasMessageContaining("尚未配置可用的 MFA 因子");
        verify(recoveryCodeManager, never()).replace(any(), any());
    }

    private static MfaFactorDO factor(Long id, MfaFactorTypeEnum type, String name) {
        return MfaFactorDO.builder()
                .id(id)
                .userId(1L)
                .factorType(type.getType())
                .name(name)
                .enabled(true)
                .createTime(LocalDateTime.of(2026, 8, 23, 12, 0))
                .build();
    }

    private static MfaChallengeDTO managementChallenge(MfaChallengePurposeEnum purpose) {
        return MfaChallengeDTO.builder()
                .userId(1L)
                .username("user")
                .purpose(purpose)
                .build();
    }

    private static WebAuthnService.RegistrationOutcome registrationResult() {
        return new WebAuthnService.RegistrationOutcome(
                true, new byte[] {1, 2, 3}, new byte[] {4, 5, 6}, 2L, false, false, List.of());
    }
}

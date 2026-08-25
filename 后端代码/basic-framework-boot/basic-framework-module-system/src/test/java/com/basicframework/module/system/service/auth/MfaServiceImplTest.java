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
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.dal.mysql.auth.MfaRecoveryCodeMapper;
import com.basicframework.module.system.dal.redis.auth.MfaChallengeRedisDAO;
import com.basicframework.module.system.dal.redis.auth.MfaStepUpRedisDAO;
import com.basicframework.module.system.enums.auth.MfaChallengePurposeEnum;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaChallengeDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import com.basicframework.module.system.service.logger.LoginLogService;
import com.basicframework.module.system.service.permission.PermissionService;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MfaServiceImplTest {

    @InjectMocks
    private MfaServiceImpl service;

    @Mock
    private MfaProperties properties;

    @Mock
    private MfaProperties.WebAuthn webAuthnProperties;

    @Mock
    private MfaChallengeRedisDAO challengeRedisDAO;

    @Mock
    private MfaStepUpRedisDAO stepUpRedisDAO;

    @Mock
    private MfaFactorMapper factorMapper;

    @Mock
    private MfaRecoveryCodeMapper recoveryCodeMapper;

    @Mock
    private MfaRecoveryCodeManager recoveryCodeManager;

    @Mock
    private MfaSecretCrypto secretCrypto;

    @Mock
    private TotpAuthenticator totpAuthenticator;

    @Mock
    private WebAuthnService webAuthnService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private LoginLogService loginLogService;

    @BeforeEach
    void setUp() {
        when(properties.isEnabled()).thenReturn(true);
        org.mockito.Mockito.lenient().when(properties.getWebauthn()).thenReturn(webAuthnProperties);
    }

    @Test
    void beginAuthentication_requiresEnrollmentForSuperAdminWithoutFactor() {
        AdminUserDO user = AdminUserDO.builder().id(1L).username("admin").build();
        when(factorMapper.selectEnabledByUserId(1L)).thenReturn(List.of());
        when(permissionService.hasAnyRoles(1L, RoleCodeEnum.SUPER_ADMIN.getCode()))
                .thenReturn(true);

        AuthLoginResultDTO result = service.beginAuthentication(user, "admin", LoginLogTypeEnum.LOGIN_USERNAME);

        assertThat(result.getAccessToken()).isNull();
        assertThat(result.getMfaRequired()).isTrue();
        assertThat(result.getMfaEnrollmentRequired()).isTrue();
        assertThat(result.getMfaMethods()).containsExactly("TOTP");
        verify(challengeRedisDAO).set(anyString(), any(MfaChallengeDTO.class));
    }

    @Test
    void beginAuthentication_allowsOrdinaryUserWithoutOptInFactor() {
        AdminUserDO user = AdminUserDO.builder().id(2L).username("user").build();
        when(factorMapper.selectEnabledByUserId(2L)).thenReturn(List.of());
        when(permissionService.hasAnyRoles(2L, RoleCodeEnum.SUPER_ADMIN.getCode()))
                .thenReturn(false);

        assertThat(service.beginAuthentication(user, "user", LoginLogTypeEnum.LOGIN_USERNAME))
                .isNull();
        verify(challengeRedisDAO, never()).set(anyString(), any());
    }

    @Test
    void completeRequiredTotpEnrollment_persistsEncryptedFactorAndTenRecoveryCodes() {
        MfaChallengeDTO challenge = challenge(MfaChallengePurposeEnum.TOTP_ENROLLMENT);
        challenge.setEncryptedTotpSecret("ciphertext");
        when(challengeRedisDAO.getAndDelete("enrollment-token")).thenReturn(challenge);
        when(secretCrypto.decrypt("ciphertext", 1L)).thenReturn("BASE32SECRET");
        when(totpAuthenticator.verify("BASE32SECRET", "123456")).thenReturn(OptionalLong.of(100L));
        when(recoveryCodeManager.replace(any(), any())).thenReturn(recoveryCodes());

        MfaVerifiedPrincipalDTO principal = service.completeRequiredTotpEnrollment("enrollment-token", "123456");

        assertThat(principal.getRecoveryCodes())
                .hasSize(10)
                .allMatch(code -> code.matches("[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}"));
        verify(factorMapper).insert(any(MfaFactorDO.class));
        verify(recoveryCodeManager).replace(any(), any());
    }

    @Test
    void completeSelfTotpEnrollment_rejectsChallengeOwnedByAnotherUser() {
        when(challengeRedisDAO.getAndDelete("enrollment-token"))
                .thenReturn(challenge(MfaChallengePurposeEnum.TOTP_ENROLLMENT));

        assertThatThrownBy(() -> service.completeSelfTotpEnrollment(2L, "enrollment-token", "123456"))
                .hasMessageContaining("MFA 验证已失效");
        verify(factorMapper, never()).insert(any(MfaFactorDO.class));
    }

    @Test
    void verifyTotp_rejectsReplayedTimeStep() {
        MfaChallengeDTO challenge = challenge(MfaChallengePurposeEnum.LOGIN);
        MfaFactorDO factor = MfaFactorDO.builder()
                .id(9L)
                .userId(1L)
                .factorType(MfaFactorTypeEnum.TOTP.getType())
                .secretCiphertext("ciphertext")
                .enabled(true)
                .build();
        when(challengeRedisDAO.getAndDelete("login-token")).thenReturn(challenge);
        when(factorMapper.selectEnabledByUserIdAndType(1L, MfaFactorTypeEnum.TOTP.getType()))
                .thenReturn(factor);
        when(secretCrypto.decrypt("ciphertext", 1L)).thenReturn("BASE32SECRET");
        when(totpAuthenticator.verify("BASE32SECRET", "123456")).thenReturn(OptionalLong.of(100L));
        when(factorMapper.advanceTotpStep(
                        org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq(100L), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.verifyTotp("login-token", "123456")).hasMessageContaining("MFA 验证码不正确或已使用");
        verify(loginLogService).createLoginLog(any());
    }

    @Test
    void beginAuthentication_offersWebAuthnBeforeTotpWhenEnabled() {
        when(webAuthnProperties.isEnabled()).thenReturn(true);
        AdminUserDO user = AdminUserDO.builder().id(1L).username("admin").build();
        when(factorMapper.selectEnabledByUserId(1L)).thenReturn(List.of());
        when(permissionService.hasAnyRoles(1L, RoleCodeEnum.SUPER_ADMIN.getCode()))
                .thenReturn(true);

        AuthLoginResultDTO result = service.beginAuthentication(user, "admin", LoginLogTypeEnum.LOGIN_USERNAME);

        assertThat(result.getMfaMethods()).containsExactly("WEBAUTHN", "TOTP");
    }

    @Test
    void beginRequiredWebAuthnEnrollment_storesOpaqueHandleAndServerRequest() {
        when(webAuthnProperties.isEnabled()).thenReturn(true);
        when(challengeRedisDAO.getAndDelete("login-token"))
                .thenReturn(challenge(MfaChallengePurposeEnum.REQUIRED_ENROLLMENT));
        when(factorMapper.selectEnabledByUserIdAndTypeList(1L, MfaFactorTypeEnum.WEBAUTHN.getType()))
                .thenReturn(List.of());
        when(webAuthnService.startRegistration(any(), anyString(), any()))
                .thenReturn(new WebAuthnService.CeremonyOptions("browser-json", "server-json"));

        MfaWebAuthnOptionsDTO result = service.beginRequiredWebAuthnEnrollment("login-token");

        assertThat(result.getOptionsJson()).isEqualTo("browser-json");
        var challengeCaptor = org.mockito.ArgumentCaptor.forClass(MfaChallengeDTO.class);
        verify(challengeRedisDAO).set(anyString(), challengeCaptor.capture());
        assertThat(challengeCaptor.getValue().getPurpose()).isEqualTo(MfaChallengePurposeEnum.WEBAUTHN_ENROLLMENT);
        assertThat(challengeCaptor.getValue().getWebAuthnUserHandle()).hasSize(32);
        assertThat(challengeCaptor.getValue().getWebAuthnRequestJson()).isEqualTo("server-json");
    }

    @Test
    void completeRequiredWebAuthnEnrollment_persistsCredentialAndRecoveryCodes() {
        when(webAuthnProperties.isEnabled()).thenReturn(true);
        MfaChallengeDTO challenge = challenge(MfaChallengePurposeEnum.WEBAUTHN_ENROLLMENT);
        challenge.setWebAuthnRequestJson("server-json");
        challenge.setWebAuthnUserHandle(new byte[32]);
        when(challengeRedisDAO.getAndDelete("ceremony-token")).thenReturn(challenge);
        when(factorMapper.selectEnabledByUserIdAndTypeList(1L, MfaFactorTypeEnum.WEBAUTHN.getType()))
                .thenReturn(List.of());
        WebAuthnService.RegistrationOutcome registration = new WebAuthnService.RegistrationOutcome(
                true, new byte[] {1, 2, 3}, new byte[] {4, 5, 6}, 1L, true, false, List.of());
        when(webAuthnService.finishRegistration("server-json", "credential-json"))
                .thenReturn(registration);
        when(recoveryCodeManager.replace(any(), any())).thenReturn(recoveryCodes());

        MfaVerifiedPrincipalDTO principal =
                service.completeRequiredWebAuthnEnrollment("ceremony-token", "credential-json");

        assertThat(principal.getRecoveryCodes()).hasSize(10);
        var factorCaptor = org.mockito.ArgumentCaptor.forClass(MfaFactorDO.class);
        verify(factorMapper).insert(factorCaptor.capture());
        assertThat(factorCaptor.getValue())
                .extracting(
                        MfaFactorDO::getUserId,
                        MfaFactorDO::getFactorType,
                        MfaFactorDO::getSignatureCount,
                        MfaFactorDO::getBackupEligible)
                .containsExactly(1L, MfaFactorTypeEnum.WEBAUTHN.getType(), 1L, true);
        assertThat(factorCaptor.getValue().getCredentialId()).containsExactly(1, 2, 3);
        assertThat(factorCaptor.getValue().getPublicKeyCose()).containsExactly(4, 5, 6);
    }

    @Test
    void verifyWebAuthn_rejectsResultBoundToAnotherUser() {
        when(webAuthnProperties.isEnabled()).thenReturn(true);
        MfaChallengeDTO challenge = challenge(MfaChallengePurposeEnum.WEBAUTHN_AUTHENTICATION);
        challenge.setWebAuthnRequestJson("server-json");
        when(challengeRedisDAO.getAndDelete("ceremony-token")).thenReturn(challenge);
        WebAuthnService.AssertionOutcome assertion =
                new WebAuthnService.AssertionOutcome(true, true, true, "2", new byte[] {1}, 0L, false);
        when(webAuthnService.finishAssertion("server-json", "credential-json")).thenReturn(assertion);

        assertThatThrownBy(() -> service.verifyWebAuthn("ceremony-token", "credential-json"))
                .hasMessageContaining("安全密钥验证失败");
        verify(factorMapper, never())
                .updateWebAuthnUsage(
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        any());
        verify(loginLogService).createLoginLog(any());
    }

    @Test
    void verifyWebAuthn_advancesSignatureCounterForBoundUser() {
        when(webAuthnProperties.isEnabled()).thenReturn(true);
        MfaChallengeDTO challenge = challenge(MfaChallengePurposeEnum.WEBAUTHN_AUTHENTICATION);
        challenge.setWebAuthnRequestJson("server-json");
        when(challengeRedisDAO.getAndDelete("ceremony-token")).thenReturn(challenge);
        WebAuthnService.AssertionOutcome assertion =
                new WebAuthnService.AssertionOutcome(true, true, true, "1", new byte[] {1}, 8L, true);
        when(webAuthnService.finishAssertion("server-json", "credential-json")).thenReturn(assertion);
        MfaFactorDO factor =
                MfaFactorDO.builder().id(10L).userId(1L).signatureCount(7L).build();
        when(factorMapper.selectEnabledByCredentialId(any(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(factor);
        when(factorMapper.updateWebAuthnUsage(
                        org.mockito.ArgumentMatchers.eq(10L),
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(8L),
                        org.mockito.ArgumentMatchers.eq(true),
                        any()))
                .thenReturn(1);

        MfaVerifiedPrincipalDTO principal = service.verifyWebAuthn("ceremony-token", "credential-json");

        assertThat(principal.getUserId()).isEqualTo(1L);
        verify(factorMapper)
                .updateWebAuthnUsage(
                        org.mockito.ArgumentMatchers.eq(10L),
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(8L),
                        org.mockito.ArgumentMatchers.eq(true),
                        any());
    }

    @Test
    void beginStepUp_bindsChallengeToAccessTokenDigestAndOffersExistingFactors() {
        when(webAuthnProperties.isEnabled()).thenReturn(true);
        when(factorMapper.selectEnabledByUserId(1L))
                .thenReturn(List.of(
                        MfaFactorDO.builder()
                                .factorType(MfaFactorTypeEnum.WEBAUTHN.getType())
                                .build(),
                        MfaFactorDO.builder()
                                .factorType(MfaFactorTypeEnum.TOTP.getType())
                                .build()));

        AuthLoginResultDTO result = service.beginStepUp(1L, "access-token-secret");

        assertThat(result.getMfaMethods()).containsExactly("WEBAUTHN", "TOTP", "RECOVERY_CODE");
        var challengeCaptor = org.mockito.ArgumentCaptor.forClass(MfaChallengeDTO.class);
        verify(challengeRedisDAO).set(anyString(), challengeCaptor.capture());
        assertThat(challengeCaptor.getValue().getPurpose()).isEqualTo(MfaChallengePurposeEnum.STEP_UP);
        assertThat(challengeCaptor.getValue().getAccessTokenHash())
                .isEqualTo(MfaStepUpRedisDAO.tokenHash("access-token-secret"))
                .doesNotContain("access-token-secret");
    }

    @Test
    void completeStepUpTotp_marksOnlyBoundSessionAfterReplaySafeVerification() {
        MfaChallengeDTO challenge = challenge(MfaChallengePurposeEnum.STEP_UP);
        challenge.setAccessTokenHash("token-hash");
        MfaFactorDO factor = MfaFactorDO.builder()
                .id(9L)
                .userId(1L)
                .factorType(MfaFactorTypeEnum.TOTP.getType())
                .secretCiphertext("ciphertext")
                .enabled(true)
                .build();
        when(challengeRedisDAO.getAndDelete("step-up-token")).thenReturn(challenge);
        when(factorMapper.selectEnabledByUserIdAndType(1L, MfaFactorTypeEnum.TOTP.getType()))
                .thenReturn(factor);
        when(secretCrypto.decrypt("ciphertext", 1L)).thenReturn("BASE32SECRET");
        when(totpAuthenticator.verify("BASE32SECRET", "123456")).thenReturn(OptionalLong.of(100L));
        when(factorMapper.advanceTotpStep(
                        org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq(100L), any()))
                .thenReturn(1);

        service.completeStepUpTotp("step-up-token", "123456");

        verify(stepUpRedisDAO).setByHash("token-hash", 1L);
    }

    @Test
    void completeStepUpRecoveryCode_consumesCodeBeforeMarkingSession() {
        MfaChallengeDTO challenge = challenge(MfaChallengePurposeEnum.STEP_UP);
        challenge.setAccessTokenHash("token-hash");
        when(challengeRedisDAO.getAndDelete("step-up-token")).thenReturn(challenge);
        when(secretCrypto.recoveryCodeHash("ABCD2345EFGH67YZ")).thenReturn("code-hash");
        when(recoveryCodeMapper.consume(
                        org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("code-hash"), any()))
                .thenReturn(1);

        service.completeStepUpRecoveryCode("step-up-token", "ABCD-2345-EFGH-67YZ");

        verify(stepUpRedisDAO).setByHash("token-hash", 1L);
    }

    @Test
    void requireStepUp_rejectsMissingOrExpiredSessionMarker() {
        when(stepUpRedisDAO.matches("access-token", 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.requireStepUp("access-token", 1L)).hasMessageContaining("需要重新完成 MFA 二次验证");
    }

    @Test
    void requireStepUp_allowsOperationWhenMfaCapabilityIsDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        service.requireStepUp("access-token", 1L);

        verify(stepUpRedisDAO, never()).matches(anyString(), any());
    }

    @Test
    void methodQueries_returnEmptyWhenMfaCapabilityIsDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        assertThat(service.getEnabledMethods(1L)).isEmpty();
        assertThat(service.getEnrollmentMethods()).isEmpty();

        verify(factorMapper, never()).selectEnabledByUserId(any());
    }

    @Test
    void beginSelfEnrollment_allowsFirstFactorAndRejectsSessionOnlyReplacement() {
        when(factorMapper.selectEnabledByUserId(1L)).thenReturn(List.of());

        String token = service.beginSelfEnrollment(1L, "ordinary-user");

        assertThat(token).isNotBlank();
        var challengeCaptor = org.mockito.ArgumentCaptor.forClass(MfaChallengeDTO.class);
        verify(challengeRedisDAO).set(org.mockito.ArgumentMatchers.eq(token), challengeCaptor.capture());
        assertThat(challengeCaptor.getValue())
                .extracting(MfaChallengeDTO::getUserId, MfaChallengeDTO::getUsername, MfaChallengeDTO::getPurpose)
                .containsExactly(1L, "ordinary-user", MfaChallengePurposeEnum.REQUIRED_ENROLLMENT);

        when(factorMapper.selectEnabledByUserId(1L))
                .thenReturn(List.of(MfaFactorDO.builder()
                        .factorType(MfaFactorTypeEnum.TOTP.getType())
                        .build()));
        assertThatThrownBy(() -> service.beginSelfEnrollment(1L, "ordinary-user"))
                .hasMessageContaining("已配置同类 MFA 因子");
    }

    private static MfaChallengeDTO challenge(MfaChallengePurposeEnum purpose) {
        return MfaChallengeDTO.builder()
                .userId(1L)
                .username("admin")
                .loginLogType(LoginLogTypeEnum.LOGIN_USERNAME.getType())
                .purpose(purpose)
                .build();
    }

    private static List<String> recoveryCodes() {
        return java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> "ABCD-EFGH-IJKL-MNOP")
                .toList();
    }
}

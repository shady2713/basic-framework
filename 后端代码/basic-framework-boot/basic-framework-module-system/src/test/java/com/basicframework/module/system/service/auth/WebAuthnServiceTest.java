package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class WebAuthnServiceTest {

    @Test
    void startRegistration_requiresUserVerificationAndKeepsServerRequest() throws Exception {
        WebAuthnCredentialRepository repository = mock(WebAuthnCredentialRepository.class);
        when(repository.getCredentialIdsForUsername("1")).thenReturn(Set.of());
        WebAuthnService service = service(repository);

        WebAuthnService.CeremonyOptions options = service.startRegistration(1L, "管理员", new byte[32]);

        assertThat(options.browserOptionsJson()).contains("\"publicKey\"");
        PublicKeyCredentialCreationOptions request = PublicKeyCredentialCreationOptions.fromJson(options.requestJson());
        assertThat(request.getRp().getId()).isEqualTo("localhost");
        assertThat(request.getUser().getName()).isEqualTo("1");
        assertThat(request.getAuthenticatorSelection())
                .hasValueSatisfying(selection ->
                        assertThat(selection.getUserVerification()).contains(UserVerificationRequirement.REQUIRED));
    }

    @Test
    void finishAssertion_rejectsMalformedCredentialWithoutEchoingPayload() {
        WebAuthnService service = service(mock(WebAuthnCredentialRepository.class));

        assertThatThrownBy(() -> service.finishAssertion("{}", "not-json"))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(ErrorCodeConstants.AUTH_MFA_WEBAUTHN_INVALID.getCode()))
                .hasMessageNotContaining("not-json");
        assertInvalidCredential(() -> service.finishRegistration("{}", "not-json"));
    }

    @Test
    void startAssertion_requiresUserVerificationAndKeepsServerRequest() throws Exception {
        WebAuthnService service = service(mock(WebAuthnCredentialRepository.class));

        WebAuthnService.CeremonyOptions options = service.startAssertion(1L);

        assertThat(options.browserOptionsJson()).contains("\"publicKey\"");
        AssertionRequest request = AssertionRequest.fromJson(options.requestJson());
        assertThat(request.getUsername()).contains("1");
        assertThat(request.getPublicKeyCredentialRequestOptions().getUserVerification())
                .contains(UserVerificationRequirement.REQUIRED);
    }

    @Test
    void ceremonies_preserveDisabledOrUnavailableWebAuthnResponse() {
        MfaProperties disabledProperties = enabledProperties();
        disabledProperties.getWebauthn().setEnabled(false);
        WebAuthnService disabledService = new WebAuthnService(disabledProperties, provider(null));
        assertWebAuthnDisabled(() -> disabledService.startRegistration(1L, "管理员", new byte[32]));
        assertWebAuthnDisabled(() -> disabledService.startAssertion(1L));
        assertWebAuthnDisabled(() -> disabledService.finishRegistration("{}", "not-json"));
        assertWebAuthnDisabled(() -> disabledService.finishAssertion("{}", "not-json"));

        assertWebAuthnDisabled(() ->
                new WebAuthnService(enabledProperties(), provider(null)).startRegistration(1L, "管理员", new byte[32]));
    }

    @Test
    void startCeremonies_mapInfrastructureFailuresToInvalidResponse() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RelyingParty> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(new IllegalStateException("Relying party unavailable"));
        WebAuthnService service = new WebAuthnService(enabledProperties(), provider);

        assertInvalidCredential(() -> service.startRegistration(1L, "管理员", new byte[32]));
        assertInvalidCredential(() -> service.startAssertion(1L));
    }

    @Test
    void finishCeremonies_rejectInvalidCredentialBeforeInvokingWebAuthn() {
        WebAuthnService service = new WebAuthnService(enabledProperties(), provider(null));

        assertInvalidCredential(() -> service.finishRegistration("{}", null));
        assertInvalidCredential(() -> service.finishRegistration("{}", " "));
        assertInvalidCredential(() -> service.finishAssertion("{}", "x".repeat(65_537)));
    }

    @Test
    void outcomes_defensivelyCopyCredentialMaterial() {
        byte[] credentialId = {1, 2};
        byte[] publicKeyCose = {3, 4};
        WebAuthnService.RegistrationOutcome registration = new WebAuthnService.RegistrationOutcome(
                true, credentialId, publicKeyCose, 1L, true, false, List.of("usb"));
        WebAuthnService.AssertionOutcome assertion =
                new WebAuthnService.AssertionOutcome(true, true, true, "1", credentialId, 2L, false);

        credentialId[0] = 9;
        publicKeyCose[0] = 9;
        assertThat(registration.credentialId()).containsExactly(1, 2);
        assertThat(registration.publicKeyCose()).containsExactly(3, 4);
        assertThat(assertion.credentialId()).containsExactly(1, 2);

        registration.credentialId()[0] = 8;
        assertion.credentialId()[0] = 8;
        assertThat(registration.credentialId()).containsExactly(1, 2);
        assertThat(assertion.credentialId()).containsExactly(1, 2);
    }

    private static WebAuthnService service(WebAuthnCredentialRepository repository) {
        MfaProperties properties = enabledProperties();

        RelyingParty relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id("localhost")
                        .name("basic-framework-test")
                        .build())
                .credentialRepository(repository)
                .origins(Set.of("http://localhost"))
                .build();
        return new WebAuthnService(properties, provider(relyingParty));
    }

    private static MfaProperties enabledProperties() {
        MfaProperties properties = new MfaProperties();
        properties.setEnabled(true);
        properties.setChallengeTtl(Duration.ofMinutes(5));
        properties.getWebauthn().setEnabled(true);
        properties.getWebauthn().setRpId("localhost");
        properties.getWebauthn().setRpName("basic-framework-test");
        properties.getWebauthn().setAllowedOrigins(Set.of("http://localhost"));
        return properties;
    }

    private static void assertWebAuthnDisabled(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(ErrorCodeConstants.AUTH_MFA_DISABLED.getCode()));
    }

    private static void assertInvalidCredential(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(ErrorCodeConstants.AUTH_MFA_WEBAUTHN_INVALID.getCode()));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RelyingParty> provider(RelyingParty relyingParty) {
        ObjectProvider<RelyingParty> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(relyingParty);
        return provider;
    }
}

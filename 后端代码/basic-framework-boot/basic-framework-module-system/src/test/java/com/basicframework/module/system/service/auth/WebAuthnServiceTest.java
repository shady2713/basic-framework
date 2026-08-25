package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import java.time.Duration;
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
    }

    private static WebAuthnService service(WebAuthnCredentialRepository repository) {
        MfaProperties properties = new MfaProperties();
        properties.setEnabled(true);
        properties.setChallengeTtl(Duration.ofMinutes(5));
        properties.getWebauthn().setEnabled(true);
        properties.getWebauthn().setRpId("localhost");
        properties.getWebauthn().setRpName("basic-framework-test");
        properties.getWebauthn().setAllowedOrigins(Set.of("http://localhost"));

        RelyingParty relyingParty = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder()
                        .id("localhost")
                        .name("basic-framework-test")
                        .build())
                .credentialRepository(repository)
                .origins(Set.of("http://localhost"))
                .build();
        @SuppressWarnings("unchecked")
        ObjectProvider<RelyingParty> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(relyingParty);
        return new WebAuthnService(properties, provider);
    }
}

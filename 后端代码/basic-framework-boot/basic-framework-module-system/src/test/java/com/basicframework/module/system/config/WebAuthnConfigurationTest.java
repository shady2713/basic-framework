package com.basicframework.module.system.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.module.system.service.auth.WebAuthnCredentialRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebAuthnConfigurationTest {

    @Test
    void relyingParty_usesExactOriginsAndSecurityDefaults() {
        MfaProperties properties = new MfaProperties();
        properties.getWebauthn().setRpId("example.com");
        properties.getWebauthn().setRpName("basic-framework");
        properties.getWebauthn().setAllowedOrigins(Set.of("https://admin.example.com"));
        WebAuthnCredentialRepository repository = mock(WebAuthnCredentialRepository.class);

        RelyingParty relyingParty = new WebAuthnConfiguration().relyingParty(properties, repository);

        assertThat(relyingParty.getIdentity().getId()).isEqualTo("example.com");
        assertThat(relyingParty.getIdentity().getName()).isEqualTo("basic-framework");
        assertThat(relyingParty.getOrigins()).containsExactly("https://admin.example.com");
        assertThat(relyingParty.getCredentialRepository()).isSameAs(repository);
        assertThat(relyingParty.getAttestationConveyancePreference()).contains(AttestationConveyancePreference.NONE);
        assertThat(relyingParty.isAllowOriginPort()).isFalse();
        assertThat(relyingParty.isAllowOriginSubdomain()).isFalse();
        assertThat(relyingParty.isAllowUntrustedAttestation()).isTrue();
        assertThat(relyingParty.isValidateSignatureCounter()).isTrue();
    }
}

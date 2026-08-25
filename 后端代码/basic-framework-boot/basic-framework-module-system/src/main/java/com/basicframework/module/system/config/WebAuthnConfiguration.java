package com.basicframework.module.system.config;

import com.basicframework.module.system.service.auth.WebAuthnCredentialRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** WebAuthn Relying Party 装配；Origin 只接受配置中的精确值。 */
@Configuration(proxyBeanMethods = false)
public class WebAuthnConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "basic-framework.security.mfa.webauthn", name = "enabled", havingValue = "true")
    public RelyingParty relyingParty(MfaProperties properties, WebAuthnCredentialRepository credentialRepository) {
        MfaProperties.WebAuthn webauthn = properties.getWebauthn();
        RelyingPartyIdentity identity = RelyingPartyIdentity.builder()
                .id(webauthn.getRpId())
                .name(webauthn.getRpName())
                .build();
        return RelyingParty.builder()
                .identity(identity)
                .credentialRepository(credentialRepository)
                .origins(webauthn.getAllowedOrigins())
                .attestationConveyancePreference(AttestationConveyancePreference.NONE)
                .allowOriginPort(false)
                .allowOriginSubdomain(false)
                .allowUntrustedAttestation(true)
                .validateSignatureCounter(true)
                .build();
    }
}

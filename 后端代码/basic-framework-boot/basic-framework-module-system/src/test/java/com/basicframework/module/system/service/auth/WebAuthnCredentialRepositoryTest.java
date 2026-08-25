package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebAuthnCredentialRepositoryTest {

    @InjectMocks
    private WebAuthnCredentialRepository repository;

    @Mock
    private MfaFactorMapper factorMapper;

    @Test
    void credentialLookup_mapsPersistedCredential() {
        MfaFactorDO factor = factor();
        when(factorMapper.selectEnabledByCredentialId(factor.getCredentialId(), MfaFactorTypeEnum.WEBAUTHN.getType()))
                .thenReturn(factor);

        var credential =
                repository.lookup(new ByteArray(factor.getCredentialId()), new ByteArray(factor.getUserHandle()));

        assertThat(credential).isPresent();
        assertThat(credential.orElseThrow().getSignatureCount()).isEqualTo(7L);
        assertBackupState(credential.orElseThrow());
        when(factorMapper.selectEnabledByUserIdAndTypeList(1L, MfaFactorTypeEnum.WEBAUTHN.getType()))
                .thenReturn(List.of(factor));
        assertThat(repository.getCredentialIdsForUsername("1").iterator().next().getTransports())
                .hasValueSatisfying(transports -> assertThat(transports)
                        .extracting(transport -> transport.getId())
                        .containsExactly("internal"));
    }

    @Test
    void credentialLookup_rejectsMismatchedUserHandle() {
        MfaFactorDO factor = factor();
        when(factorMapper.selectEnabledByCredentialId(factor.getCredentialId(), MfaFactorTypeEnum.WEBAUTHN.getType()))
                .thenReturn(factor);

        assertThat(repository.lookup(new ByteArray(factor.getCredentialId()), new ByteArray(new byte[] {9})))
                .isEmpty();
    }

    @Test
    void usernameLookup_usesOpaqueInternalUserId() {
        MfaFactorDO factor = factor();
        when(factorMapper.selectEnabledByUserIdAndTypeList(1L, MfaFactorTypeEnum.WEBAUTHN.getType()))
                .thenReturn(List.of(factor));

        assertThat(repository.getUserHandleForUsername("1")).contains(new ByteArray(factor.getUserHandle()));
        assertThat(repository.getUserHandleForUsername("not-an-id")).isEmpty();
    }

    private static MfaFactorDO factor() {
        return MfaFactorDO.builder()
                .id(10L)
                .userId(1L)
                .factorType(MfaFactorTypeEnum.WEBAUTHN.getType())
                .credentialId(new byte[] {1, 2})
                .userHandle(new byte[] {3, 4})
                .publicKeyCose(new byte[] {5, 6})
                .signatureCount(7L)
                .backupEligible(true)
                .backupState(false)
                .transports("[\"internal\"]")
                .enabled(true)
                .build();
    }

    @SuppressWarnings("deprecation")
    private static void assertBackupState(RegisteredCredential credential) {
        assertThat(credential.isBackupEligible()).contains(true);
        assertThat(credential.isBackedUp()).contains(false);
    }
}

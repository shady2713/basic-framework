package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.dal.dataobject.auth.MfaRecoveryCodeDO;
import com.basicframework.module.system.dal.mysql.auth.MfaRecoveryCodeMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MfaRecoveryCodeManagerTest {

    @InjectMocks
    private MfaRecoveryCodeManager manager;

    @Mock
    private MfaRecoveryCodeMapper recoveryCodeMapper;

    @Mock
    private MfaSecretCrypto secretCrypto;

    @Test
    void replace_deletesOldCodesAndPersistsTenHashes() {
        when(secretCrypto.recoveryCodeHash(any())).thenReturn("code-hash");
        LocalDateTime now = LocalDateTime.of(2026, 8, 23, 12, 0);

        List<String> codes = manager.replace(7L, now);

        assertThat(codes)
                .hasSize(10)
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}"));
        verify(recoveryCodeMapper).deleteByUserId(7L);
        ArgumentCaptor<MfaRecoveryCodeDO> captor = ArgumentCaptor.forClass(MfaRecoveryCodeDO.class);
        verify(recoveryCodeMapper, times(10)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(code -> {
            assertThat(code.getUserId()).isEqualTo(7L);
            assertThat(code.getCodeHash()).isEqualTo("code-hash");
            assertThat(code.getCreateTime()).isEqualTo(now);
        });
    }

    @Test
    void deleteByUserId_deletesAllCodes() {
        manager.deleteByUserId(9L);

        verify(recoveryCodeMapper).deleteByUserId(9L);
    }
}

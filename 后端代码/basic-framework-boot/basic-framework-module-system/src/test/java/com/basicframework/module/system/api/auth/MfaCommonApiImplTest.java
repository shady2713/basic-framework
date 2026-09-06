package com.basicframework.module.system.api.auth;

import static org.mockito.Mockito.verify;

import com.basicframework.module.system.service.auth.MfaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MfaCommonApiImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class MfaCommonApiImplTest {

    @InjectMocks
    private MfaCommonApiImpl mfaCommonApi;

    @Mock
    private MfaService mfaService;

    @Test
    void requireStepUp_delegatesToMfaService() {
        mfaCommonApi.requireStepUp("access-token-1", 100L);

        verify(mfaService).requireStepUp("access-token-1", 100L);
    }
}

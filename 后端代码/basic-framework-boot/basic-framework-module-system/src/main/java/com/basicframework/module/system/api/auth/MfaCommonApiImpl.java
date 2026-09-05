package com.basicframework.module.system.api.auth;

import com.basicframework.module.system.service.auth.MfaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** MFA 跨模块安全契约实现。 */
@Service
@RequiredArgsConstructor
public class MfaCommonApiImpl implements MfaCommonApi {

    private final MfaService mfaService;

    @Override
    public void requireStepUp(String accessToken, Long userId) {
        mfaService.requireStepUp(accessToken, userId);
    }
}

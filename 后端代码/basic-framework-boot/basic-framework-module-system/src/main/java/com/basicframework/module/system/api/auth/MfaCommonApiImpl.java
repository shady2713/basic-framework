package com.basicframework.module.system.api.auth;

import com.basicframework.module.system.service.auth.MfaService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/** MFA 跨模块安全契约实现。 */
@Service
public class MfaCommonApiImpl implements MfaCommonApi {

    @Resource
    private MfaService mfaService;

    @Override
    public void requireStepUp(String accessToken, Long userId) {
        mfaService.requireStepUp(accessToken, userId);
    }
}

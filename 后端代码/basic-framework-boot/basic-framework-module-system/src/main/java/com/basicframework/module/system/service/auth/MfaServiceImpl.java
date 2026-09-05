package com.basicframework.module.system.service.auth;

import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.auth.dto.MfaWebAuthnOptionsDTO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MFA 对外事务入口；具体登录、注册与二次验证由独立流程组件执行。 */
@Service
public class MfaServiceImpl implements MfaService {

    private final MfaLoginFlow loginFlow;
    private final MfaRequiredEnrollmentFlow enrollmentFlow;
    private final MfaStepUpFlow stepUpFlow;
    private final MfaMethodPolicy methodPolicy;

    public MfaServiceImpl(
            MfaLoginFlow loginFlow,
            MfaRequiredEnrollmentFlow enrollmentFlow,
            MfaStepUpFlow stepUpFlow,
            MfaMethodPolicy methodPolicy) {
        this.loginFlow = loginFlow;
        this.enrollmentFlow = enrollmentFlow;
        this.stepUpFlow = stepUpFlow;
        this.methodPolicy = methodPolicy;
    }

    @Override
    public AuthLoginResultDTO beginAuthentication(AdminUserDO user, String loginIdentity, LoginLogTypeEnum logType) {
        return loginFlow.beginAuthentication(user, loginIdentity, logType);
    }

    @Override
    public MfaTotpSetupDTO beginRequiredTotpEnrollment(String mfaToken) {
        return enrollmentFlow.beginTotp(mfaToken);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO completeRequiredTotpEnrollment(String enrollmentToken, String code) {
        return enrollmentFlow.completeTotp(null, enrollmentToken, code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO completeSelfTotpEnrollment(Long userId, String enrollmentToken, String code) {
        return enrollmentFlow.completeTotp(userId, enrollmentToken, code);
    }

    @Override
    public MfaVerifiedPrincipalDTO verifyTotp(String mfaToken, String code) {
        return loginFlow.verifyTotp(mfaToken, code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO verifyRecoveryCode(String mfaToken, String recoveryCode) {
        return loginFlow.verifyRecoveryCode(mfaToken, recoveryCode);
    }

    @Override
    public MfaWebAuthnOptionsDTO beginRequiredWebAuthnEnrollment(String mfaToken) {
        return enrollmentFlow.beginWebAuthn(mfaToken);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO completeRequiredWebAuthnEnrollment(String ceremonyToken, String credentialJson) {
        return enrollmentFlow.completeWebAuthn(null, ceremonyToken, credentialJson);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MfaVerifiedPrincipalDTO completeSelfWebAuthnEnrollment(
            Long userId, String ceremonyToken, String credentialJson) {
        return enrollmentFlow.completeWebAuthn(userId, ceremonyToken, credentialJson);
    }

    @Override
    public MfaWebAuthnOptionsDTO beginWebAuthnAuthentication(String mfaToken) {
        return loginFlow.beginWebAuthn(mfaToken);
    }

    @Override
    public MfaVerifiedPrincipalDTO verifyWebAuthn(String ceremonyToken, String credentialJson) {
        return loginFlow.verifyWebAuthn(ceremonyToken, credentialJson);
    }

    @Override
    public AuthLoginResultDTO beginStepUp(Long userId, String accessToken) {
        return stepUpFlow.begin(userId, accessToken);
    }

    @Override
    public void completeStepUpTotp(String mfaToken, String code) {
        stepUpFlow.completeTotp(mfaToken, code);
    }

    @Override
    public void completeStepUpRecoveryCode(String mfaToken, String recoveryCode) {
        stepUpFlow.completeRecoveryCode(mfaToken, recoveryCode);
    }

    @Override
    public MfaWebAuthnOptionsDTO beginStepUpWebAuthn(String mfaToken) {
        return stepUpFlow.beginWebAuthn(mfaToken);
    }

    @Override
    public void completeStepUpWebAuthn(String ceremonyToken, String credentialJson) {
        stepUpFlow.completeWebAuthn(ceremonyToken, credentialJson);
    }

    @Override
    public void requireStepUp(String accessToken, Long userId) {
        stepUpFlow.require(accessToken, userId);
    }

    @Override
    public String beginSelfEnrollment(Long userId, String username) {
        return loginFlow.beginSelfEnrollment(userId, username);
    }

    @Override
    public List<String> getEnabledMethods(Long userId) {
        return methodPolicy.enabledMethods(userId);
    }

    @Override
    public List<String> getEnrollmentMethods() {
        return methodPolicy.isEnabled() ? methodPolicy.enrollmentMethods() : List.of();
    }
}

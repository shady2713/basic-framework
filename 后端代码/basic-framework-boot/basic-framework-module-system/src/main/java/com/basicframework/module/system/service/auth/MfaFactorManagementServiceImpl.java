package com.basicframework.module.system.service.auth;

import static com.basicframework.module.system.enums.LogRecordConstants.*;

import com.basicframework.module.system.service.auth.dto.MfaFactorDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.mzt.logapi.starter.annotation.LogRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MFA 因子管理编排。安全校验与持久化由对应的内部管理器负责。 */
@Service
public class MfaFactorManagementServiceImpl implements MfaFactorManagementService {

    private final MfaTotpEnrollmentManager totpEnrollmentManager;
    private final MfaFactorLifecycleManager factorLifecycleManager;
    private final MfaRecoveryCodeManager recoveryCodeManager;

    public MfaFactorManagementServiceImpl(
            MfaTotpEnrollmentManager totpEnrollmentManager,
            MfaFactorLifecycleManager factorLifecycleManager,
            MfaRecoveryCodeManager recoveryCodeManager) {
        this.totpEnrollmentManager = totpEnrollmentManager;
        this.factorLifecycleManager = factorLifecycleManager;
        this.recoveryCodeManager = recoveryCodeManager;
    }

    @Override
    public List<MfaFactorDTO> getFactors(Long userId) {
        return factorLifecycleManager.getFactors(userId);
    }

    @Override
    public MfaTotpSetupDTO beginTotpEnrollment(Long userId, String username) {
        return totpEnrollmentManager.begin(userId, username);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_MFA_ADD_SUB_TYPE,
            bizNo = "{{#userId}}",
            success = SYSTEM_USER_MFA_TOTP_ADD_SUCCESS)
    public List<String> completeTotpEnrollment(Long userId, String enrollmentToken, String code) {
        LocalDateTime completedAt = totpEnrollmentManager.complete(userId, enrollmentToken, code);
        return recoveryCodeManager.replace(userId, completedAt);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_MFA_DELETE_SUB_TYPE,
            bizNo = "{{#userId}}",
            success = SYSTEM_USER_MFA_DELETE_SUCCESS)
    public void removeFactor(Long userId, Long factorId) {
        factorLifecycleManager.remove(userId, factorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @LogRecord(
            type = SYSTEM_USER_TYPE,
            subType = SYSTEM_USER_MFA_RECOVERY_RESET_SUB_TYPE,
            bizNo = "{{#userId}}",
            success = SYSTEM_USER_MFA_RECOVERY_RESET_SUCCESS)
    public List<String> resetRecoveryCodes(Long userId) {
        return factorLifecycleManager.resetRecoveryCodes(userId);
    }
}

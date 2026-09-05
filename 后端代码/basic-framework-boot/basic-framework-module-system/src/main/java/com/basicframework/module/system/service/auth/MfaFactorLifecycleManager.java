package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.*;

import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.service.auth.dto.MfaFactorDTO;
import com.basicframework.module.system.service.permission.PermissionService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 查询和移除 MFA 因子，并维护因子与恢复码之间的生命周期约束。 */
@Component
public class MfaFactorLifecycleManager {

    private final MfaProperties properties;
    private final MfaFactorMapper factorMapper;
    private final MfaRecoveryCodeManager recoveryCodeManager;
    private final PermissionService permissionService;

    public MfaFactorLifecycleManager(
            MfaProperties properties,
            MfaFactorMapper factorMapper,
            MfaRecoveryCodeManager recoveryCodeManager,
            PermissionService permissionService) {
        this.properties = properties;
        this.factorMapper = factorMapper;
        this.recoveryCodeManager = recoveryCodeManager;
        this.permissionService = permissionService;
    }

    public List<MfaFactorDTO> getFactors(Long userId) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        return factorMapper.selectEnabledByUserId(userId).stream()
                .map(MfaFactorLifecycleManager::toFactorDTO)
                .toList();
    }

    public void remove(Long userId, Long factorId) {
        requireEnabled();
        MfaFactorDO factor = factorMapper.selectEnabledByIdAndUserId(factorId, userId);
        if (factor == null) {
            throw exception(AUTH_MFA_FACTOR_NOT_FOUND);
        }
        List<MfaFactorDO> factors = factorMapper.selectEnabledByUserIdForUpdate(userId);
        if (factors.size() <= 1 && permissionService.hasAnyRoles(userId, RoleCodeEnum.SUPER_ADMIN.getCode())) {
            throw exception(AUTH_MFA_LAST_FACTOR_REQUIRED);
        }
        if (factorMapper.deleteEnabledByIdAndUserId(factorId, userId) != 1) {
            throw exception(AUTH_MFA_FACTOR_NOT_FOUND);
        }
        if (factors.size() <= 1) {
            recoveryCodeManager.deleteByUserId(userId);
        }
    }

    public List<String> resetRecoveryCodes(Long userId) {
        requireEnabled();
        if (factorMapper.selectEnabledByUserIdForUpdate(userId).isEmpty()) {
            throw exception(AUTH_MFA_NOT_CONFIGURED);
        }
        return recoveryCodeManager.replace(userId, LocalDateTime.now());
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw exception(AUTH_MFA_DISABLED);
        }
    }

    private static MfaFactorDTO toFactorDTO(MfaFactorDO factor) {
        if (MfaFactorTypeEnum.TOTP.getType().equals(factor.getFactorType())) {
            return MfaFactorDTO.builder()
                    .id(factor.getId())
                    .type("TOTP")
                    .name("动态验证码")
                    .createTime(factor.getCreateTime())
                    .build();
        }
        if (MfaFactorTypeEnum.WEBAUTHN.getType().equals(factor.getFactorType())) {
            String suffix = StringUtils.hasText(factor.getName())
                    ? factor.getName().substring(Math.max(0, factor.getName().length() - 8))
                    : String.valueOf(factor.getId());
            return MfaFactorDTO.builder()
                    .id(factor.getId())
                    .type("WEBAUTHN")
                    .name("安全密钥 · " + suffix)
                    .createTime(factor.getCreateTime())
                    .build();
        }
        throw new IllegalStateException("未知 MFA 因子类型: " + factor.getFactorType());
    }
}

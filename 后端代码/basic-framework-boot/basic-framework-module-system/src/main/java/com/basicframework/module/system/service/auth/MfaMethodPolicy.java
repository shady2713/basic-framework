package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_DISABLED;

import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.service.permission.PermissionService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 统一决定 MFA 是否生效以及向客户端暴露哪些认证方式。 */
@Component
public class MfaMethodPolicy {

    private static final String WEB_AUTHN_METHOD = "WEBAUTHN";
    private static final String TOTP_METHOD = "TOTP";
    private static final String RECOVERY_METHOD = "RECOVERY_CODE";

    private final MfaProperties properties;
    private final MfaFactorMapper factorMapper;
    private final PermissionService permissionService;

    public MfaMethodPolicy(
            MfaProperties properties, MfaFactorMapper factorMapper, PermissionService permissionService) {
        this.properties = properties;
        this.factorMapper = factorMapper;
        this.permissionService = permissionService;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw exception(AUTH_MFA_DISABLED);
        }
    }

    public void requireWebAuthnEnabled() {
        requireEnabled();
        if (!properties.getWebauthn().isEnabled()) {
            throw exception(AUTH_MFA_DISABLED);
        }
    }

    public List<MfaFactorDO> enabledFactors(Long userId) {
        return factorMapper.selectEnabledByUserId(userId);
    }

    public boolean requiresAuthentication(Long userId, List<MfaFactorDO> factors) {
        return !factors.isEmpty() || permissionService.hasAnyRoles(userId, RoleCodeEnum.SUPER_ADMIN.getCode());
    }

    public List<String> availableMethods(List<MfaFactorDO> factors) {
        boolean webAuthnAvailable = factors.stream()
                .anyMatch(factor -> MfaFactorTypeEnum.WEBAUTHN.getType().equals(factor.getFactorType()));
        boolean totpAvailable = factors.stream()
                .anyMatch(factor -> MfaFactorTypeEnum.TOTP.getType().equals(factor.getFactorType()));
        List<String> methods = new ArrayList<>(3);
        if (webAuthnAvailable && properties.getWebauthn().isEnabled()) {
            methods.add(WEB_AUTHN_METHOD);
        }
        if (totpAvailable) {
            methods.add(TOTP_METHOD);
        }
        methods.add(RECOVERY_METHOD);
        return List.copyOf(methods);
    }

    public List<String> enrollmentMethods() {
        return properties.getWebauthn().isEnabled() ? List.of(WEB_AUTHN_METHOD, TOTP_METHOD) : List.of(TOTP_METHOD);
    }

    public List<String> enabledMethods(Long userId) {
        if (!isEnabled()) {
            return List.of();
        }
        List<MfaFactorDO> factors = enabledFactors(userId);
        if (factors.isEmpty()) {
            return List.of();
        }
        return availableMethods(factors).stream()
                .filter(method -> !RECOVERY_METHOD.equals(method))
                .toList();
    }
}

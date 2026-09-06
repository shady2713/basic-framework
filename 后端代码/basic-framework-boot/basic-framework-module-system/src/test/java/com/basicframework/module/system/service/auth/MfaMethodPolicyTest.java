package com.basicframework.module.system.service.auth;

import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_DISABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.service.permission.PermissionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 覆盖 MFA 能力开关与认证方式列表的边界分支。 */
@ExtendWith(MockitoExtension.class)
class MfaMethodPolicyTest {

    @Mock
    private MfaProperties properties;

    @Mock
    private MfaFactorMapper factorMapper;

    @Mock
    private PermissionService permissionService;

    private MfaMethodPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new MfaMethodPolicy(properties, factorMapper, permissionService);
    }

    @Test
    void requireEnabled_failsLoudWhenMfaCapabilityIsDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> policy.requireEnabled())
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_DISABLED.getCode()));
    }

    @Test
    void availableMethods_offersOnlyRecoveryCodesWhenNoTotpFactorExists() {
        List<MfaFactorDO> legacyFactors =
                List.of(MfaFactorDO.builder().factorType(99).build());

        assertThat(policy.availableMethods(legacyFactors)).containsExactly("RECOVERY_CODE");
    }

    @Test
    void enabledMethods_returnsNothingForUsersWithoutAnEnabledFactor() {
        when(properties.isEnabled()).thenReturn(true);
        when(factorMapper.selectEnabledByUserId(1L)).thenReturn(List.of());

        assertThat(policy.enabledMethods(1L)).isEmpty();

        verifyNoInteractions(permissionService);
    }
}

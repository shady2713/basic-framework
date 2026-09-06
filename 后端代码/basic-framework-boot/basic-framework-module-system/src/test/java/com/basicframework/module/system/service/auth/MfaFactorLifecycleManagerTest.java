package com.basicframework.module.system.service.auth;

import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_DISABLED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_FACTOR_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.config.MfaProperties;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.basicframework.module.system.enums.permission.RoleCodeEnum;
import com.basicframework.module.system.service.permission.PermissionService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 覆盖因子生命周期管理的开关防御、删除竞态与遗留因子类型的 fail-closed 行为。 */
@ExtendWith(MockitoExtension.class)
class MfaFactorLifecycleManagerTest {

    @Mock
    private MfaProperties properties;

    @Mock
    private MfaFactorMapper factorMapper;

    @Mock
    private MfaRecoveryCodeManager recoveryCodeManager;

    @Mock
    private PermissionService permissionService;

    private MfaFactorLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new MfaFactorLifecycleManager(properties, factorMapper, recoveryCodeManager, permissionService);
    }

    @Test
    void getFactors_returnsAnEmptyListWhenMfaCapabilityIsDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        assertThat(manager.getFactors(1L)).isEmpty();

        verifyNoInteractions(factorMapper);
    }

    @Test
    void remove_rejectsUnknownOrForeignFactors() {
        when(properties.isEnabled()).thenReturn(true);
        when(factorMapper.selectEnabledByIdAndUserId(10L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> manager.remove(1L, 10L))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_FACTOR_NOT_FOUND.getCode()));

        verifyNoInteractions(permissionService);
    }

    @Test
    void remove_rejectsWhenTheDeleteLosesARaceAgainstConcurrentRemoval() {
        when(properties.isEnabled()).thenReturn(true);
        MfaFactorDO factor = totpFactor();
        when(factorMapper.selectEnabledByIdAndUserId(10L, 1L)).thenReturn(factor);
        when(factorMapper.selectEnabledByUserIdForUpdate(1L)).thenReturn(List.of(factor));
        when(permissionService.hasAnyRoles(1L, RoleCodeEnum.SUPER_ADMIN.getCode()))
                .thenReturn(false);
        when(factorMapper.deleteEnabledByIdAndUserId(10L, 1L)).thenReturn(0);

        assertThatThrownBy(() -> manager.remove(1L, 10L))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_FACTOR_NOT_FOUND.getCode()));

        verifyNoInteractions(recoveryCodeManager);
    }

    @Test
    void resetRecoveryCodes_rotatesTheCodesOfAConfiguredUser() {
        when(properties.isEnabled()).thenReturn(true);
        when(factorMapper.selectEnabledByUserIdForUpdate(1L)).thenReturn(List.of(totpFactor()));
        when(recoveryCodeManager.replace(eq(1L), any(LocalDateTime.class))).thenReturn(List.of("AAAA-BBBB-CCCC-DDDD"));

        assertThat(manager.resetRecoveryCodes(1L)).containsExactly("AAAA-BBBB-CCCC-DDDD");
    }

    @Test
    void mutatingOperations_failLoudWhenMfaCapabilityIsDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> manager.remove(1L, 10L))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_DISABLED.getCode()));
        assertThatThrownBy(() -> manager.resetRecoveryCodes(1L))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AUTH_MFA_DISABLED.getCode()));

        verifyNoInteractions(factorMapper);
    }

    @Test
    void toFactorDTO_failsLoudOnLegacyFactorTypesWithoutAnAuthenticationChannel() throws Exception {
        // 该防御分支位于 managed-factor 过滤之后，只能直接构造遗留类型验证 fail-closed 行为。
        Method toFactorDTO = MfaFactorLifecycleManager.class.getDeclaredMethod("toFactorDTO", MfaFactorDO.class);
        toFactorDTO.setAccessible(true);
        MfaFactorDO legacyFactor = MfaFactorDO.builder().id(9L).factorType(99).build();

        assertThatThrownBy(() -> {
                    try {
                        toFactorDTO.invoke(null, legacyFactor);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知 MFA 因子类型")
                .hasMessageContaining("99");
    }

    private static MfaFactorDO totpFactor() {
        return MfaFactorDO.builder()
                .id(10L)
                .userId(1L)
                .factorType(MfaFactorTypeEnum.TOTP.getType())
                .name("TOTP")
                .enabled(true)
                .createTime(LocalDateTime.of(2026, 8, 23, 12, 0))
                .build();
    }
}

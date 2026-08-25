package com.basicframework.framework.datapermission.config;

import com.basicframework.framework.common.security.CurrentUserProvider;
import com.basicframework.framework.datapermission.core.rule.dept.DeptDataPermissionRule;
import com.basicframework.framework.datapermission.core.rule.dept.DeptDataPermissionRuleCustomizer;
import com.basicframework.module.system.api.permission.PermissionCommonApi;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * 基于部门的数据权限 AutoConfiguration
 *
 * {@link ConditionalOnBean} 依赖 {@link CurrentUserProvider}：该实现 Bean 由
 * security starter 的自动配置注册（@AutoConfigureOrder(-1)，先于本配置解析），
 * security starter 缺席时本规则不挂载
 *
 */
@AutoConfiguration
@ConditionalOnBean(value = {DeptDataPermissionRuleCustomizer.class, CurrentUserProvider.class})
public class BasicFrameworkDeptDataPermissionAutoConfiguration {

    @Bean
    public DeptDataPermissionRule deptDataPermissionRule(
            PermissionCommonApi permissionApi,
            ObjectProvider<CurrentUserProvider> currentUserProvider,
            List<DeptDataPermissionRuleCustomizer> customizers) {
        // 创建 DeptDataPermissionRule 对象
        DeptDataPermissionRule rule = new DeptDataPermissionRule(permissionApi, currentUserProvider);
        // 补全表配置
        customizers.forEach(customizer -> customizer.customize(rule));
        return rule;
    }
}

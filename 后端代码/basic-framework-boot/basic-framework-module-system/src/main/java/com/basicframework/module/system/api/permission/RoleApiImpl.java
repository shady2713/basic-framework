package com.basicframework.module.system.api.permission;

import com.basicframework.module.system.service.permission.RoleService;
import jakarta.annotation.Resource;
import java.util.Collection;
import org.springframework.stereotype.Service;

/**
 * 角色 API 实现类
 *
 */
@Service
public class RoleApiImpl implements RoleApi {

    @Resource
    private RoleService roleService;

    @Override
    public void validRoleList(Collection<Long> ids) {
        roleService.validateRoleList(ids);
    }
}

package com.basicframework.module.infra.api.codegen;

import com.basicframework.module.infra.dal.mysql.codegen.CodegenTableMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/** 代码生成逻辑引用 API 实现。 */
@Service
public class CodegenReferenceCommonApiImpl implements CodegenReferenceCommonApi {

    @Resource
    private CodegenTableMapper codegenTableMapper;

    @Override
    public boolean isParentMenuReferenced(Long menuId) {
        if (menuId == null || menuId == 0L) {
            return false;
        }
        return codegenTableMapper.selectCountByParentMenuId(menuId) > 0;
    }
}

package com.basicframework.module.infra.dal.mysql.codegen;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenColumnDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CodegenColumnMapper extends BaseMapperX<CodegenColumnDO> {

    default List<CodegenColumnDO> selectListByTableId(Long tableId) {
        return selectList(new LambdaQueryWrapperX<CodegenColumnDO>()
                .eq(CodegenColumnDO::getTableId, tableId)
                .orderByAsc(CodegenColumnDO::getOrdinalPosition));
    }

    default void deleteListByTableId(Long tableId) {
        delete(CodegenColumnDO::getTableId, tableId);
    }

    default void deleteListByTableId(Collection<Long> tableIds) {
        delete(new LambdaQueryWrapperX<CodegenColumnDO>().in(CodegenColumnDO::getTableId, tableIds));
    }
}

package com.basicframework.module.infra.dal.mysql.codegen;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenTableDO;
import com.basicframework.module.infra.enums.codegen.CodegenTemplateTypeEnum;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CodegenTableMapper extends BaseMapperX<CodegenTableDO> {

    default CodegenTableDO selectByTableName(String tableName) {
        return selectOne(CodegenTableDO::getTableName, tableName);
    }

    default PageResult<CodegenTableDO> selectPage(
            PageParam pageParam, String tableName, String tableComment, String className, LocalDateTime[] createTime) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<CodegenTableDO>()
                        .likeIfPresent(CodegenTableDO::getTableName, tableName)
                        .likeIfPresent(CodegenTableDO::getTableComment, tableComment)
                        .likeIfPresent(CodegenTableDO::getClassName, className)
                        .betweenIfPresent(CodegenTableDO::getCreateTime, createTime)
                        .orderByDesc(CodegenTableDO::getUpdateTime));
    }

    @Select("SELECT * FROM infra_codegen_table WHERE id = #{id} AND deleted = b'0' FOR UPDATE")
    CodegenTableDO selectByIdForUpdate(Long id);

    @Select("SELECT * FROM infra_codegen_table WHERE id = #{id} AND deleted = b'0' FOR SHARE")
    CodegenTableDO selectByIdForShare(Long id);

    default Long selectCountByMasterTableId(Long masterTableId) {
        return selectCount(new LambdaQueryWrapperX<CodegenTableDO>()
                .eq(CodegenTableDO::getTemplateType, CodegenTemplateTypeEnum.SUB.getType())
                .eq(CodegenTableDO::getMasterTableId, masterTableId));
    }

    default Long selectCountByParentMenuId(Long parentMenuId) {
        return selectCount(CodegenTableDO::getParentMenuId, parentMenuId);
    }

    @Select(
            """
            SELECT parent_menu_id
            FROM infra_codegen_table
            WHERE deleted = b'0'
              AND scene = 1
              AND parent_menu_id IS NOT NULL
              AND parent_menu_id <> 0
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    List<Long> selectParentMenuIdsForIntegrityAudit();

    @Select(
            """
            SELECT COUNT(*)
            FROM infra_codegen_table
            WHERE deleted = b'0'
              AND (sub_join_column_id = #{columnId}
                OR tree_parent_column_id = #{columnId}
                OR tree_name_column_id = #{columnId})
            """)
    long selectCountByReferenceColumnId(Long columnId);

    @Select(
            """
            SELECT COUNT(*)
            FROM infra_codegen_table child
            LEFT JOIN infra_codegen_table master
              ON master.id = child.master_table_id AND master.deleted = b'0'
            WHERE child.deleted = b'0'
              AND child.template_type = 15
              AND (child.master_table_id = child.id
                OR master.id IS NULL
                OR master.template_type NOT IN (10, 11, 12))
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int selectInvalidMasterTableReferenceCount();

    @Select(
            """
            SELECT COUNT(*)
            FROM infra_codegen_table t
            LEFT JOIN infra_codegen_column sub_column
              ON sub_column.id = t.sub_join_column_id AND sub_column.deleted = b'0'
            LEFT JOIN infra_codegen_column tree_parent
              ON tree_parent.id = t.tree_parent_column_id AND tree_parent.deleted = b'0'
            LEFT JOIN infra_codegen_column tree_name
              ON tree_name.id = t.tree_name_column_id AND tree_name.deleted = b'0'
            WHERE t.deleted = b'0'
              AND ((t.template_type = 15
                    AND (sub_column.id IS NULL OR sub_column.table_id <> t.id))
                OR (t.template_type = 2
                    AND (tree_parent.id IS NULL OR tree_parent.table_id <> t.id
                      OR tree_name.id IS NULL OR tree_name.table_id <> t.id
                      OR t.tree_parent_column_id = t.tree_name_column_id)))
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    int selectInvalidColumnReferenceCount();

    default List<CodegenTableDO> selectListByTemplateTypeAndMasterTableId(Integer templateType, Long masterTableId) {
        return selectList(
                CodegenTableDO::getTemplateType, templateType, CodegenTableDO::getMasterTableId, masterTableId);
    }
}

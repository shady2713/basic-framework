package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.infra.job.InfraDataIntegrityAuditJob;
import com.basicframework.module.infra.service.codegen.CodegenService;
import com.basicframework.module.system.dal.dataobject.permission.MenuDO;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import com.basicframework.module.system.service.permission.MenuService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** 使用真实 MySQL 验证代码生成配置的内部引用、菜单引用与列外键。 */
class InfraDataIntegrityIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private InfraDataIntegrityAuditJob infraDataIntegrityAuditJob;

    @Autowired
    private CodegenService codegenService;

    @Autowired
    private MenuService menuService;

    @Test
    void codegenReferencesAndForeignKeys_succeedAgainstRealServices() {
        verifyCodegenInternalReferenceIntegrity();
        verifyCodegenParentMenuReferenceIntegrity();
        verifyCodegenColumnForeignKey();
    }

    private void verifyCodegenInternalReferenceIntegrity() {
        long masterTableId = 9_086_001L;
        long subTableId = 9_086_002L;
        long joinColumnId = 9_086_012L;
        String insertCodegenTableSql =
                """
                INSERT INTO infra_codegen_table
                    (id, scene, table_name, table_comment, module_name,
                     business_name, class_name, class_comment, author, template_type, front_type,
                     master_table_id, sub_join_column_id, sub_join_many)
                VALUES (?, 1, ?, 'integration', 'infra', 'reference', ?, 'integration',
                        'integration', ?, 50, ?, ?, ?)
                """;
        jdbcTemplate.update(
                insertCodegenTableSql,
                masterTableId,
                "integration_codegen_master",
                "IntegrationCodegenMaster",
                10,
                null,
                null,
                null);
        jdbcTemplate.update(
                insertCodegenTableSql,
                subTableId,
                "integration_codegen_sub",
                "IntegrationCodegenSub",
                15,
                masterTableId,
                joinColumnId,
                true);
        jdbcTemplate.update(
                """
                INSERT INTO infra_codegen_column
                    (id, table_id, column_name, data_type, column_comment, nullable, primary_key,
                     ordinal_position, java_type, java_field, create_operation, update_operation,
                     list_operation, list_operation_result, html_type)
                VALUES (?, ?, 'master_id', 'bigint', 'integration', b'0', b'0', 1, 'Long',
                        'masterId', b'1', b'1', b'0', b'1', 'input')
                """,
                joinColumnId,
                subTableId);

        assertServiceException(
                com.basicframework.module.infra.enums.ErrorCodeConstants.CODEGEN_TABLE_HAS_SUB_TABLES.getCode(),
                () -> codegenService.deleteCodegen(masterTableId));
        jdbcTemplate.update("UPDATE infra_codegen_table SET deleted = b'1' WHERE id = ?", masterTableId);
        assertThatThrownBy(() -> infraDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_codegen_table.master_table_id 无效引用 1 条");
        jdbcTemplate.update("UPDATE infra_codegen_table SET deleted = b'0' WHERE id = ?", masterTableId);

        jdbcTemplate.update("UPDATE infra_codegen_column SET deleted = b'1' WHERE id = ?", joinColumnId);
        assertThatThrownBy(() -> infraDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_codegen_table.column_id 无效引用 1 条");
        jdbcTemplate.update("UPDATE infra_codegen_column SET deleted = b'0' WHERE id = ?", joinColumnId);
        assertThat(infraDataIntegrityAuditJob.execute("")).isEqualTo("infra 逻辑引用完整性检查通过");

        jdbcTemplate.update("DELETE FROM infra_codegen_column WHERE id = ?", joinColumnId);
        jdbcTemplate.update("DELETE FROM infra_codegen_table WHERE id = ?", subTableId);
        jdbcTemplate.update("DELETE FROM infra_codegen_table WHERE id = ?", masterTableId);
    }

    private void verifyCodegenParentMenuReferenceIntegrity() {
        Long menuId = menuService.createMenu(new MenuDO()
                .setName("代码生成父菜单引用集成")
                .setType(MenuTypeEnum.DIR.getType())
                .setParentId(MenuDO.ID_ROOT)
                .setSort(99)
                .setStatus(CommonStatusEnum.ENABLE.getStatus()));
        long codegenTableId = 9_087_101L;
        jdbcTemplate.update(
                """
                INSERT INTO infra_codegen_table
                    (id, scene, table_name, table_comment, module_name,
                     business_name, class_name, class_comment, author, template_type, front_type,
                     parent_menu_id)
                VALUES (?, 1, 'integration_codegen_menu', 'integration', 'infra',
                        'reference', 'IntegrationCodegenMenu', 'integration', 'integration', 1, 50, ?)
                """,
                codegenTableId,
                menuId);

        assertServiceException(ErrorCodeConstants.MENU_USED_BY_CODEGEN.getCode(), () -> menuService.deleteMenu(menuId));
        assertServiceException(
                ErrorCodeConstants.MENU_USED_BY_CODEGEN.getCode(),
                () -> menuService.updateMenu(
                        new MenuDO().setId(menuId).setParentId(MenuDO.ID_ROOT).setType(MenuTypeEnum.BUTTON.getType())));

        jdbcTemplate.update("UPDATE system_menu SET deleted = b'1' WHERE id = ?", menuId);
        assertThatThrownBy(() -> infraDataIntegrityAuditJob.execute(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_codegen_table.parent_menu_id 无效引用 1 条");
        jdbcTemplate.update("UPDATE system_menu SET deleted = b'0' WHERE id = ?", menuId);
        assertThat(infraDataIntegrityAuditJob.execute("")).isEqualTo("infra 逻辑引用完整性检查通过");

        codegenService.deleteCodegen(codegenTableId);
        menuService.deleteMenu(menuId);
    }

    private void verifyCodegenColumnForeignKey() {
        long tableId = 9_100_001L;
        long columnId = 9_100_001L;
        long missingTableId = 9_100_002L;
        String insertColumnSql =
                """
                INSERT INTO infra_codegen_column
                    (id, table_id, column_name, data_type, column_comment, nullable, primary_key,
                     ordinal_position, java_type, java_field, create_operation, update_operation,
                     list_operation, list_operation_result, html_type)
                VALUES (?, ?, 'id', 'bigint', 'integration', b'0', b'1', 1, 'Long', 'id',
                        b'0', b'0', b'0', b'1', 'input')
                """;
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT delete_rule FROM information_schema.referential_constraints "
                                + "WHERE constraint_schema = DATABASE() "
                                + "AND constraint_name = 'fk_codegen_column_table'",
                        String.class))
                .isEqualTo("CASCADE");
        jdbcTemplate.update(
                """
                INSERT INTO infra_codegen_table
                    (id, scene, table_name, table_comment, module_name,
                     business_name, class_name, class_comment, author, template_type, front_type)
                VALUES (?, 1, 'integration_codegen', 'integration', 'infra', 'integration',
                        'IntegrationCodegen', 'integration', 'integration', 1, 10)
                """,
                tableId);

        assertThatThrownBy(() -> jdbcTemplate.update(insertColumnSql, columnId, missingTableId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.update(insertColumnSql, columnId, tableId)).isEqualTo(1);
        assertThat(jdbcTemplate.update("DELETE FROM infra_codegen_table WHERE id = ?", tableId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM infra_codegen_column WHERE id = ?", Integer.class, columnId))
                .isZero();
    }
}

package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 MySQL 验证 V25 修复代码生成内部引用并增加形状约束。 */
@Testcontainers
class CodegenInternalReferenceMigrationIT {

    private static final long MASTER_ID = 9_085_001L;
    private static final long VALID_SUB_ID = 9_085_002L;
    private static final long VALID_TREE_ID = 9_085_003L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.8"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v25_repairsInvalidReferencesAndAddsShapeConstraints() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("24")).migrate().migrationsExecuted)
                .isEqualTo(23);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        prepareReferenceFixtures(jdbcTemplate);

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("25")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(deleted(jdbcTemplate, MASTER_ID)).isFalse();
        assertThat(deleted(jdbcTemplate, VALID_SUB_ID)).isFalse();
        assertThat(deleted(jdbcTemplate, VALID_TREE_ID)).isFalse();
        assertThat(deleted(jdbcTemplate, 9_085_004L)).isFalse();
        assertThat(referenceValue(jdbcTemplate, 9_085_004L, "master_table_id")).isNull();
        assertThat(referenceValue(jdbcTemplate, 9_085_004L, "sub_join_column_id"))
                .isNull();
        assertThat(referenceValue(jdbcTemplate, 9_085_004L, "tree_parent_column_id"))
                .isNull();
        assertThat(referenceValue(jdbcTemplate, 9_085_004L, "tree_name_column_id"))
                .isNull();

        assertThat(deleted(jdbcTemplate, 9_085_006L)).isTrue();
        assertThat(deleted(jdbcTemplate, 9_085_007L)).isTrue();
        assertThat(deleted(jdbcTemplate, 9_085_008L)).isTrue();
        assertThat(deleted(jdbcTemplate, 9_085_009L)).isTrue();
        assertThat(deleted(jdbcTemplate, 9_085_010L)).isTrue();
        assertThat(columnDeleted(jdbcTemplate, 9_085_016L)).isTrue();
        assertThat(columnDeleted(jdbcTemplate, 9_085_017L)).isTrue();
        assertThat(columnDeleted(jdbcTemplate, 9_085_018L)).isTrue();
        assertThat(columnDeleted(jdbcTemplate, 9_085_019L)).isTrue();
        assertThat(columnDeleted(jdbcTemplate, 9_085_020L)).isTrue();

        assertThat(indexCount(jdbcTemplate)).isEqualTo(4);
        assertThat(checkCount(jdbcTemplate)).isEqualTo(3);
        assertThatThrownBy(() -> insertTable(jdbcTemplate, 9_085_021L, 99, null, null, null, null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_codegen_template_type");
        assertThatThrownBy(() -> insertTable(jdbcTemplate, 9_085_022L, 15, null, null, null, null, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_codegen_sub_reference_shape");
        assertThatThrownBy(() -> insertTable(jdbcTemplate, 9_085_023L, 2, null, null, null, 9_085_013L, 9_085_013L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_codegen_tree_reference_shape");
    }

    private static void prepareReferenceFixtures(JdbcTemplate jdbcTemplate) {
        insertTable(jdbcTemplate, MASTER_ID, 10, null, null, null, null, null);
        insertTable(jdbcTemplate, VALID_SUB_ID, 15, MASTER_ID, 9_085_012L, true, null, null);
        insertTable(jdbcTemplate, VALID_TREE_ID, 2, null, null, null, 9_085_013L, 9_085_014L);
        insertTable(jdbcTemplate, 9_085_004L, 1, MASTER_ID, 9_085_012L, true, 9_085_013L, 9_085_014L);
        insertTable(jdbcTemplate, 9_085_005L, 1, null, null, null, null, null);
        insertTable(jdbcTemplate, 9_085_006L, 15, 9_085_099L, 9_085_016L, true, null, null);
        insertTable(jdbcTemplate, 9_085_007L, 15, 9_085_005L, 9_085_017L, true, null, null);
        insertTable(jdbcTemplate, 9_085_008L, 15, MASTER_ID, 9_085_012L, true, null, null);
        insertTable(jdbcTemplate, 9_085_009L, 2, null, null, null, 9_085_019L, 9_085_019L);
        insertTable(jdbcTemplate, 9_085_010L, 99, null, null, null, null, null);

        insertColumn(jdbcTemplate, 9_085_012L, VALID_SUB_ID);
        insertColumn(jdbcTemplate, 9_085_013L, VALID_TREE_ID);
        insertColumn(jdbcTemplate, 9_085_014L, VALID_TREE_ID);
        insertColumn(jdbcTemplate, 9_085_016L, 9_085_006L);
        insertColumn(jdbcTemplate, 9_085_017L, 9_085_007L);
        insertColumn(jdbcTemplate, 9_085_018L, 9_085_008L);
        insertColumn(jdbcTemplate, 9_085_019L, 9_085_009L);
        insertColumn(jdbcTemplate, 9_085_020L, 9_085_010L);
    }

    private static void insertTable(
            JdbcTemplate jdbcTemplate,
            long id,
            int templateType,
            Long masterTableId,
            Long subJoinColumnId,
            Boolean subJoinMany,
            Long treeParentColumnId,
            Long treeNameColumnId) {
        jdbcTemplate.update(
                """
                INSERT INTO infra_codegen_table
                    (id, data_source_config_id, scene, table_name, table_comment, module_name,
                     business_name, class_name, class_comment, author, template_type, front_type,
                     master_table_id, sub_join_column_id, sub_join_many,
                     tree_parent_column_id, tree_name_column_id)
                VALUES (?, 0, 1, ?, 'migration', 'infra', 'migration', ?, 'migration',
                        'integration', ?, 50, ?, ?, ?, ?, ?)
                """,
                id,
                "migration_" + id,
                "Migration" + id,
                templateType,
                masterTableId,
                subJoinColumnId,
                subJoinMany,
                treeParentColumnId,
                treeNameColumnId);
    }

    private static void insertColumn(JdbcTemplate jdbcTemplate, long id, long tableId) {
        jdbcTemplate.update(
                """
                INSERT INTO infra_codegen_column
                    (id, table_id, column_name, data_type, column_comment, nullable, primary_key,
                     ordinal_position, java_type, java_field, create_operation, update_operation,
                     list_operation, list_operation_result, html_type)
                VALUES (?, ?, ?, 'bigint', 'migration', b'0', b'0', 1, 'Long', ?,
                        b'0', b'0', b'0', b'1', 'input')
                """,
                id,
                tableId,
                "column_" + id,
                "field" + id);
    }

    private static boolean deleted(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted + 0 FROM infra_codegen_table WHERE id = ?", Boolean.class, id);
    }

    private static boolean columnDeleted(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted + 0 FROM infra_codegen_column WHERE id = ?", Boolean.class, id);
    }

    private static Long referenceValue(JdbcTemplate jdbcTemplate, long id, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM infra_codegen_table WHERE id = ?", Long.class, id);
    }

    private static int indexCount(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'infra_codegen_table'
                  AND index_name IN ('idx_master_table_id', 'idx_sub_join_column_id',
                                     'idx_tree_parent_column_id', 'idx_tree_name_column_id')
                """,
                Integer.class);
    }

    private static int checkCount(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'infra_codegen_table'
                  AND constraint_type = 'CHECK'
                  AND constraint_name IN ('chk_codegen_template_type',
                                          'chk_codegen_sub_reference_shape',
                                          'chk_codegen_tree_reference_shape')
                """,
                Integer.class);
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}

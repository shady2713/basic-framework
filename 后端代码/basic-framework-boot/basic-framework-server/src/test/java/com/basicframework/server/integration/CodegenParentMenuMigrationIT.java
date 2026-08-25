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

/** 使用真实 MySQL 验证 V26 修复代码生成父菜单引用并增加场景形状约束。 */
@Testcontainers
class CodegenParentMenuMigrationIT {

    private static final long DIRECTORY_MENU_ID = 9_087_001L;
    private static final long BUTTON_MENU_ID = 9_087_002L;
    private static final long DELETED_MENU_ID = 9_087_003L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.8"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v26_repairsInvalidParentMenusAndAddsSceneShapeConstraint() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("25")).migrate().migrationsExecuted)
                .isEqualTo(24);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        prepareFixtures(jdbcTemplate);

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("26")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(parentMenuId(jdbcTemplate, 9_087_011L)).isEqualTo(DIRECTORY_MENU_ID);
        assertThat(parentMenuId(jdbcTemplate, 9_087_012L)).isZero();
        assertThat(parentMenuId(jdbcTemplate, 9_087_013L)).isNull();
        assertThat(parentMenuId(jdbcTemplate, 9_087_014L)).isZero();
        assertThat(parentMenuId(jdbcTemplate, 9_087_015L)).isZero();
        assertThat(parentMenuId(jdbcTemplate, 9_087_016L)).isZero();
        assertThat(parentMenuId(jdbcTemplate, 9_087_017L)).isNull();
        assertThat(deleted(jdbcTemplate, 9_087_018L)).isTrue();
        assertThat(scene(jdbcTemplate, 9_087_018L)).isEqualTo(2);
        assertThat(columnDeleted(jdbcTemplate, 9_087_021L)).isTrue();
        assertThat(indexCount(jdbcTemplate)).isEqualTo(1);
        assertThat(checkCount(jdbcTemplate)).isEqualTo(1);

        assertThatThrownBy(() -> insertCodegenTable(jdbcTemplate, 9_087_031L, 2, DIRECTORY_MENU_ID))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_codegen_scene_parent_menu_shape");
        assertThatThrownBy(() -> insertCodegenTable(jdbcTemplate, 9_087_032L, 99, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_codegen_scene_parent_menu_shape");
        assertThatThrownBy(() -> insertCodegenTable(jdbcTemplate, 9_087_033L, 1, -1L))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_codegen_scene_parent_menu_shape");
    }

    private static void prepareFixtures(JdbcTemplate jdbcTemplate) {
        insertMenu(jdbcTemplate, DIRECTORY_MENU_ID, 1, false);
        insertMenu(jdbcTemplate, BUTTON_MENU_ID, 3, false);
        insertMenu(jdbcTemplate, DELETED_MENU_ID, 1, true);

        insertCodegenTable(jdbcTemplate, 9_087_011L, 1, DIRECTORY_MENU_ID);
        insertCodegenTable(jdbcTemplate, 9_087_012L, 1, 0L);
        insertCodegenTable(jdbcTemplate, 9_087_013L, 1, null);
        insertCodegenTable(jdbcTemplate, 9_087_014L, 1, 9_087_099L);
        insertCodegenTable(jdbcTemplate, 9_087_015L, 1, BUTTON_MENU_ID);
        insertCodegenTable(jdbcTemplate, 9_087_016L, 1, DELETED_MENU_ID);
        insertCodegenTable(jdbcTemplate, 9_087_017L, 2, DIRECTORY_MENU_ID);
        insertCodegenTable(jdbcTemplate, 9_087_018L, 99, DIRECTORY_MENU_ID);
        jdbcTemplate.update(
                """
                INSERT INTO infra_codegen_column
                    (id, table_id, column_name, data_type, column_comment, nullable, primary_key,
                     ordinal_position, java_type, java_field, create_operation, update_operation,
                     list_operation, list_operation_result, html_type)
                VALUES (?, ?, 'id', 'bigint', 'migration', b'0', b'1', 1, 'Long', 'id',
                        b'0', b'0', b'0', b'1', 'input')
                """,
                9_087_021L,
                9_087_018L);
    }

    private static void insertMenu(JdbcTemplate jdbcTemplate, long id, int type, boolean deleted) {
        jdbcTemplate.update(
                """
                INSERT INTO system_menu
                    (id, name, permission, type, sort, parent_id, path, icon, component, status, deleted)
                VALUES (?, ?, '', ?, 0, 0, '', '', '', 0, ?)
                """,
                id,
                "migration-" + id,
                type,
                deleted);
    }

    private static void insertCodegenTable(JdbcTemplate jdbcTemplate, long id, int scene, Long parentMenuId) {
        jdbcTemplate.update(
                """
                INSERT INTO infra_codegen_table
                    (id, data_source_config_id, scene, table_name, table_comment, module_name,
                     business_name, class_name, class_comment, author, template_type, front_type,
                     parent_menu_id)
                VALUES (?, 0, ?, ?, 'migration', 'infra', 'migration', ?, 'migration',
                        'integration', 1, 50, ?)
                """,
                id,
                scene,
                "migration_" + id,
                "Migration" + id,
                parentMenuId);
    }

    private static Long parentMenuId(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT parent_menu_id FROM infra_codegen_table WHERE id = ?", Long.class, id);
    }

    private static int scene(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject("SELECT scene FROM infra_codegen_table WHERE id = ?", Integer.class, id);
    }

    private static boolean deleted(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted + 0 FROM infra_codegen_table WHERE id = ?", Boolean.class, id);
    }

    private static boolean columnDeleted(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted + 0 FROM infra_codegen_column WHERE id = ?", Boolean.class, id);
    }

    private static int indexCount(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'infra_codegen_table'
                  AND index_name = 'idx_parent_menu_id'
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
                  AND constraint_name = 'chk_codegen_scene_parent_menu_shape'
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

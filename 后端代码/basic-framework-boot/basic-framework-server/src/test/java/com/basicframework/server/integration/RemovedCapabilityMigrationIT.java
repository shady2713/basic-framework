package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 使用真实 MySQL 验证 V32 从已有数据库移除废弃的生成器能力。 */
@Testcontainers
class RemovedCapabilityMigrationIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
                            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb")
                    .asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("basic_framework")
            .withUsername("root")
            .withPassword("integration-only");

    @Test
    void v32_removesGeneratorTablesMenusPermissionsAndDictionaries() {
        DataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        assertThat(flyway(dataSource, MigrationVersion.fromVersion("31")).migrate().migrationsExecuted)
                .isEqualTo(30);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertThat(tableCount(jdbcTemplate, "infra_codegen_table")).isEqualTo(1);
        assertThat(tableCount(jdbcTemplate, "infra_codegen_column")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_role_menu WHERE role_id = ? AND menu_id = ?",
                        Integer.class,
                        1L,
                        115L))
                .isPositive();

        assertThat(flyway(dataSource, MigrationVersion.fromVersion("32")).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(tableCount(jdbcTemplate, "infra_codegen_table")).isZero();
        assertThat(tableCount(jdbcTemplate, "infra_codegen_column")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_menu WHERE id = 115 OR parent_id = 115 "
                                + "OR permission LIKE 'infra:codegen:%'",
                        Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_role_menu WHERE role_id = ? AND menu_id = ?",
                        Integer.class,
                        1L,
                        115L))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_data WHERE dict_type LIKE 'infra_codegen_%'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_dict_type WHERE type LIKE 'infra_codegen_%'", Integer.class))
                .isZero();
    }

    private static int tableCount(JdbcTemplate jdbcTemplate, String tableName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        return flywayConfiguration(dataSource).target(target).load();
    }

    private static FluentConfiguration flywayConfiguration(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }
}

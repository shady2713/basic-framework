package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.crm.dal.dataobject.crm.CustomerDO;
import com.basicframework.module.crm.service.crm.CustomerService;
import com.basicframework.module.infra.job.job.JobLogCleanJob;
import com.basicframework.module.system.job.SystemDataRetentionCleanJob;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;

/** 使用真实 MySQL 验证空库迁移、生命周期列、业务持久化与清理任务。 */
class PersistenceLifecycleIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private JobLogCleanJob jobLogCleanJob;

    @Autowired
    private SystemDataRetentionCleanJob systemDataRetentionCleanJob;

    @Test
    void migrationLifecyclePersistenceAndJobs_succeedAgainstRealServices() throws Exception {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(migrationCount).isEqualTo(29);
        Integer quartzTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND LEFT(table_name, 5) = 'QRTZ_'",
                Integer.class);
        assertThat(quartzTableCount).isEqualTo(11);
        Integer mfaTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('system_user_mfa_factor', 'system_user_mfa_recovery_code')",
                Integer.class);
        assertThat(mfaTableCount).isEqualTo(2);
        verifyLifecycleColumns();
        assertThat(scheduler.isStarted()).isTrue();
        verifyCustomerPersistence();
        verifyBuiltInJobIsReentrant();
        verifySystemDataRetentionJob();
    }

    private void verifyBuiltInJobIsReentrant() throws Exception {
        jdbcTemplate.update("INSERT INTO infra_job_log "
                + "(job_id, handler_name, execute_index, begin_time, status, create_time) "
                + "VALUES (25, 'jobLogCleanJob', 1, NOW() - INTERVAL 30 DAY, 1, NOW() - INTERVAL 30 DAY)");

        assertThat(jobLogCleanJob.execute("")).contains("1 个");
        assertThat(jobLogCleanJob.execute("")).contains("0 个");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM infra_job_log", Integer.class))
                .isZero();
    }

    private void verifySystemDataRetentionJob() {
        jdbcTemplate.update(
                """
                INSERT INTO system_login_log
                    (log_type, trace_id, user_id, user_type, username, result, user_ip, user_agent, create_time)
                VALUES
                    (100, 'retention-old', 1, 2, 'retention', 0, '127.0.0.1', 'integration', '2000-01-01'),
                    (100, 'retention-current', 1, 2, 'retention', 0, '127.0.0.1', 'integration', NOW())
                """);
        jdbcTemplate.update(
                """
                INSERT INTO system_operate_log
                    (trace_id, user_id, user_type, type, sub_type, biz_id, action, success, extra, create_time)
                VALUES
                    ('retention-old', 1, 2, 'retention', 'old', 1, 'old', b'1', '', '2000-01-01'),
                    ('retention-current', 1, 2, 'retention', 'current', 1, 'current', b'1', '', NOW())
                """);
        jdbcTemplate.update(
                """
                INSERT INTO system_sms_log
                    (channel_id, channel_code, template_id, template_code, template_type,
                     template_content, template_params, api_template_id, mobile,
                     send_status, receive_status, create_time)
                VALUES
                    (1, 'retention', 1, 'retention-old', 1, 'old', '{}', '1', '13900000001', 0, 0, '2000-01-01'),
                    (1, 'retention', 1, 'retention-current', 1, 'current', '{}', '1', '13900000001', 0, 0, NOW())
                """);
        jdbcTemplate.update(
                """
                INSERT INTO system_notify_message
                    (user_id, user_type, template_id, template_code, template_nickname,
                     template_content, template_type, template_params, read_status, create_time)
                VALUES
                    (1, 2, 1, 'retention-old-read', 'system', 'old', 1, '{}', b'1', '2000-01-01'),
                    (1, 2, 1, 'retention-old-unread', 'system', 'old', 1, '{}', b'0', '2000-01-01'),
                    (1, 2, 1, 'retention-current-read', 'system', 'current', 1, '{}', b'1', NOW())
                """);

        assertThat(systemDataRetentionCleanJob.execute("")).isEqualTo("登录日志 1，操作日志 1，短信日志 1，已读站内信 1，过期会话 0");
        assertThat(systemDataRetentionCleanJob.execute("")).isEqualTo("登录日志 0，操作日志 0，短信日志 0，已读站内信 0，过期会话 0");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_login_log WHERE trace_id LIKE 'retention-%'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_operate_log WHERE trace_id LIKE 'retention-%'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_sms_log WHERE template_code LIKE 'retention-%'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_notify_message WHERE template_code LIKE 'retention-%'",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM infra_job WHERE id BETWEEN 25 AND 30 AND status = 1", Integer.class))
                .isEqualTo(6);
    }

    private void verifyCustomerPersistence() {
        CustomerDO customer = new CustomerDO();
        customer.setName("集成测试客户");
        customer.setMobile(TEST_MOBILE);
        customer.setAmount(new BigDecimal("100000.00"));
        customer.setContractDate(LocalDate.of(2026, 8, 1));

        Long customerId = customerService.createCustomer(customer);

        assertThat(customerId).isPositive();
        CustomerDO stored = customerService.getCustomer(customerId);
        assertThat(stored)
                .extracting(
                        CustomerDO::getName, CustomerDO::getMobile, CustomerDO::getAmount, CustomerDO::getContractDate)
                .containsExactly("集成测试客户", TEST_MOBILE, new BigDecimal("100000.00"), LocalDate.of(2026, 8, 1));
        assertThat(stored.getCreateTime()).isNotNull();
        assertThat(stored.getUpdateTime()).isNotNull();

        PageParam pageParam = new PageParam();
        PageResult<CustomerDO> page = customerService.getCustomerPage(pageParam, "集成测试", TEST_MOBILE);
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).extracting(CustomerDO::getId).containsExactly(customerId);

        customerService.deleteCustomer(customerId);

        assertThat(customerService.getCustomer(customerId)).isNull();
        Boolean deleted =
                jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id = ?", Boolean.class, customerId);
        assertThat(deleted).isTrue();
    }

    private void verifyLifecycleColumns() {
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT table_name
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND column_name = 'deleted'
                        ORDER BY table_name
                        """,
                        String.class))
                .containsExactly(
                        "crm_customer",
                        "infra_codegen_column",
                        "infra_codegen_table",
                        "infra_config",
                        "infra_file_config",
                        "infra_job",
                        "system_dept",
                        "system_dict_data",
                        "system_dict_type",
                        "system_menu",
                        "system_notice",
                        "system_notify_template",
                        "system_post",
                        "system_role",
                        "system_sms_channel",
                        "system_sms_template",
                        "system_users");
    }
}

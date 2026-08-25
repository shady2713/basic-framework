package com.basicframework.module.infra.job.logger;

import com.basicframework.framework.quartz.core.handler.JobHandler;
import com.basicframework.module.infra.framework.retention.config.InfraDataRetentionProperties;
import com.basicframework.module.infra.service.logger.ApiErrorLogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 物理删除 N 天前的错误日志的 Job
 *
 */
@Slf4j
@Component
public class ErrorLogCleanJob implements JobHandler {

    @Resource
    private ApiErrorLogService apiErrorLogService;

    @Resource
    private InfraDataRetentionProperties retentionProperties;

    @Override
    public String execute(String param) {
        Integer count = apiErrorLogService.cleanErrorLog(
                retentionProperties.getErrorLogDays(),
                retentionProperties.getBatchSize(),
                retentionProperties.getMaxBatches());
        log.info("[execute][定时执行清理错误日志数量 ({}) 个]", count);
        return String.format("定时执行清理错误日志数量 %s 个", count);
    }
}

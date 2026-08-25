package com.basicframework.framework.quartz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步任务线程池配置项
 *
 */
@ConfigurationProperties("basic-framework.async")
@Data
public class BasicFrameworkAsyncProperties {

    /**
     * {@link #corePoolSize} 默认值
     */
    private static final Integer CORE_POOL_SIZE_DEFAULT = 8;

    /**
     * {@link #maxPoolSize} 默认值
     */
    private static final Integer MAX_POOL_SIZE_DEFAULT = 32;

    /**
     * {@link #queueCapacity} 默认值
     */
    private static final Integer QUEUE_CAPACITY_DEFAULT = 100;

    /**
     * {@link #keepAliveSeconds} 默认值
     */
    private static final Integer KEEP_ALIVE_SECONDS_DEFAULT = 60;

    /**
     * {@link #awaitTerminationSeconds} 默认值，与 spring.lifecycle.timeout-per-shutdown-phase 对齐
     */
    private static final Integer AWAIT_TERMINATION_SECONDS_DEFAULT = 30;

    /**
     * 核心线程数
     */
    private Integer corePoolSize = CORE_POOL_SIZE_DEFAULT;

    /**
     * 最大线程数
     */
    private Integer maxPoolSize = MAX_POOL_SIZE_DEFAULT;

    /**
     * 队列容量
     */
    private Integer queueCapacity = QUEUE_CAPACITY_DEFAULT;

    /**
     * 空闲线程存活秒数
     */
    private Integer keepAliveSeconds = KEEP_ALIVE_SECONDS_DEFAULT;

    /**
     * 优雅关闭时等待任务完成的最长秒数
     */
    private Integer awaitTerminationSeconds = AWAIT_TERMINATION_SECONDS_DEFAULT;
}

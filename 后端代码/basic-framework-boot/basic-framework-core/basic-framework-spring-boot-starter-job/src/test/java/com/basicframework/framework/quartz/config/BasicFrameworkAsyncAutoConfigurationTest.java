package com.basicframework.framework.quartz.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class BasicFrameworkAsyncAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TaskExecutionAutoConfiguration.class,
                    TaskSchedulingAutoConfiguration.class,
                    BasicFrameworkAsyncAutoConfiguration.class));

    @Test
    void customizerConfiguresSpringBootExecutorWithoutBeanNameConflict() {
        contextRunner
                .withUserConfiguration(CompetingTaskExecutorConfiguration.class)
                .withPropertyValues(
                        "basic-framework.async.core-pool-size=3",
                        "basic-framework.async.max-pool-size=7",
                        "basic-framework.async.queue-capacity=19",
                        "basic-framework.async.keep-alive-seconds=45")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ThreadPoolTaskExecutor.class);
                    ThreadPoolTaskExecutor executor =
                            context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(7);
                    assertThat(executor.getQueueCapacity()).isEqualTo(19);
                    assertThat(executor.getKeepAliveSeconds()).isEqualTo(45);
                    assertThat(executor.getThreadNamePrefix()).isEqualTo("async-");
                    assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
                            .isSameAs(executor);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class CompetingTaskExecutorConfiguration {}
}

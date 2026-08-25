package com.basicframework.module.infra.service.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.basicframework.module.infra.dal.dataobject.job.JobLogDO;
import com.basicframework.module.infra.dal.mysql.job.JobLogMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class JobLogServiceImplTest {

    @InjectMocks
    private JobLogServiceImpl service;

    @Mock
    private JobLogMapper jobLogMapper;

    @Test
    void updateJobLogResultAsync_logsExceptionWithoutExecutionResult() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(jobLogMapper).updateById(any(JobLogDO.class));
        Logger logger = (Logger) LoggerFactory.getLogger(JobLogServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        boolean previousAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);

        try {
            service.updateJobLogResultAsync(1L, LocalDateTime.now(), 10, false, "secret-token");
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(previousAdditive);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("secret-token");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }
}

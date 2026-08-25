package com.basicframework.framework.quartz.core.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.quartz.core.enums.JobDataKeyEnum;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;

@ExtendWith(MockitoExtension.class)
class SchedulerManagerTest {

    private static final String HANDLER_NAME = "sampleJob";

    @Mock
    private Scheduler scheduler;

    private SchedulerManager schedulerManager;

    @BeforeEach
    void setUp() {
        schedulerManager = new SchedulerManager(scheduler);
    }

    @Test
    void addJob_persistsWithoutRecoveryAndSkipsMissedCronFirings() throws Exception {
        when(scheduler.scheduleJob(any(JobDetail.class), any(Trigger.class))).thenReturn(new Date());

        schedulerManager.addJob(1L, HANDLER_NAME, "param", "0 0 0 * * ?", 2, 1000);

        ArgumentCaptor<JobDetail> jobCaptor = ArgumentCaptor.forClass(JobDetail.class);
        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(jobCaptor.capture(), triggerCaptor.capture());

        JobDetail jobDetail = jobCaptor.getValue();
        assertThat(jobDetail.requestsRecovery()).isFalse();
        assertThat(jobDetail.isConcurrentExecutionDisallowed()).isTrue();
        assertThat(jobDetail.getJobDataMap().getLong(JobDataKeyEnum.JOB_ID.name()))
                .isEqualTo(1L);

        CronTrigger trigger = (CronTrigger) triggerCaptor.getValue();
        assertThat(trigger.getMisfireInstruction()).isEqualTo(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        assertThat(trigger.getJobDataMap().getString(JobDataKeyEnum.JOB_HANDLER_PARAM.name()))
                .isEqualTo("param");
    }

    @Test
    void jobExists_delegatesToQuartz() throws Exception {
        when(scheduler.checkExists(new JobKey(HANDLER_NAME))).thenReturn(true);

        assertThat(schedulerManager.jobExists(HANDLER_NAME)).isTrue();
    }
}

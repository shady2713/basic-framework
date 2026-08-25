package com.basicframework.module.system.controller.admin.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.controller.admin.sms.vo.channel.SmsChannelRespVO;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.service.sms.SmsChannelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SmsChannelControllerTest {

    @Test
    void getSmsChannel_returnsOnlySecretConfigurationState() {
        SmsChannelService service = mock(SmsChannelService.class);
        SmsChannelController controller = new SmsChannelController();
        ReflectionTestUtils.setField(controller, "smsChannelService", service);
        when(service.getSmsChannel(1L))
                .thenReturn(new SmsChannelDO().setId(1L).setApiSecretCiphertext("v1.encrypted.value"));

        SmsChannelRespVO response = controller.getSmsChannel(1L).getData();

        assertThat(response.getApiSecretConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("encrypted");
    }
}

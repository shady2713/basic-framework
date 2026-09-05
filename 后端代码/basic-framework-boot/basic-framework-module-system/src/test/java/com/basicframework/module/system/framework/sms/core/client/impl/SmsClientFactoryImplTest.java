package com.basicframework.module.system.framework.sms.core.client.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.enums.SmsChannelEnum;
import com.basicframework.module.system.framework.sms.core.property.SmsChannelProperties;
import org.junit.jupiter.api.Test;

class SmsClientFactoryImplTest {

    private final SmsClientFactoryImpl factory = new SmsClientFactoryImpl();

    @Test
    void createOrUpdateSmsClient_reusesTheSameProviderClientForAConfigRefresh() {
        SmsClient first = factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "initial-signature"));
        SmsClient refreshed = factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "new-signature"));

        assertThat(refreshed).isSameAs(first).isInstanceOf(AliyunSmsClient.class);
        assertThat(((AbstractSmsClient) refreshed).properties.getSignature()).isEqualTo("new-signature");
    }

    @Test
    void createOrUpdateSmsClient_replacesTheCachedClientWhenTheProviderChanges() {
        SmsClient aliyunClient =
                factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "aliyun-signature"));

        SmsClient tencentClient =
                factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.TENCENT, "tencent-signature"));

        assertThat(aliyunClient).isInstanceOf(AliyunSmsClient.class);
        assertThat(tencentClient).isNotSameAs(aliyunClient).isInstanceOf(TencentSmsClient.class);
        assertThat(factory.getSmsClient(10L)).isSameAs(tencentClient);
    }

    @Test
    void createTransientSmsClient_doesNotReplaceTheCachedClient() {
        SmsClient cached = factory.createOrUpdateSmsClient(properties(10L, SmsChannelEnum.ALIYUN, "cached-signature"));

        SmsClient transientClient =
                factory.createTransientSmsClient(properties(10L, SmsChannelEnum.TENCENT, "transient-signature"));

        assertThat(transientClient).isInstanceOf(TencentSmsClient.class);
        assertThat(factory.getSmsClient(10L)).isSameAs(cached);
    }

    @Test
    void getSmsClientByProviderCode_exposesTheDedicatedReceiptParser() {
        assertThat(factory.getSmsClient(SmsChannelEnum.ALIYUN.getCode())).isInstanceOf(AliyunSmsClient.class);
        assertThat(factory.getSmsClient(SmsChannelEnum.TENCENT.getCode())).isInstanceOf(TencentSmsClient.class);
    }

    private static SmsChannelProperties properties(Long id, SmsChannelEnum channel, String signature) {
        return new SmsChannelProperties()
                .setId(id)
                .setCode(channel.getCode())
                .setSignature(signature)
                .setApiKey(channel == SmsChannelEnum.TENCENT ? "secret-id app-id" : "access-key")
                .setApiSecret("api-secret");
    }
}

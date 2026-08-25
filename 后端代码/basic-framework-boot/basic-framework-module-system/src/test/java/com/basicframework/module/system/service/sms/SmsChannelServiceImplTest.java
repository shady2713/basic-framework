package com.basicframework.module.system.service.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.system.dal.dataobject.sms.SmsChannelDO;
import com.basicframework.module.system.dal.mysql.sms.SmsChannelMapper;
import com.basicframework.module.system.enums.ErrorCodeConstants;
import com.basicframework.module.system.framework.sms.core.client.SmsClient;
import com.basicframework.module.system.framework.sms.core.client.SmsClientFactory;
import com.basicframework.module.system.framework.sms.core.property.SmsChannelProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class SmsChannelServiceImplTest {

    @InjectMocks
    private SmsChannelServiceImpl service;

    @Mock
    private SmsClientFactory smsClientFactory;

    @Mock
    private SmsChannelMapper smsChannelMapper;

    @Mock
    private ObjectProvider<SmsTemplateService> smsTemplateServiceProvider;

    @Mock
    private SmsTemplateService smsTemplateService;

    @Mock
    private CredentialCipher credentialCipher;

    @Test
    void createSmsChannel_encryptsSecretAndClearsPlaintextBeforeInsert() {
        SmsChannelDO channel = channel(null).setApiSecret("request-secret");
        when(credentialCipher.encrypt("request-secret", "sms-channel:api-secret"))
                .thenReturn("v1.encrypted.value");

        service.createSmsChannel(channel);

        assertThat(channel.getApiSecret()).isNull();
        assertThat(channel.getApiSecretCiphertext()).isEqualTo("v1.encrypted.value");
        verify(smsChannelMapper).insert(channel);
    }

    @Test
    void deleteSmsChannel_locksBeforeCheckingTemplates() {
        when(smsChannelMapper.selectByIdForUpdate(1L)).thenReturn(channel(1L));
        when(smsTemplateServiceProvider.getObject()).thenReturn(smsTemplateService);
        when(smsTemplateService.getSmsTemplateCountByChannelId(1L)).thenReturn(1L);

        assertServiceException(ErrorCodeConstants.SMS_CHANNEL_HAS_CHILDREN, () -> service.deleteSmsChannel(1L));

        InOrder inOrder = inOrder(smsChannelMapper, smsTemplateService);
        inOrder.verify(smsChannelMapper).selectByIdForUpdate(1L);
        inOrder.verify(smsTemplateService).getSmsTemplateCountByChannelId(1L);
        verify(smsChannelMapper, never()).deleteById(1L);
    }

    @Test
    void deleteSmsChannelList_locksDistinctChannelsInStableOrderAndCleansClients() {
        when(smsChannelMapper.selectByIdForUpdate(1L)).thenReturn(channel(1L));
        when(smsChannelMapper.selectByIdForUpdate(2L)).thenReturn(channel(2L));
        when(smsTemplateServiceProvider.getObject()).thenReturn(smsTemplateService);

        service.deleteSmsChannelList(List.of(2L, 1L, 2L));

        InOrder inOrder = inOrder(smsChannelMapper);
        inOrder.verify(smsChannelMapper).selectByIdForUpdate(1L);
        inOrder.verify(smsChannelMapper).selectByIdForUpdate(2L);
        verify(smsChannelMapper).deleteByIds(List.of(1L, 2L));
        verify(smsClientFactory).removeSmsClient(1L);
        verify(smsClientFactory).removeSmsClient(2L);
    }

    @Test
    void updateSmsChannel_locksRowAndCleansCachedClient() {
        SmsChannelDO update = channel(1L).setCode("aliyun");
        when(smsChannelMapper.selectByIdForUpdate(1L))
                .thenReturn(channel(1L).setApiSecretCiphertext("v1.existing.value"));

        service.updateSmsChannel(update);

        InOrder inOrder = inOrder(smsChannelMapper, smsClientFactory);
        inOrder.verify(smsChannelMapper).selectByIdForUpdate(1L);
        inOrder.verify(smsChannelMapper).updateById(update);
        inOrder.verify(smsClientFactory).removeSmsClient(1L);
        assertThat(update.getApiSecretCiphertext()).isEqualTo("v1.existing.value");
        verify(credentialCipher, never())
                .encrypt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getSmsClient_decryptsSecretOnlyForClientInitialization() {
        SmsChannelDO channel = channel(1L).setApiSecretCiphertext("v1.encrypted.value");
        SmsClient client = org.mockito.Mockito.mock(SmsClient.class);
        when(smsChannelMapper.selectById(1L)).thenReturn(channel);
        when(credentialCipher.decrypt("v1.encrypted.value", "sms-channel:api-secret"))
                .thenReturn("runtime-secret");
        when(smsClientFactory.createOrUpdateSmsClient(org.mockito.ArgumentMatchers.any()))
                .thenReturn(client);

        assertThat(service.getSmsClient(1L)).isSameAs(client);

        org.mockito.ArgumentCaptor<SmsChannelProperties> propertiesCaptor =
                org.mockito.ArgumentCaptor.forClass(SmsChannelProperties.class);
        verify(smsClientFactory).createOrUpdateSmsClient(propertiesCaptor.capture());
        assertThat(propertiesCaptor.getValue().getApiSecret()).isEqualTo("runtime-secret");
        assertThat(channel.getApiSecret()).isNull();
    }

    @Test
    void getSmsClient_returnsNullForMissingChannelWithoutCreatingClient() {
        assertThat(service.getSmsClient(99L)).isNull();

        verify(smsClientFactory, never()).createOrUpdateSmsClient(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createTransientSmsClient_usesProvidedSnapshotWithoutSharedCache() {
        SmsChannelDO channel = channel(1L).setApiKey("snapshot-key").setApiSecret("snapshot-secret");
        SmsClient client = org.mockito.Mockito.mock(SmsClient.class);
        when(smsClientFactory.createTransientSmsClient(org.mockito.ArgumentMatchers.any()))
                .thenReturn(client);

        assertThat(service.createTransientSmsClient(channel)).isSameAs(client);

        org.mockito.ArgumentCaptor<SmsChannelProperties> propertiesCaptor =
                org.mockito.ArgumentCaptor.forClass(SmsChannelProperties.class);
        verify(smsClientFactory).createTransientSmsClient(propertiesCaptor.capture());
        assertThat(propertiesCaptor.getValue())
                .extracting(
                        SmsChannelProperties::getId,
                        SmsChannelProperties::getCode,
                        SmsChannelProperties::getApiKey,
                        SmsChannelProperties::getApiSecret)
                .containsExactly(1L, "aliyun", "snapshot-key", "snapshot-secret");
        verify(smsClientFactory, never()).createOrUpdateSmsClient(org.mockito.ArgumentMatchers.any());
    }

    private static SmsChannelDO channel(Long id) {
        return new SmsChannelDO().setId(id).setCode("aliyun");
    }

    private static void assertServiceException(ErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(errorCode.getCode());
    }
}

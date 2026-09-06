package com.basicframework.module.system.framework.sms.core.client.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.basicframework.framework.common.core.KeyValue;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsReceiveRespDTO;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsSendRespDTO;
import com.basicframework.module.system.framework.sms.core.client.dto.SmsTemplateRespDTO;
import com.basicframework.module.system.framework.sms.core.enums.SmsTemplateAuditStatusEnum;
import com.basicframework.module.system.framework.sms.core.property.SmsChannelProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TencentSmsClientTest {

    @Test
    void constructorRejectsIncompleteOrMalformedCredentials() {
        SmsChannelProperties properties = properties();
        properties.setApiSecret("");
        assertThatIllegalArgumentException().isThrownBy(() -> new TencentSmsClient(properties));

        properties.setApiSecret("secret-key");
        properties.setApiKey("secret-id-only");
        assertThatIllegalArgumentException().isThrownBy(() -> new TencentSmsClient(properties));
    }

    @Test
    void sendSmsBuildsSignedRequestAndMapsSuccess() {
        AtomicReference<HttpRequest> request = new AtomicReference<>();
        TencentSmsClient client = new TencentSmsClient(properties(), (url, headers, body) -> {
            request.set(new HttpRequest(url, headers, body));
            return """
                    {"Response":{"RequestId":"request-1","SendStatusSet":[
                      {"Code":"Ok","SerialNo":"serial-1","Message":"send success"}]}}
                    """;
        });

        SmsSendRespDTO response = client.sendSms(12L, "+8613900000000", "100", List.of(new KeyValue<>("name", "张三")));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getApiRequestId()).isEqualTo("request-1");
        assertThat(response.getSerialNo()).isEqualTo("serial-1");
        assertThat(response.getApiMsg()).isEqualTo("send success");
        assertThat(request.get().url()).isEqualTo("https://sms.tencentcloudapi.com");
        assertThat(request.get().headers())
                .containsEntry("Host", "sms.tencentcloudapi.com")
                .containsEntry("X-TC-Action", "SendSms")
                .containsEntry("X-TC-Version", "2021-01-11")
                .containsKey("Authorization");
        assertThat(request.get().headers().get("Authorization"))
                .startsWith("TC3-HMAC-SHA256 Credential=secret-id/")
                .doesNotContain("secret-key");
        assertThat(request.get().body())
                .contains("\"SmsSdkAppId\":\"sdk-app-id\"")
                .contains("\"TemplateId\":\"100\"")
                .contains("\"PhoneNumberSet\":[\"+8613900000000\"]")
                .contains("\"TemplateParamSet\":[\"张三\"]");
    }

    @Test
    void sendSmsMapsProviderErrorWithoutAssumingStatusArray() {
        TencentSmsClient client = new TencentSmsClient(
                properties(),
                (url, headers, body) ->
                        """
                {"Response":{"RequestId":"request-2","Error":{
                  "Code":"FailedOperation.SignatureIncorrectOrUnapproved",
                  "Message":"signature unavailable"}}}
                """);

        SmsSendRespDTO response = client.sendSms(13L, "+8613900000000", "100", List.of());

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getApiRequestId()).isEqualTo("request-2");
        assertThat(response.getApiCode()).isEqualTo("FailedOperation.SignatureIncorrectOrUnapproved");
        assertThat(response.getApiMsg()).isEqualTo("signature unavailable");
    }

    @Test
    void getSmsTemplateMapsProviderStatus() {
        TencentSmsClient client = new TencentSmsClient(
                properties(),
                (url, headers, body) ->
                        """
                {"Response":{"DescribeTemplateStatusSet":[{
                  "TemplateContent":"hello {1}","StatusCode":0,"ReviewReply":"approved"}]}}
                """);

        SmsTemplateRespDTO template = client.getSmsTemplate("100");

        assertThat(template.getId()).isEqualTo("100");
        assertThat(template.getContent()).isEqualTo("hello {1}");
        assertThat(template.getAuditStatus()).isEqualTo(SmsTemplateAuditStatusEnum.SUCCESS.getStatus());
        assertThat(template.getAuditReason()).isEqualTo("approved");
    }

    @Test
    void parseReceiveStatusKeepsProviderSerialForDatabaseCorrelation() {
        TencentSmsClient client = new TencentSmsClient(properties());

        List<SmsReceiveRespDTO> receipts = client.parseSmsReceiveStatus(
                """
                [{
                  "user_receive_time": "2026-08-30 10:00:00",
                  "mobile": "8613900000004",
                  "report_status": "SUCCESS",
                  "errmsg": "DELIVRD",
                  "description": "delivered",
                  "sid": "provider-serial"
                }]
                """);

        assertThat(receipts).singleElement().satisfies(receipt -> {
            assertThat(receipt.getLogId()).isNull();
            assertThat(receipt.getSerialNo()).isEqualTo("provider-serial");
            assertThat(receipt.getSuccess()).isTrue();
        });
    }

    @Test
    void auditStatusConversionIsClosedForUnknownProviderValues() {
        TencentSmsClient client = new TencentSmsClient(properties(), (url, headers, body) -> "{}");

        assertThat(client.convertSmsTemplateAuditStatus(1)).isEqualTo(SmsTemplateAuditStatusEnum.CHECKING.getStatus());
        assertThat(client.convertSmsTemplateAuditStatus(0)).isEqualTo(SmsTemplateAuditStatusEnum.SUCCESS.getStatus());
        assertThat(client.convertSmsTemplateAuditStatus(-1)).isEqualTo(SmsTemplateAuditStatusEnum.FAIL.getStatus());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> client.convertSmsTemplateAuditStatus(2))
                .withMessageContaining("未知审核状态");
    }

    private static SmsChannelProperties properties() {
        SmsChannelProperties properties = new SmsChannelProperties();
        properties.setId(1L);
        properties.setCode("tencent");
        properties.setSignature("signature");
        properties.setApiKey("secret-id sdk-app-id");
        properties.setApiSecret("secret-key");
        return properties;
    }

    private record HttpRequest(String url, Map<String, String> headers, String body) {}
}

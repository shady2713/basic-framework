package com.basicframework.framework.web.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveDataSanitizerTest {

    @Test
    void sanitizeJson_removesCredentialAndSensitivePiiKeysRecursively() {
        String json =
                """
                {
                  "username": "visible",
                  "old_password": "${TEST_SECRET}",
                  "NEW-PASSWORD": "${TEST_SECRET}",
                  "nested": {
                    "apiSecret": "${TEST_SECRET}",
                    "ACCESS_KEY": "${TEST_SECRET}",
                    "response-key": "${TEST_SECRET}",
                    "idCard": "${TEST_SECRET}"
                  },
                  "items": [{"refreshToken": "${TEST_SECRET}", "name": "kept"}]
                }
                """;

        JsonNode sanitized = JsonUtils.parseTree(SensitiveDataSanitizer.sanitizeJson(json));

        assertThat(sanitized.get("username").asText()).isEqualTo("visible");
        assertThat(sanitized.has("old_password")).isFalse();
        assertThat(sanitized.has("NEW-PASSWORD")).isFalse();
        assertThat(sanitized.get("nested").isEmpty()).isTrue();
        assertThat(sanitized.at("/items/0/refreshToken").isMissingNode()).isTrue();
        assertThat(sanitized.at("/items/0/name").asText()).isEqualTo("kept");
    }

    @Test
    void sanitizeJson_removesAdditionalKeyUsingNormalizedMatch() {
        String sanitized = SensitiveDataSanitizer.sanitizeJson(
                "{\"security_pin\":\"${TEST_SECRET}\",\"name\":\"kept\"}", "securityPin");

        assertThat(sanitized).doesNotContain("security_pin", "${TEST_SECRET}").contains("name", "kept");
    }

    @Test
    void sanitizeMap_removesDefaultAndAdditionalKeysWithoutChangingInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("Api-Key", "${TEST_SECRET}");
        input.put("pin", "${TEST_SECRET}");
        input.put("name", "kept");

        String sanitized = SensitiveDataSanitizer.sanitizeMap(input, "PIN");

        assertThat(sanitized).doesNotContain("${TEST_SECRET}").contains("name", "kept");
        assertThat(input).containsKeys("Api-Key", "pin", "name");
    }

    @Test
    void sanitizeJson_returnsPlaceholderForInvalidJson() {
        assertThat(SensitiveDataSanitizer.sanitizeJson("not-json"))
                .isEqualTo(SensitiveDataSanitizer.SANITIZE_FAILURE_PLACEHOLDER);
    }

    @Test
    void sanitizeResult_removesSensitiveDataButKeepsResponseMetadata() {
        CommonResult<Map<String, String>> result = CommonResult.success(Map.of(
                "accessToken", "${TEST_SECRET}",
                "name", "kept"));

        JsonNode sanitized = JsonUtils.parseTree(SensitiveDataSanitizer.sanitizeResult(result));

        assertThat(sanitized.get("code").asInt()).isEqualTo(0);
        assertThat(sanitized.at("/data/accessToken").isMissingNode()).isTrue();
        assertThat(sanitized.at("/data/name").asText()).isEqualTo("kept");
    }
}

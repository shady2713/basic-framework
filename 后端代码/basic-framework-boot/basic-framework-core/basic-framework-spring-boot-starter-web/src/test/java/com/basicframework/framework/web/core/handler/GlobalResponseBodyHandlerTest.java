package com.basicframework.framework.web.core.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.pojo.CommonResult;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;

/**
 * {@link GlobalResponseBodyHandler#supports} return-type matching, including the
 * ResponseEntity&lt;CommonResult&gt; shape produced by {@link GlobalExceptionHandler} (ADR 0003).
 */
class GlobalResponseBodyHandlerTest {

    private final GlobalResponseBodyHandler advice = new GlobalResponseBodyHandler();

    @Test
    void supports_commonResultAndResponseEntityOfCommonResult() throws Exception {
        assertThat(supports("commonResult")).isTrue();
        assertThat(supports("responseEntityOfCommonResult")).isTrue();
    }

    @Test
    void supports_rejectsUnrelatedReturnTypes() throws Exception {
        assertThat(supports("responseEntityOfString")).isFalse();
        assertThat(supports("plainString")).isFalse();
    }

    private boolean supports(String methodName) throws Exception {
        Method method = FakeController.class.getMethod(methodName);
        return advice.supports(new MethodParameter(method, -1), null);
    }

    @SuppressWarnings("unused")
    static class FakeController {

        public CommonResult<?> commonResult() {
            return null;
        }

        public ResponseEntity<CommonResult<?>> responseEntityOfCommonResult() {
            return null;
        }

        public ResponseEntity<String> responseEntityOfString() {
            return null;
        }

        public String plainString() {
            return null;
        }
    }
}

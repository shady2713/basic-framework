package com.basicframework.module.system.framework.captcha.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anji.captcha.model.common.RepCodeEnum;
import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaCacheService;
import com.anji.captcha.service.impl.AbstractCaptchaService;
import com.anji.captcha.service.impl.CaptchaServiceFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class PictureWordCaptchaServiceImplTest {

    @Test
    void verification_masksInternalCacheFailure() {
        Object previousCacheType = ReflectionTestUtils.getField(AbstractCaptchaService.class, "cacheType");
        ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", "test");
        CaptchaCacheService cache = mock(CaptchaCacheService.class);
        when(cache.exists(anyString())).thenThrow(new IllegalStateException("redis://user:secret@internal"));
        CaptchaVO request = new CaptchaVO();
        request.setCaptchaVerification("verification-token");
        Logger logger = (Logger) LoggerFactory.getLogger(PictureWordCaptchaServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        boolean previousAdditive = logger.isAdditive();
        logger.setAdditive(false);
        logger.addAppender(appender);

        ResponseModel response;
        try (MockedStatic<CaptchaServiceFactory> factory = mockStatic(CaptchaServiceFactory.class)) {
            factory.when(() -> CaptchaServiceFactory.getCache("test")).thenReturn(cache);
            response = new PictureWordCaptchaServiceImpl().verification(request);
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(previousAdditive);
            appender.stop();
            ReflectionTestUtils.setField(AbstractCaptchaService.class, "cacheType", previousCacheType);
        }

        assertThat(response.getRepCode()).isEqualTo(RepCodeEnum.API_CAPTCHA_ERROR.getCode());
        assertThat(response.getRepMsg()).doesNotContain("redis://", "secret", "internal");
        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("redis://", "secret", "internal"));
    }
}

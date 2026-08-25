package com.basicframework.module.infra.controller.admin.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.controller.admin.file.vo.config.FileConfigRespVO;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.basicframework.module.infra.service.file.FileConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FileConfigControllerTest {

    @Test
    void getFileConfig_redactsAccessSecretAndReturnsConfigurationState() {
        FileConfigService service = mock(FileConfigService.class);
        FileConfigController controller = new FileConfigController();
        ReflectionTestUtils.setField(controller, "fileConfigService", service);
        S3FileClientConfig clientConfig = new S3FileClientConfig();
        clientConfig.setAccessSecret("runtime-secret");
        when(service.getFileConfig(1L)).thenReturn(new FileConfigDO().setId(1L).setConfig(clientConfig));

        FileConfigRespVO response = controller.getFileConfig(1L).getData();

        assertThat(response.getAccessSecretConfigured()).isTrue();
        assertThat(((S3FileClientConfig) response.getConfig()).getAccessSecret())
                .isNull();
        assertThat(clientConfig.getAccessSecret()).isEqualTo("runtime-secret");
    }
}

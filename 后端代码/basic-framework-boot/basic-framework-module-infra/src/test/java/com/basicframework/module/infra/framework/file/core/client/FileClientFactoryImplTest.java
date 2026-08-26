package com.basicframework.module.infra.framework.file.core.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.basicframework.module.infra.dal.dataobject.file.FileContentDO;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.framework.file.core.client.db.DBFileClientConfig;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FileClientFactoryImplTest {

    @Test
    void databaseClient_usesFactoryMapper() throws Exception {
        FileContentMapper fileContentMapper = mock(FileContentMapper.class);
        FileClientFactory factory = new FileClientFactoryImpl(fileContentMapper);
        long configId = 42L;
        factory.createOrUpdateFileClient(
                configId,
                FileStorageEnum.DB.getStorage(),
                new DBFileClientConfig().setDomain("http://localhost/files"));

        FileClient client = factory.getFileClient(configId);
        String url = client.upload(new byte[] {1, 2, 3}, "integration/test.txt", "text/plain");

        assertThat(url).isEqualTo("http://localhost/files/admin-api/infra/file/42/get/integration/test.txt");
        ArgumentCaptor<FileContentDO> captor = ArgumentCaptor.forClass(FileContentDO.class);
        verify(fileContentMapper).insert(captor.capture());
        assertThat(captor.getValue().getConfigId()).isEqualTo(configId);
        assertThat(captor.getValue().getPath()).isEqualTo("integration/test.txt");
        assertThat(captor.getValue().getContent()).containsExactly(1, 2, 3);
    }
}

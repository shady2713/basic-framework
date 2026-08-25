package com.basicframework.module.infra.service.file;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CLIENT_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PATH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.framework.file.config.FileArchiveSecurityProperties;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** {@link FileServiceImpl} 上传路径边界测试。 */
class FileServiceImplTest {

    private final FileServiceImpl fileService = new FileServiceImpl();
    private final FileConfigService fileConfigService = mock(FileConfigService.class);
    private final FileMapper fileMapper = mock(FileMapper.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                fileService, "fileArchiveValidator", new FileArchiveValidator(new FileArchiveSecurityProperties()));
        ReflectionTestUtils.setField(fileService, "fileConfigService", fileConfigService);
        ReflectionTestUtils.setField(fileService, "fileMapper", fileMapper);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "../escape.png",
                "..\\escape.png",
                "nested/file.png",
                "nested\\file.png",
                "C:\\escape.png",
                "line\nbreak.png"
            })
    void generateUploadPath_withInvalidFileName_rejectsBeforeStorage(String fileName) {
        assertPathInvalid(() -> fileService.generateUploadPath(fileName, "images"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "/absolute", "\\absolute", "nested\\windows", "C:\\absolute"})
    void generateUploadPath_withInvalidDirectory_rejectsBeforeStorage(String directory) {
        assertPathInvalid(() -> fileService.generateUploadPath("safe.png", directory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"images", "customers/contracts"})
    void generateUploadPath_withRelativeDirectory_keepsPathUnderDirectory(String directory) {
        String path = fileService.generateUploadPath("safe.png", directory);

        assertThat(path).startsWith(directory + "/").endsWith(".png");
    }

    @Test
    void createFile_withoutMasterClient_returnsRegisteredBusinessError() {
        when(fileConfigService.getMasterFileClientForReferenceWrite()).thenReturn(null);
        byte[] png = Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

        assertThatThrownBy(() -> fileService.createFile(png, "safe.png", "images", "image/png"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(FILE_CLIENT_NOT_EXISTS.getCode());
    }

    @Test
    void createFileMetadata_withLockedConfig_removesUrlQueryBeforeInsert() {
        FileClient fileClient = mock(FileClient.class);
        when(fileConfigService.getFileClientForReferenceWrite(21L)).thenReturn(fileClient);
        FileDO file = new FileDO()
                .setConfigId(21L)
                .setName("safe.png")
                .setPath("images/safe.png")
                .setUrl("https://example.test/safe.png?signature=secret")
                .setSize(1L);

        fileService.createFile(file);

        assertThat(file.getUrl()).isEqualTo("https://example.test/safe.png");
        verify(fileMapper).insert(file);
    }

    private static void assertPathInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(FILE_PATH_INVALID.getCode());
    }
}

package com.basicframework.module.infra.service.file;

import static cn.hutool.core.date.DatePattern.PURE_DATE_PATTERN;
import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_CLIENT_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_IS_EMPTY;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_NOT_EXISTS;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_PATH_INVALID;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_TYPE_NOT_ALLOWED;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.http.HttpUtils;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.framework.file.core.client.FileClient;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import com.basicframework.module.infra.framework.file.core.utils.FileTypeUtils;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件 Service 实现类
 */
@Service
public class FileServiceImpl implements FileService {

    /**
     * 上传文件的前缀，是否包含日期（yyyyMMdd）。
     *
     * 目的：按照日期分目录，便于后续定位和归档。
     */
    static boolean PATH_PREFIX_DATE_ENABLE = true;
    /**
     * 上传文件的后缀，是否包含时间戳。
     *
     * 目的：保证文件名唯一，避免同名文件覆盖。
     */
    static boolean PATH_SUFFIX_TIMESTAMP_ENABLE = true;

    @Resource
    private FileConfigService fileConfigService;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private FileArchiveValidator fileArchiveValidator;

    @Override
    public PageResult<FileDO> getFilePage(PageParam pageParam, String path, String type, LocalDateTime[] createTime) {
        return fileMapper.selectPage(pageParam, path, type, createTime);
    }

    @Override
    @SneakyThrows
    @Transactional(rollbackFor = Exception.class)
    public String createFile(byte[] content, String name, String directory, String type) {
        // 先拦截空内容，避免后续 MIME 识别、摘要计算和长度访问时出现空指针。
        if (content == null || content.length == 0) {
            throw exception(FILE_IS_EMPTY);
        }
        // 先探测真实 MIME，后续既要用于补齐后缀，也要用于类型白名单校验。
        String detectedType = FileTypeUtils.getMineType(content, name);
        // 调用方未显式传入类型时，回退为探测结果。
        if (StrUtil.isEmpty(type)) {
            type = detectedType;
        }
        // 未传入文件名时，使用内容摘要生成稳定名称。
        if (StrUtil.isEmpty(name)) {
            name = DigestUtil.sha256Hex(content);
        }
        if (StrUtil.isEmpty(FileUtil.extName(name))) {
            // 文件名缺少后缀时，优先根据探测结果补齐，避免白名单校验误判。
            String extension = FileTypeUtils.getExtension(StrUtil.emptyToDefault(detectedType, type));
            if (StrUtil.isNotEmpty(extension)) {
                name = name + extension;
            }
        }
        // 只有后缀和探测类型都在允许范围内时才允许上传，防止伪装可执行文件。
        if (!FileTypeUtils.isAllowedUploadType(content, name)) {
            throw exception(FILE_TYPE_NOT_ALLOWED);
        }
        // ZIP 在进入持久化存储前校验内部路径和展开规模，避免转交下游时携带压缩包攻击载荷。
        fileArchiveValidator.validate(content, name);
        // 重新结合最终文件名计算 MIME，确保上传到存储客户端的类型与文件名一致。
        type = FileTypeUtils.getMineType(content, name);

        // 生成唯一上传路径，避免不同目录/同名文件互相覆盖。
        String path = generateUploadPath(name, directory);
        FileClient client = requireMasterFileClientForReferenceWrite();
        String url = client.upload(content, path, type);

        // 上传成功后持久化文件元数据，便于后续查询、预签名和删除。
        fileMapper.insert(new FileDO()
                .setConfigId(client.getId())
                .setName(name)
                .setPath(path)
                .setUrl(url)
                .setType(type)
                .setSize((long) content.length));
        return url;
    }

    @VisibleForTesting
    String generateUploadPath(String name, String directory) {
        validateUploadPath(name, directory);
        // 先准备日期前缀和时间戳后缀，两者都可按需关闭。
        String prefix = null;
        if (PATH_PREFIX_DATE_ENABLE) {
            prefix = LocalDateTimeUtil.format(LocalDateTimeUtil.now(), PURE_DATE_PATTERN);
        }
        String suffix = null;
        if (PATH_SUFFIX_TIMESTAMP_ENABLE) {
            suffix = String.valueOf(System.currentTimeMillis());
        }

        // 先拼接时间戳，保留原始扩展名结构。
        if (StrUtil.isNotEmpty(suffix)) {
            String ext = FileUtil.extName(name);
            if (StrUtil.isNotEmpty(ext)) {
                name = FileUtil.mainName(name) + StrUtil.C_UNDERLINE + suffix + StrUtil.DOT + ext;
            } else {
                name = name + StrUtil.C_UNDERLINE + suffix;
            }
        }
        // 再拼接日期前缀，按天分目录。
        if (StrUtil.isNotEmpty(prefix)) {
            name = prefix + StrUtil.SLASH + name;
        }
        // 最后拼接业务目录，保持调用方传入的目录层级。
        if (StrUtil.isNotEmpty(directory)) {
            name = directory + StrUtil.SLASH + name;
        }
        return name;
    }

    private static void validateUploadPath(String name, String directory) {
        if (StrUtil.isEmpty(name)
                || StrUtil.contains(name, "..")
                || StrUtil.containsAny(name, "/", "\\", ":")
                || containsControlCharacter(name)) {
            throw exception(FILE_PATH_INVALID);
        }
        if (StrUtil.isNotEmpty(directory)
                && (StrUtil.contains(directory, "..")
                        || StrUtil.startWithAny(directory, "/", "\\")
                        || StrUtil.containsAny(directory, "\\", ":")
                        || containsControlCharacter(directory))) {
            throw exception(FILE_PATH_INVALID);
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    @Override
    @SneakyThrows
    public FilePresignedUrlDTO presignPutUrl(String name, String directory) {
        // 预签名上传同样复用统一的路径生成规则，避免和直接上传路径不一致。
        String path = generateUploadPath(name, directory);

        // 同时返回上传地址和访问地址，前端上传后可以直接使用访问地址预览。
        FileClient fileClient = requireMasterFileClient();
        String uploadUrl = fileClient.presignPutUrl(path);
        String visitUrl = fileClient.presignGetUrl(path, null);
        return new FilePresignedUrlDTO(fileClient.getId(), uploadUrl, visitUrl, path);
    }

    @Override
    public String presignGetUrl(String url, Integer expirationSeconds) {
        FileClient fileClient = requireMasterFileClient();
        return fileClient.presignGetUrl(url, expirationSeconds);
    }

    private FileClient requireMasterFileClient() {
        FileClient fileClient = fileConfigService.getMasterFileClient();
        if (fileClient == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, "master");
        }
        return fileClient;
    }

    private FileClient requireMasterFileClientForReferenceWrite() {
        FileClient fileClient = fileConfigService.getMasterFileClientForReferenceWrite();
        if (fileClient == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, "master");
        }
        return fileClient;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFile(FileDO file) {
        FileClient fileClient = fileConfigService.getFileClientForReferenceWrite(file.getConfigId());
        if (fileClient == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, file.getConfigId());
        }
        // 移除私有桶 URL 上的签名参数，避免把短期凭证误存入数据库。
        file.setUrl(HttpUtils.removeUrlQuery(file.getUrl()));
        fileMapper.insert(file);
        return file.getId();
    }

    @Override
    public FileDO getFile(Long id) {
        return validateFileExists(id);
    }

    @Override
    public void deleteFile(Long id) throws Exception {
        // 先校验记录存在，避免存储删除和数据库删除状态不一致。
        FileDO file = validateFileExists(id);

        // 先删存储中的文件，再删数据库记录，避免外部资源残留。
        FileClient client = fileConfigService.getFileClient(file.getConfigId());
        if (client == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, file.getConfigId());
        }
        client.delete(file.getPath());

        fileMapper.deleteById(id);
    }

    @Override
    @SneakyThrows
    public void deleteFileList(List<Long> ids) {
        // 批量删除时先遍历存储，再统一删除数据库记录，保持数据一致性。
        List<FileDO> files = fileMapper.selectByIds(ids);
        for (FileDO file : files) {
            // 根据每条记录所属的配置获取客户端，兼容多存储源。
            FileClient client = fileConfigService.getFileClient(file.getConfigId());
            if (client == null) {
                throw exception(FILE_CLIENT_NOT_EXISTS, file.getConfigId());
            }
            client.delete(file.getPath());
        }

        fileMapper.deleteByIds(ids);
    }

    private FileDO validateFileExists(Long id) {
        FileDO fileDO = fileMapper.selectById(id);
        if (fileDO == null) {
            throw exception(FILE_NOT_EXISTS);
        }
        return fileDO;
    }

    @Override
    public byte[] getFileContent(Long configId, String path) throws Exception {
        FileClient client = fileConfigService.getFileClient(configId);
        if (client == null) {
            throw exception(FILE_CLIENT_NOT_EXISTS, configId);
        }
        return client.getContent(path);
    }
}

package com.basicframework.module.infra.framework.file.core.client.s3;

import static com.basicframework.framework.common.util.exception.SafeExceptionLogUtils.format;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.basicframework.framework.common.util.http.HttpUtils;
import com.basicframework.module.infra.framework.file.core.client.AbstractFileClient;
import com.basicframework.module.infra.framework.file.core.client.FileObjectMetadata;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * 基于 S3 协议的文件客户端，实现 MinIO、阿里云、腾讯云、七牛云、华为云等云服务
 *
 */
@Slf4j
public class S3FileClient extends AbstractFileClient<S3FileClientConfig> {

    private static final Duration EXPIRATION_DEFAULT = Duration.ofHours(24);

    private final ReentrantReadWriteLock runtimeLock = new ReentrantReadWriteLock();

    private volatile RuntimeState runtime;

    public S3FileClient(Long id, S3FileClientConfig config) {
        super(id, config);
    }

    @Override
    protected void doInit() {
        S3FileClientConfig currentConfig = config;
        String domain =
                StrUtil.isEmpty(currentConfig.getDomain()) ? buildDomain(currentConfig) : currentConfig.getDomain();
        // 初始化 S3 客户端
        // 优先级：配置的 region > 从 endpoint 解析的 region > 默认值 us-east-1
        String regionStr = resolveRegion(currentConfig);
        Region region = Region.of(regionStr);
        AwsCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(currentConfig.getAccessKey(), currentConfig.getAccessSecret()));
        URI endpoint = URI.create(buildEndpoint(currentConfig));
        S3Configuration serviceConfiguration = S3Configuration.builder() // Path-style 访问
                .pathStyleAccessEnabled(Boolean.TRUE.equals(currentConfig.getEnablePathStyleAccess()))
                .chunkedEncodingEnabled(false) // 禁用分块编码，避免部分兼容实现上传失败
                .build();
        S3Client replacementClient = null;
        S3Presigner replacementPresigner = null;
        try {
            replacementClient = S3Client.builder()
                    .credentialsProvider(credentialsProvider)
                    .region(region)
                    .endpointOverride(endpoint)
                    .serviceConfiguration(serviceConfiguration)
                    .build();
            replacementPresigner = S3Presigner.builder()
                    .credentialsProvider(credentialsProvider)
                    .region(region)
                    .endpointOverride(endpoint)
                    .serviceConfiguration(serviceConfiguration)
                    .build();
        } catch (RuntimeException | Error exception) {
            closeResources(replacementClient, replacementPresigner, "initialization-failed");
            throw exception;
        }

        RuntimeState replacement = new RuntimeState(replacementClient, replacementPresigner, currentConfig, domain);
        runtimeLock.writeLock().lock();
        try {
            RuntimeState previous = runtime;
            runtime = replacement;
            closeRuntime(previous, "refresh");
        } finally {
            runtimeLock.writeLock().unlock();
        }
    }

    @Override
    public String upload(byte[] content, String path, String type) {
        runtimeLock.readLock().lock();
        try {
            RuntimeState current = requireRuntime();
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(current.config().getBucket())
                    .key(path)
                    .contentType(type)
                    .contentLength((long) content.length)
                    .build();
            current.client().putObject(putRequest, RequestBody.fromBytes(content));
            return presignGetUrl(current, path, null);
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    @Override
    public void delete(String path) {
        runtimeLock.readLock().lock();
        try {
            RuntimeState current = requireRuntime();
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(current.config().getBucket())
                    .key(path)
                    .build();
            current.client().deleteObject(deleteRequest);
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    @Override
    public byte[] getContent(String path) throws Exception {
        runtimeLock.readLock().lock();
        try {
            RuntimeState current = requireRuntime();
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(current.config().getBucket())
                    .key(path)
                    .build();
            try (ResponseInputStream<GetObjectResponse> input = current.client().getObject(getRequest)) {
                return IoUtil.readBytes(input);
            }
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    @Override
    public String presignPutUrl(String path, long size, String type, Duration expiration) {
        runtimeLock.readLock().lock();
        try {
            RuntimeState current = requireRuntime();
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(current.config().getBucket())
                    .key(path)
                    .contentLength(size);
            if (StrUtil.isNotBlank(type)) {
                request.contentType(type);
            }
            return current.presigner()
                    .presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(expiration)
                            .putObjectRequest(request.build())
                            .build())
                    .url()
                    .toString();
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    @Override
    public FileObjectMetadata getMetadata(String path) {
        runtimeLock.readLock().lock();
        try {
            RuntimeState current = requireRuntime();
            HeadObjectResponse response = current.client()
                    .headObject(HeadObjectRequest.builder()
                            .bucket(current.config().getBucket())
                            .key(path)
                            .build());
            return new FileObjectMetadata(response.contentLength(), response.contentType());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return null;
            }
            throw exception;
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    @Override
    public boolean supportsPrivatePresignedUpload() {
        runtimeLock.readLock().lock();
        try {
            return BooleanUtil.isFalse(requireRuntime().config().getEnablePublicAccess());
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    @Override
    public boolean supportsPrivateRead() {
        runtimeLock.readLock().lock();
        try {
            return BooleanUtil.isFalse(requireRuntime().config().getEnablePublicAccess());
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    @Override
    public void promotePrivateUpload(String sourcePath, String targetPath) {
        runtimeLock.readLock().lock();
        try {
            RuntimeState current = requireRuntime();
            String copySource = URLEncoder.encode(
                            current.config().getBucket() + "/" + sourcePath, StandardCharsets.UTF_8)
                    .replace("%2F", "/")
                    .replace("+", "%20");
            current.client()
                    .copyObject(CopyObjectRequest.builder()
                            .bucket(current.config().getBucket())
                            .copySource(copySource)
                            .key(targetPath)
                            .build());
            current.client()
                    .deleteObject(DeleteObjectRequest.builder()
                            .bucket(current.config().getBucket())
                            .key(sourcePath)
                            .build());
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    @Override
    public String presignGetUrl(String url, Integer expirationSeconds) {
        runtimeLock.readLock().lock();
        try {
            return presignGetUrl(requireRuntime(), url, expirationSeconds);
        } finally {
            runtimeLock.readLock().unlock();
        }
    }

    private static String presignGetUrl(RuntimeState current, String url, Integer expirationSeconds) {
        // 1. 将 url 转换为 path
        String path = HttpUtils.removeUrlQuery(url);
        String domain = StrUtil.removeSuffix(current.domain(), "/");
        String domainPrefix = domain + "/";
        if (path.startsWith(domainPrefix)) {
            path = path.substring(domainPrefix.length());
        }
        path = HttpUtils.decodeUtf8(path);

        // 2.1 情况一：公开访问：无需签名
        // 考虑到老版本的兼容，所以必须是 config.getEnablePublicAccess() 为 false 时，才进行签名
        if (!BooleanUtil.isFalse(current.config().getEnablePublicAccess())) {
            return current.domain() + "/" + path;
        }

        // 2.2 情况二：私有访问：生成 GET 预签名 URL
        String finalPath = path;
        Duration expiration = expirationSeconds != null ? Duration.ofSeconds(expirationSeconds) : EXPIRATION_DEFAULT;
        URL signedUrl = current.presigner()
                .presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(expiration)
                        .getObjectRequest(
                                b -> b.bucket(current.config().getBucket()).key(finalPath))
                        .build())
                .url();
        return signedUrl.toString();
    }

    /**
     * 基于 bucket + endpoint 构建访问的 Domain 地址
     *
     * @return Domain 地址
     */
    private static String buildDomain(S3FileClientConfig config) {
        // 如果已经是 http 或者 https，则不进行拼接.主要适配 MinIO
        if (HttpUtil.isHttp(config.getEndpoint()) || HttpUtil.isHttps(config.getEndpoint())) {
            return StrUtil.format("{}/{}", config.getEndpoint(), config.getBucket());
        }
        // 阿里云、腾讯云、华为云都适合。七牛云比较特殊，必须有自定义域名
        return StrUtil.format("https://{}.{}", config.getBucket(), config.getEndpoint());
    }

    /**
     * 节点地址补全协议头
     *
     * @return 节点地址
     */
    private static String buildEndpoint(S3FileClientConfig config) {
        // 如果已经是 http 或者 https，则不进行拼接
        if (HttpUtil.isHttp(config.getEndpoint()) || HttpUtil.isHttps(config.getEndpoint())) {
            return config.getEndpoint();
        }
        return StrUtil.format("https://{}", config.getEndpoint());
    }

    /**
     * 解析 AWS 区域
     * 优先级：配置的 region > 从 endpoint 解析的 region > 默认值 us-east-1
     *
     * @return 区域字符串
     */
    private static String resolveRegion(S3FileClientConfig config) {
        // 1. 如果配置了 region，直接使用
        if (StrUtil.isNotEmpty(config.getRegion())) {
            return config.getRegion();
        }

        // 2.1 尝试从 endpoint 中解析 region
        String endpoint = config.getEndpoint();
        if (StrUtil.isEmpty(endpoint)) {
            return "us-east-1";
        }

        // 2.2 移除协议头（http:// 或 https://）
        String host = endpoint;
        if (HttpUtil.isHttp(endpoint) || HttpUtil.isHttps(endpoint)) {
            try {
                host = URI.create(endpoint).getHost();
            } catch (Exception e) {
                // 解析失败，使用默认值
                return "us-east-1";
            }
        }
        if (StrUtil.isEmpty(host)) {
            return "us-east-1";
        }

        // 3.1 AWS S3 格式：s3.us-west-2.amazonaws.com 或 s3.amazonaws.com
        if (host.contains("amazonaws.com")) {
            // 匹配 s3.{region}.amazonaws.com 格式
            if (host.startsWith("s3.") && host.contains(".amazonaws.com")) {
                String regionPart = host.substring(3, host.indexOf(".amazonaws.com"));
                if (StrUtil.isNotEmpty(regionPart) && !regionPart.equals("accelerate")) {
                    return regionPart;
                }
            }
            // s3.amazonaws.com 或 s3-accelerate.amazonaws.com 使用默认值
            return "us-east-1";
        }
        // 3.2 阿里云 OSS 格式：oss-cn-beijing.aliyuncs.com
        if (host.contains(S3FileClientConfig.ENDPOINT_ALIYUN)) {
            // 匹配 oss-{region}.aliyuncs.com 格式
            if (host.startsWith("oss-") && host.contains("." + S3FileClientConfig.ENDPOINT_ALIYUN)) {
                String regionPart = host.substring(4, host.indexOf("." + S3FileClientConfig.ENDPOINT_ALIYUN));
                if (StrUtil.isNotEmpty(regionPart)) {
                    return regionPart;
                }
            }
        }
        // 3.3 腾讯云 COS 格式：cos.ap-shanghai.myqcloud.com
        if (host.contains(S3FileClientConfig.ENDPOINT_TENCENT)) {
            // 匹配 cos.{region}.myqcloud.com 格式
            if (host.startsWith("cos.") && host.contains("." + S3FileClientConfig.ENDPOINT_TENCENT)) {
                String regionPart = host.substring(4, host.indexOf("." + S3FileClientConfig.ENDPOINT_TENCENT));
                if (StrUtil.isNotEmpty(regionPart)) {
                    return regionPart;
                }
            }
        }

        // 3.4 其他情况（MinIO、七牛云等）使用默认值
        return "us-east-1";
    }

    @Override
    public void close() {
        runtimeLock.writeLock().lock();
        try {
            RuntimeState previous = runtime;
            runtime = null;
            closeRuntime(previous, "close");
        } finally {
            runtimeLock.writeLock().unlock();
        }
    }

    private RuntimeState requireRuntime() {
        RuntimeState current = runtime;
        if (current == null) {
            throw new IllegalStateException("S3 文件客户端尚未初始化或已关闭");
        }
        return current;
    }

    private void closeRuntime(RuntimeState state, String reason) {
        if (state != null) {
            closeResources(state.client(), state.presigner(), reason);
        }
    }

    private void closeResources(S3Client s3Client, S3Presigner s3Presigner, String reason) {
        try {
            try {
                if (s3Client != null) {
                    s3Client.close();
                }
            } catch (RuntimeException exception) {
                log.error(
                        "[closeResources][配置编号({}) 原因({}) 关闭 S3Client 失败，stackTrace({})]",
                        getId(),
                        reason,
                        format(exception));
            }
        } finally {
            try {
                if (s3Presigner != null) {
                    s3Presigner.close();
                }
            } catch (RuntimeException exception) {
                log.error(
                        "[closeResources][配置编号({}) 原因({}) 关闭 S3Presigner 失败，stackTrace({})]",
                        getId(),
                        reason,
                        format(exception));
            }
        }
    }

    static record RuntimeState(S3Client client, S3Presigner presigner, S3FileClientConfig config, String domain) {}
}

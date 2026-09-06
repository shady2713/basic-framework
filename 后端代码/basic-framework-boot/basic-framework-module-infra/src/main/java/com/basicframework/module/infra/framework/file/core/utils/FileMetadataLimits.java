package com.basicframework.module.infra.framework.file.core.utils;

/** 与 infra_file 列宽和上传路径生成格式保持一致的文件元数据边界。 */
public final class FileMetadataLimits {

    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_DIRECTORY_LENGTH = 200;
    public static final int MAX_PATH_LENGTH = 512;
    public static final int MAX_URL_LENGTH = 1024;
    public static final int MAX_TYPE_LENGTH = 128;
    public static final long MAX_SIZE = Integer.MAX_VALUE;

    private FileMetadataLimits() {}
}

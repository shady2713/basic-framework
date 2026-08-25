package com.basicframework.module.infra.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * Infra 错误码枚举类
 *
 * infra 系统，使用 1-001-000-000 段
 */
public interface ErrorCodeConstants {

    // ========== 参数配置 1-001-000-000 ==========
    ErrorCode CONFIG_NOT_EXISTS = new ErrorCode(1_001_000_001, "参数配置不存在");
    ErrorCode CONFIG_KEY_DUPLICATE = new ErrorCode(1_001_000_002, "参数配置 key 重复");
    ErrorCode CONFIG_CAN_NOT_DELETE_SYSTEM_TYPE = new ErrorCode(1_001_000_003, "不能删除类型为系统内置的参数配置");
    ErrorCode CONFIG_GET_VALUE_ERROR_IF_VISIBLE = new ErrorCode(1_001_000_004, "获取参数配置失败，原因：不允许获取不可见配置");

    // ========== 定时任务 1-001-001-000 ==========
    ErrorCode JOB_NOT_EXISTS = new ErrorCode(1_001_001_000, "定时任务不存在");
    ErrorCode JOB_HANDLER_EXISTS = new ErrorCode(1_001_001_001, "定时任务的处理器已经存在");
    ErrorCode JOB_CHANGE_STATUS_INVALID = new ErrorCode(1_001_001_002, "只允许修改为开启或者关闭状态");
    ErrorCode JOB_CHANGE_STATUS_EQUALS = new ErrorCode(1_001_001_003, "定时任务已经处于该状态，无需修改");
    ErrorCode JOB_UPDATE_ONLY_NORMAL_STATUS = new ErrorCode(1_001_001_004, "只有开启状态的任务，才可以修改");
    ErrorCode JOB_CRON_EXPRESSION_VALID = new ErrorCode(1_001_001_005, "CRON 表达式不正确");
    ErrorCode JOB_HANDLER_BEAN_NOT_EXISTS = new ErrorCode(1_001_001_006, "定时任务的处理器 Bean 不存在，注意 Bean 默认首字母小写");
    ErrorCode JOB_HANDLER_BEAN_TYPE_ERROR = new ErrorCode(1_001_001_007, "定时任务的处理器 Bean 类型不正确，未实现 JobHandler 接口");

    // ========== API 错误日志 1-001-002-000 ==========

    // ========= 文件相关 1-001-003-000 =================
    ErrorCode FILE_PATH_EXISTS = new ErrorCode(1_001_003_000, "文件路径已存在");
    ErrorCode FILE_NOT_EXISTS = new ErrorCode(1_001_003_001, "文件不存在");
    ErrorCode FILE_IS_EMPTY = new ErrorCode(1_001_003_002, "文件为空");
    ErrorCode FILE_TYPE_NOT_ALLOWED = new ErrorCode(1_001_003_003, "不支持的文件类型");
    ErrorCode FILE_PATH_INVALID = new ErrorCode(1_001_003_004, "文件路径不合法");
    ErrorCode FILE_CLIENT_NOT_EXISTS = new ErrorCode(1_001_003_005, "文件客户端({}) 不存在");
    ErrorCode FILE_ARCHIVE_UNSAFE = new ErrorCode(1_001_003_006, "压缩包结构或展开规模不安全");

    // ========== 代码生成器 1-001-004-000 ==========
    ErrorCode CODEGEN_TABLE_EXISTS = new ErrorCode(1_001_004_003, "表定义已经存在");
    ErrorCode CODEGEN_IMPORT_TABLE_NULL = new ErrorCode(1_001_004_001, "导入的表不存在");
    ErrorCode CODEGEN_IMPORT_COLUMNS_NULL = new ErrorCode(1_001_004_002, "导入的字段不存在");
    ErrorCode CODEGEN_TABLE_NOT_EXISTS = new ErrorCode(1_001_004_004, "表定义不存在");
    ErrorCode CODEGEN_COLUMN_NOT_EXISTS = new ErrorCode(1_001_004_005, "字段定义不存在");
    ErrorCode CODEGEN_SYNC_COLUMNS_NULL = new ErrorCode(1_001_004_006, "同步的字段不存在");
    ErrorCode CODEGEN_SYNC_NONE_CHANGE = new ErrorCode(1_001_004_007, "同步失败，不存在改变");
    ErrorCode CODEGEN_TABLE_INFO_TABLE_COMMENT_IS_NULL = new ErrorCode(1_001_004_008, "数据库的表注释未填写");
    ErrorCode CODEGEN_TABLE_INFO_COLUMN_COMMENT_IS_NULL = new ErrorCode(1_001_004_009, "数据库的表字段({})注释未填写");
    ErrorCode CODEGEN_MASTER_TABLE_NOT_EXISTS = new ErrorCode(1_001_004_010, "主表(id={})定义不存在，请检查");
    ErrorCode CODEGEN_SUB_COLUMN_NOT_EXISTS = new ErrorCode(1_001_004_011, "子表的字段(id={})不存在，请检查");
    ErrorCode CODEGEN_MASTER_GENERATION_FAIL_NO_SUB_TABLE = new ErrorCode(1_001_004_012, "主表生成代码失败，原因：它没有子表");
    ErrorCode CODEGEN_COLUMN_NOT_BELONG_TABLE = new ErrorCode(1_001_004_013, "字段(id={})不属于表(id={})");
    ErrorCode CODEGEN_TEMPLATE_TYPE_INVALID = new ErrorCode(1_001_004_014, "代码生成模板类型({})无效");
    ErrorCode CODEGEN_MASTER_TABLE_INVALID = new ErrorCode(1_001_004_015, "主表(id={})必须是有效的主表模板");
    ErrorCode CODEGEN_TABLE_HAS_SUB_TABLES = new ErrorCode(1_001_004_016, "代码生成表(id={})仍被子表引用");
    ErrorCode CODEGEN_COLUMN_IN_USE = new ErrorCode(1_001_004_017, "代码生成字段(id={})仍被生成配置引用");
    ErrorCode CODEGEN_TREE_COLUMNS_DUPLICATE = new ErrorCode(1_001_004_018, "树父字段和树名称字段不能相同");
    ErrorCode CODEGEN_SUB_CONFIGURATION_INVALID = new ErrorCode(1_001_004_019, "子表关联配置不完整");
    ErrorCode CODEGEN_SCENE_INVALID = new ErrorCode(1_001_004_020, "代码生成场景({})无效");
    ErrorCode CODEGEN_PARENT_MENU_INVALID = new ErrorCode(1_001_004_021, "上级菜单(id={})必须是有效的目录或菜单");

    // ========== 文件配置 1-001-006-000 ==========
    ErrorCode FILE_CONFIG_NOT_EXISTS = new ErrorCode(1_001_006_000, "文件配置不存在");
    ErrorCode FILE_CONFIG_DELETE_FAIL_MASTER = new ErrorCode(1_001_006_001, "该文件配置不允许删除，原因：它是主配置，删除会导致无法上传文件");
    ErrorCode FILE_CONFIG_IN_USE = new ErrorCode(1_001_006_002, "文件配置({})仍被文件或文件内容引用，无法删除");
}

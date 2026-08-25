package com.basicframework.module.infra.controller.admin.codegen.vo.table;

import static com.basicframework.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

@Schema(description = "管理后台 - 表定义分页 Request VO")
@Data
public class CodegenTablePageReqVO extends PageParam {

    @Schema(description = "表名称，模糊匹配", example = "sys_user")
    private String tableName;

    @Schema(description = "表描述，模糊匹配", example = "用户表")
    private String tableComment;

    @Schema(description = "实体，模糊匹配", example = "BasicFramework")
    private String className;

    @Schema(description = "创建时间", example = "[2022-07-01 00:00:00,2022-07-01 23:59:59]")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;
}

package com.basicframework.module.infra.controller.admin.codegen.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/** 代码生成表的批量创建请求 VO */
@Schema(description = "管理后台 - 基于数据库的表结构，创建代码生成器的表和字段定义 Request VO")
@Data
public class CodegenCreateListReqVO {

    @Schema(description = "表名数组", requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"system_user\"]")
    @NotEmpty(message = "表名数组不能为空")
    @Size(max = 100, message = "单次最多导入 100 张表")
    private List<@NotBlank(message = "表名不能为空") @Size(max = 64, message = "表名长度不能超过 64 个字符") String> tableNames;
}

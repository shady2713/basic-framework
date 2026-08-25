package com.basicframework.module.crm.controller.admin.crm.vo.customer;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 客户分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class CustomerPageReqVO extends PageParam {

    @Schema(description = "客户名称，模糊匹配", example = "张三")
    private String name;

    @Schema(description = "手机号，精确匹配", example = "13800138000")
    private String mobile;
}

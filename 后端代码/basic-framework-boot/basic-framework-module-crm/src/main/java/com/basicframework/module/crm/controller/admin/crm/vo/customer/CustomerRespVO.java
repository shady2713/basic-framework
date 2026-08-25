package com.basicframework.module.crm.controller.admin.crm.vo.customer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Schema(description = "管理后台 - 客户信息 Response VO")
@Data
public class CustomerRespVO {

    @Schema(description = "客户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "客户名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三")
    private String name;

    @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED, example = "13800138000")
    private String mobile;

    @Schema(description = "合同金额，单位：元", requiredMode = Schema.RequiredMode.REQUIRED, example = "100000.00")
    private BigDecimal amount;

    @Schema(description = "合同日期", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-08-01")
    private LocalDate contractDate;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;
}

package com.basicframework.module.crm.controller.admin.crm.vo.customer;

import com.basicframework.framework.common.validation.Mobile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;

@Schema(description = "管理后台 - 客户更新 Request VO")
@Data
public class CustomerUpdateReqVO {

    @Schema(description = "客户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "客户编号不能为空")
    private Long id;

    @Schema(description = "客户名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三")
    @NotBlank(message = "客户名称不能为空")
    @Size(max = 100, message = "客户名称长度不能超过 100 个字符")
    private String name;

    @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED, example = "13800138000")
    @NotBlank(message = "手机号不能为空")
    @Mobile
    private String mobile;

    @Schema(description = "合同金额，单位：元", requiredMode = Schema.RequiredMode.REQUIRED, example = "100000.00")
    @NotNull(message = "合同金额不能为空")
    @DecimalMin(value = "0", message = "合同金额不能小于 0")
    private BigDecimal amount;

    @Schema(description = "合同日期", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-08-01")
    @NotNull(message = "合同日期不能为空")
    @PastOrPresent(message = "合同日期不能晚于今天") // 合同签订日期业务上不允许落在未来
    private LocalDate contractDate;
}

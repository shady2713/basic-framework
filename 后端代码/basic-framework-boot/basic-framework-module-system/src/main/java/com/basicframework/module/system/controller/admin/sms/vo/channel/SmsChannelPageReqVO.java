package com.basicframework.module.system.controller.admin.sms.vo.channel;

import static com.basicframework.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

@Schema(description = "管理后台 - 短信渠道分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SmsChannelPageReqVO extends PageParam {

    @Schema(description = "任务状态", example = "1")
    private Integer status;

    @Schema(description = "短信签名，模糊匹配", example = "基础框架")
    private String signature;

    @Schema(description = "短信渠道编码", example = "ALIYUN")
    private String code;

    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description = "创建时间")
    private LocalDateTime[] createTime;
}

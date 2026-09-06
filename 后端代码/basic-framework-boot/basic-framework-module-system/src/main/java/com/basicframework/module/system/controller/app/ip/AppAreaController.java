package com.basicframework.module.system.controller.app.ip;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AREA_CHINA_NOT_EXISTS;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.ip.core.Area;
import com.basicframework.framework.ip.core.utils.AreaUtils;
import com.basicframework.module.system.controller.app.ip.vo.AppAreaNodeRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "应用端 - 地区")
@RestController
@RequestMapping("/system/area")
@Validated
public class AppAreaController {

    @GetMapping("/tree")
    @Operation(summary = "获得地区树")
    @PermitAll
    public CommonResult<List<AppAreaNodeRespVO>> getAreaTree() {
        Area area = AreaUtils.getArea(Area.ID_CHINA);
        if (area == null) {
            throw exception(AREA_CHINA_NOT_EXISTS);
        }
        return success(BeanUtils.toBean(area.getChildren(), AppAreaNodeRespVO.class));
    }
}

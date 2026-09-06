package com.basicframework.module.system.controller.app.dict;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.system.controller.app.dict.vo.AppDictDataRespVO;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.service.dict.DictDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "应用端 - 字典数据")
@RestController
@RequestMapping("/system/dict-data")
@Validated
@RequiredArgsConstructor
public class AppDictDataController {

    private final DictDataService dictDataService;

    @GetMapping("/type")
    @Operation(summary = "根据字典类型查询字典数据信息")
    @Parameter(name = "type", description = "字典类型", required = true, example = "common_status")
    @PermitAll
    public CommonResult<List<AppDictDataRespVO>> getDictDataListByType(
            @RequestParam("type") @NotBlank(message = "字典类型不能为空") @Size(max = 100, message = "字典类型长度不能超过 100 个字符")
                    String type) {
        List<DictDataDO> list = dictDataService.getDictDataList(CommonStatusEnum.ENABLE.getStatus(), type);
        return success(BeanUtils.toBean(list, AppDictDataRespVO.class));
    }
}

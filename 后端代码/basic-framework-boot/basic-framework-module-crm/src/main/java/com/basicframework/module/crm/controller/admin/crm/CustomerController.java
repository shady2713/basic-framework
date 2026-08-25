package com.basicframework.module.crm.controller.admin.crm;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.crm.controller.admin.crm.vo.customer.CustomerCreateReqVO;
import com.basicframework.module.crm.controller.admin.crm.vo.customer.CustomerPageReqVO;
import com.basicframework.module.crm.controller.admin.crm.vo.customer.CustomerRespVO;
import com.basicframework.module.crm.controller.admin.crm.vo.customer.CustomerUpdateReqVO;
import com.basicframework.module.crm.dal.dataobject.crm.CustomerDO;
import com.basicframework.module.crm.service.crm.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台 - 客户档案")
@RestController
@RequestMapping("/crm/customer")
@Validated
public class CustomerController {

    @Resource
    private CustomerService customerService;

    @PostMapping("/create")
    @Operation(summary = "创建客户")
    @PreAuthorize("@ss.hasPermission('crm:customer:create')")
    public CommonResult<Long> createCustomer(@Valid @RequestBody CustomerCreateReqVO createReqVO) {
        Long customerId = customerService.createCustomer(BeanUtils.toBean(createReqVO, CustomerDO.class));
        return success(customerId);
    }

    @PutMapping("/update")
    @Operation(summary = "更新客户")
    @PreAuthorize("@ss.hasPermission('crm:customer:update')")
    public CommonResult<Boolean> updateCustomer(@Valid @RequestBody CustomerUpdateReqVO updateReqVO) {
        customerService.updateCustomer(BeanUtils.toBean(updateReqVO, CustomerDO.class));
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除客户")
    @PreAuthorize("@ss.hasPermission('crm:customer:delete')")
    public CommonResult<Boolean> deleteCustomer(@RequestParam("id") Long id) {
        customerService.deleteCustomer(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得客户信息")
    @Parameter(name = "id", description = "客户编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('crm:customer:query')")
    public CommonResult<CustomerRespVO> getCustomer(@RequestParam("id") Long id) {
        CustomerDO customer = customerService.getCustomer(id);
        return success(BeanUtils.toBean(customer, CustomerRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得客户分页列表")
    @PreAuthorize("@ss.hasPermission('crm:customer:query')")
    public CommonResult<PageResult<CustomerRespVO>> getCustomerPage(@Validated CustomerPageReqVO pageReqVO) {
        PageResult<CustomerDO> pageResult =
                customerService.getCustomerPage(pageReqVO, pageReqVO.getName(), pageReqVO.getMobile());
        return success(BeanUtils.toBean(pageResult, CustomerRespVO.class));
    }
}

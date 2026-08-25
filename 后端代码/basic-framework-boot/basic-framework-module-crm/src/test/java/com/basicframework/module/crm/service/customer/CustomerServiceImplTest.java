package com.basicframework.module.crm.service.customer;

import static com.basicframework.module.crm.enums.ErrorCodeConstants.CRM_CUSTOMER_MOBILE_EXISTS;
import static com.basicframework.module.crm.enums.ErrorCodeConstants.CRM_CUSTOMER_NOT_EXISTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.crm.dal.dataobject.crm.CustomerDO;
import com.basicframework.module.crm.dal.mysql.crm.CustomerMapper;
import com.basicframework.module.crm.service.crm.CustomerServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link CustomerServiceImpl} 客户档案 Service 的单元测试
 *
 * 覆盖 create/update/delete/get/page 全路径：正常流程、手机号唯一性（含更新时排除自身）、
 * 客户不存在两条错误路径（更新/删除）。
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    private static final Long CUSTOMER_ID = 1024L;
    private static final Long OTHER_CUSTOMER_ID = 2048L;
    private static final String MOBILE = "13800138000";

    @InjectMocks
    private CustomerServiceImpl customerService;

    @Mock
    private CustomerMapper customerMapper;

    // ========== 创建 ==========

    @Test
    void createCustomer_success_returnsGeneratedId() {
        // 模拟 MyBatis-Plus insert 回填主键
        doAnswer(invocation -> {
                    CustomerDO arg = invocation.getArgument(0);
                    arg.setId(CUSTOMER_ID);
                    return 1;
                })
                .when(customerMapper)
                .insert(any(CustomerDO.class));

        CustomerDO customer = buildCustomer(null);

        Long customerId = customerService.createCustomer(customer);

        assertThat(customerId).isEqualTo(CUSTOMER_ID);
        verify(customerMapper).selectByMobile(MOBILE);
        verify(customerMapper).insert(customer);
    }

    @Test
    void createCustomer_mobileExists_throwsAndSkipsInsert() {
        CustomerDO existing = buildCustomer(OTHER_CUSTOMER_ID);
        when(customerMapper.selectByMobile(MOBILE)).thenReturn(existing);

        CustomerDO customer = buildCustomer(null);

        assertThatThrownBy(() -> customerService.createCustomer(customer))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CRM_CUSTOMER_MOBILE_EXISTS.getCode()));
        verify(customerMapper, never()).insert(any(CustomerDO.class));
    }

    // ========== 更新 ==========

    @Test
    void updateCustomer_success() {
        CustomerDO existing = buildCustomer(CUSTOMER_ID);
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(existing);

        CustomerDO updateObj = buildCustomer(CUSTOMER_ID);
        updateObj.setName("李四");

        customerService.updateCustomer(updateObj);

        verify(customerMapper).selectByMobile(MOBILE);
        verify(customerMapper).updateById(updateObj);
    }

    @Test
    void updateCustomer_mobileBelongsToSelf_isAllowed() {
        // 边界：手机号未变更，selectByMobile 查到的是自己，不应判重
        CustomerDO existing = buildCustomer(CUSTOMER_ID);
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(existing);
        when(customerMapper.selectByMobile(MOBILE)).thenReturn(existing);

        CustomerDO updateObj = buildCustomer(CUSTOMER_ID);

        customerService.updateCustomer(updateObj);

        verify(customerMapper).updateById(updateObj);
    }

    @Test
    void updateCustomer_notExists_throws() {
        CustomerDO updateObj = buildCustomer(CUSTOMER_ID);

        assertThatThrownBy(() -> customerService.updateCustomer(updateObj))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CRM_CUSTOMER_NOT_EXISTS.getCode()));
        verify(customerMapper, never()).updateById(any(CustomerDO.class));
    }

    @Test
    void updateCustomer_mobileTakenByOther_throws() {
        CustomerDO existing = buildCustomer(CUSTOMER_ID);
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(existing);
        when(customerMapper.selectByMobile(MOBILE)).thenReturn(buildCustomer(OTHER_CUSTOMER_ID));

        CustomerDO updateObj = buildCustomer(CUSTOMER_ID);

        assertThatThrownBy(() -> customerService.updateCustomer(updateObj))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CRM_CUSTOMER_MOBILE_EXISTS.getCode()));
        verify(customerMapper, never()).updateById(any(CustomerDO.class));
    }

    // ========== 删除 ==========

    @Test
    void deleteCustomer_success() {
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(buildCustomer(CUSTOMER_ID));

        customerService.deleteCustomer(CUSTOMER_ID);

        verify(customerMapper).deleteById(CUSTOMER_ID);
    }

    @Test
    void deleteCustomer_notExists_throws() {
        assertThatThrownBy(() -> customerService.deleteCustomer(CUSTOMER_ID))
                .isInstanceOfSatisfying(ServiceException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CRM_CUSTOMER_NOT_EXISTS.getCode()));
        verify(customerMapper, never()).deleteById(CUSTOMER_ID);
    }

    // ========== 查询 ==========

    @Test
    void getCustomer_returnsCustomer() {
        CustomerDO existing = buildCustomer(CUSTOMER_ID);
        when(customerMapper.selectById(CUSTOMER_ID)).thenReturn(existing);

        assertThat(customerService.getCustomer(CUSTOMER_ID)).isSameAs(existing);
    }

    @Test
    void getCustomerPage_delegatesToMapper() {
        PageParam pageParam = new PageParam();
        PageResult<CustomerDO> pageResult = new PageResult<>(List.of(buildCustomer(CUSTOMER_ID)), 1L);
        when(customerMapper.selectPage(eq(pageParam), eq("张"), eq(MOBILE))).thenReturn(pageResult);

        assertThat(customerService.getCustomerPage(pageParam, "张", MOBILE)).isSameAs(pageResult);
        verify(customerMapper).selectPage(pageParam, "张", MOBILE);
    }

    private CustomerDO buildCustomer(Long id) {
        CustomerDO customer = new CustomerDO();
        customer.setId(id);
        customer.setName("张三");
        customer.setMobile(MOBILE);
        customer.setAmount(new BigDecimal("100000.00"));
        customer.setContractDate(LocalDate.of(2026, 8, 1));
        return customer;
    }
}

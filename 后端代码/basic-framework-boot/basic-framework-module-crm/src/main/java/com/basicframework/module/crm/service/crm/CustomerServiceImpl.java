package com.basicframework.module.crm.service.crm;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.crm.enums.ErrorCodeConstants.CRM_CUSTOMER_MOBILE_EXISTS;
import static com.basicframework.module.crm.enums.ErrorCodeConstants.CRM_CUSTOMER_NOT_EXISTS;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.crm.dal.dataobject.crm.CustomerDO;
import com.basicframework.module.crm.dal.mysql.crm.CustomerMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * 客户档案 Service 实现类
 *
 */
@Service
@Validated
public class CustomerServiceImpl implements CustomerService {

    @Resource
    private CustomerMapper customerMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCustomer(CustomerDO customer) {
        // 校验手机号唯一
        validateMobileUnique(null, customer.getMobile());
        // 插入客户
        customerMapper.insert(customer);
        return customer.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCustomer(CustomerDO updateObj) {
        // 校验正确性
        validateCustomerExists(updateObj.getId());
        validateMobileUnique(updateObj.getId(), updateObj.getMobile());
        // 更新客户
        customerMapper.updateById(updateObj);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCustomer(Long id) {
        // 校验是否存在
        validateCustomerExists(id);
        // 删除客户
        customerMapper.deleteById(id);
    }

    private void validateCustomerExists(Long id) {
        if (id == null) {
            return;
        }
        if (customerMapper.selectById(id) == null) {
            throw exception(CRM_CUSTOMER_NOT_EXISTS);
        }
    }

    private void validateMobileUnique(Long id, String mobile) {
        CustomerDO customer = customerMapper.selectByMobile(mobile);
        if (customer == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的客户
        if (id == null) {
            throw exception(CRM_CUSTOMER_MOBILE_EXISTS, mobile);
        }
        if (!customer.getId().equals(id)) {
            throw exception(CRM_CUSTOMER_MOBILE_EXISTS, mobile);
        }
    }

    @Override
    public CustomerDO getCustomer(Long id) {
        return customerMapper.selectById(id);
    }

    @Override
    public PageResult<CustomerDO> getCustomerPage(PageParam pageParam, String name, String mobile) {
        return customerMapper.selectPage(pageParam, name, mobile);
    }
}

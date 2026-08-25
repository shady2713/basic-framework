package com.basicframework.module.crm.service.crm;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.crm.dal.dataobject.crm.CustomerDO;

/**
 * 客户档案 Service 接口
 *
 */
public interface CustomerService {

    /**
     * 创建客户
     *
     * @param customer 客户信息
     * @return 客户编号
     */
    Long createCustomer(CustomerDO customer);

    /**
     * 更新客户
     *
     * @param updateObj 客户信息
     */
    void updateCustomer(CustomerDO updateObj);

    /**
     * 删除客户
     *
     * @param id 客户编号
     */
    void deleteCustomer(Long id);

    /**
     * 获得客户信息
     *
     * @param id 客户编号
     * @return 客户信息
     */
    CustomerDO getCustomer(Long id);

    /**
     * 获得客户分页列表
     *
     * @param pageParam 分页参数
     * @param name      客户名称，模糊匹配
     * @param mobile    手机号，精确匹配
     * @return 客户分页列表
     */
    PageResult<CustomerDO> getCustomerPage(PageParam pageParam, String name, String mobile);
}

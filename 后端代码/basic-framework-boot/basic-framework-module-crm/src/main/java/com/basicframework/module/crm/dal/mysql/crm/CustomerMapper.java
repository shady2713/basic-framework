package com.basicframework.module.crm.dal.mysql.crm;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.crm.dal.dataobject.crm.CustomerDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CustomerMapper extends BaseMapperX<CustomerDO> {

    default PageResult<CustomerDO> selectPage(PageParam pageParam, String name, String mobile) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<CustomerDO>()
                        .likeIfPresent(CustomerDO::getName, name)
                        .eqIfPresent(CustomerDO::getMobile, mobile) // 手机号为精确匹配
                        .orderByDesc(CustomerDO::getId));
    }

    default CustomerDO selectByMobile(String mobile) {
        return selectOne(CustomerDO::getMobile, mobile);
    }
}

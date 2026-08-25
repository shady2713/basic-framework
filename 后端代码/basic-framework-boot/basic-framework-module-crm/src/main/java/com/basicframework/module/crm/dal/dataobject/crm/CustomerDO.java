package com.basicframework.module.crm.dal.dataobject.crm;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 客户档案表
 *
 */
@TableName("crm_customer")
@KeySequence("crm_customer_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
public class CustomerDO extends SoftDeletableDO {

    /**
     * 客户ID
     */
    @TableId
    private Long id;
    /**
     * 客户名称
     */
    private String name;
    /**
     * 手机号
     */
    private String mobile;
    /**
     * 合同金额
     */
    private BigDecimal amount;
    /**
     * 合同日期
     */
    private LocalDate contractDate;
}

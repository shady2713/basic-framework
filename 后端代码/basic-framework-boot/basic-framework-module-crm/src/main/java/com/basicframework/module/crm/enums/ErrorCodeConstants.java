package com.basicframework.module.crm.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * CRM 错误码枚举类
 *
 * crm 系统，使用 1-006-000-000 段（1-003~1-005 在
 * {@link com.basicframework.framework.common.exception.enums.ServiceErrorCodeRange}
 * 中预留给订单/支付/优惠券体系，尚未启用，故 CRM 跳过取 1-006）
 */
public interface ErrorCodeConstants {

    // ========== 客户档案 1-006-000-000 ==========
    ErrorCode CRM_CUSTOMER_NOT_EXISTS = new ErrorCode(1_006_000_000, "客户不存在");
    ErrorCode CRM_CUSTOMER_MOBILE_EXISTS = new ErrorCode(1_006_000_001, "已经存在手机号为【{}】的客户");
}

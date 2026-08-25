import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace CrmCustomerApi {
  /** 客户档案 */
  export interface Customer {
    id?: number;
    /** 客户名称 */
    name: string;
    /** 手机号 */
    mobile: string;
    /** 合同金额（元） */
    amount: number;
    /** 合同日期 */
    contractDate: string;
    createTime?: Date;
  }
}

/** 查询客户分页 */
export function getCustomerPage(params: PageParam) {
  return requestClient.get<PageResult<CrmCustomerApi.Customer>>(
    '/crm/customer/page',
    { params },
  );
}

/** 查询客户详情 */
export function getCustomer(id: number) {
  return requestClient.get<CrmCustomerApi.Customer>(
    `/crm/customer/get?id=${id}`,
  );
}

/** 新增客户 */
export function createCustomer(data: CrmCustomerApi.Customer) {
  return requestClient.post('/crm/customer/create', data);
}

/** 修改客户 */
export function updateCustomer(data: CrmCustomerApi.Customer) {
  return requestClient.put('/crm/customer/update', data);
}

/** 删除客户 */
export function deleteCustomer(id: number) {
  return requestClient.delete(`/crm/customer/delete?id=${id}`);
}

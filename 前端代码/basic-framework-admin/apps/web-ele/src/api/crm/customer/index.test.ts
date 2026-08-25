import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createCustomer,
  deleteCustomer,
  getCustomer,
  getCustomerPage,
  updateCustomer,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('crm customer API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps CRUD operations and preserves decimal and date fields', async () => {
    const page = { pageNo: 1, pageSize: 20 };
    const customer = {
      amount: 1200.5,
      contractDate: '2026-08-25',
      mobile: '13800138000',
      name: 'customer',
    };

    await getCustomerPage(page);
    await getCustomer(9);
    await createCustomer(customer);
    await updateCustomer(customer);
    await deleteCustomer(9);

    expect(requestClient.get.mock.calls).toEqual([
      ['/crm/customer/page', { params: page }],
      ['/crm/customer/get?id=9'],
    ]);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/crm/customer/create',
      customer,
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/crm/customer/update',
      customer,
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/crm/customer/delete?id=9',
    );
  });
});

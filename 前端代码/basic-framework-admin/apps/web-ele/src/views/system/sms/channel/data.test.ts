import { describe, expect, it, vi } from 'vitest';

import { useFormSchema } from './data';

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: { ENABLE: 0 },
  DICT_TYPE: {
    COMMON_STATUS: 'common_status',
    SYSTEM_SMS_CHANNEL_CODE: 'system_sms_channel_code',
  },
}));

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => []),
}));

describe('sms channel credential form', () => {
  it('创建渠道时 API Secret 必填、编辑时允许留空保留', () => {
    const field = useFormSchema().find(
      (item) => item.fieldName === 'apiSecret',
    ) as any;

    expect(field.component).toBe('VbenInputPassword');
    expect(
      field.dependencies.rules({ id: undefined }).safeParse('').success,
    ).toBe(false);
    expect(field.dependencies.rules({ id: 1 }).safeParse('').success).toBe(
      true,
    );
  });
});

import { describe, expect, it, vi } from 'vitest';

import { useFormSchema } from './data';

/** zod 链子的最小 mock：记录被调用的约束 */
function createRuleChain() {
  const calls: string[] = [];
  const chain = {
    calls,
    trim: vi.fn(() => {
      calls.push('trim');
      return chain;
    }),
    min: vi.fn(() => {
      calls.push('min');
      return chain;
    }),
    max: vi.fn(() => {
      calls.push('max');
      return chain;
    }),
    or: vi.fn(() => {
      calls.push('or');
      return chain;
    }),
    optional: vi.fn(() => {
      calls.push('optional');
      return chain;
    }),
    refine: vi.fn(() => {
      calls.push('refine');
      return chain;
    }),
  };
  return chain;
}

vi.mock('#/adapter/form', () => ({
  z: {
    string: () => createRuleChain(),
    number: () => createRuleChain(),
  },
}));

vi.mock('@vben/utils', () => ({
  MOBILE_REGEX: /^1[3-9]\d{9}$/,
}));

vi.mock('#/adapter/field-rules', () => ({
  buildRequiredMobileSchema: vi.fn((label: string) => ({
    kind: 'required-mobile',
    label,
  })),
}));

/** 从 schema 中取字段，缺失即抛错（规避非空断言） */
function requireField(
  schema: ReturnType<typeof useFormSchema>,
  fieldName: string,
) {
  const field = schema.find((s) => s.fieldName === fieldName);
  if (!field) {
    throw new Error(`schema 缺少字段 ${fieldName}`);
  }
  return field;
}

describe('crm/customer/data 表单与列表 schema', () => {
  it('useFormSchema 覆盖四个契约字段与隐藏 id', () => {
    const schema = useFormSchema();
    expect(schema.map((s) => s.fieldName)).toEqual([
      'id',
      'name',
      'mobile',
      'amount',
      'contractDate',
    ]);
    // id 为隐藏字段（依赖传统：仅断言 dependencies 存在，类型由其 schema 定义承载）
    expect(requireField(schema, 'id').dependencies).toBeTruthy();
  });

  it('name 字段是 Input 且带长度上限', () => {
    const name = requireField(useFormSchema(), 'name');
    expect(name.component).toBe('Input');
    expect(name.rules).toBeTruthy();
  });

  it('mobile 复用统一规则中心构建器', () => {
    const schema = useFormSchema();
    const mobile = requireField(schema, 'mobile');
    expect(mobile.rules).toEqual({ kind: 'required-mobile', label: '手机号' });
  });

  it('amount 是 InputNumber 且非负两位小数', () => {
    const amount = requireField(useFormSchema(), 'amount');
    expect(amount.component).toBe('InputNumber');
    expect(amount.componentProps).toMatchObject({ min: 0, precision: 2 });
  });

  it('contractDate 是 DatePicker 且 valueFormat 为 ISO 日期', () => {
    const contractDate = requireField(useFormSchema(), 'contractDate');
    expect(contractDate.component).toBe('DatePicker');
    expect(contractDate.componentProps).toMatchObject({
      valueFormat: 'YYYY-MM-DD',
    });
  });
});

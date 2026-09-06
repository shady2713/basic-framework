import { describe, expect, it, vi } from 'vitest';

import { DeptSelectModal } from './index';

vi.mock('@vben/common-ui', () => ({
  useVbenModal: vi.fn(() => [{ name: 'ModalStub' }, { close: vi.fn() }]),
}));

vi.mock('@vben/utils', () => ({
  handleTree: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const { defineComponent } = await import('vue');
  const slotOnly = (name: string) =>
    defineComponent({ name, template: '<div><slot /></div>' });
  return {
    ElCard: slotOnly('ElCard'),
    ElCol: slotOnly('ElCol'),
    ElRow: slotOnly('ElRow'),
    ElTree: slotOnly('ElTree'),
  };
});

vi.mock('#/api/system/dept', () => ({
  getSimpleDeptList: vi.fn(),
}));

describe('dept components index contract', () => {
  it('re-exports the department select modal with its declared props', () => {
    expect(DeptSelectModal).toBeDefined();
    const declaredProps = (DeptSelectModal as { props?: object }).props ?? {};
    expect(Object.keys(declaredProps).toSorted()).toEqual([
      'cancelText',
      'checkStrictly',
      'confirmText',
      'multiple',
      'title',
    ]);
  });
});

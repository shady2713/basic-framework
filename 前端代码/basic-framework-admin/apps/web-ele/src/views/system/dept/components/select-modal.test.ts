/* eslint-disable vue/one-component-per-file -- 测试内的轻量组件用于隔离树组件与弹窗。 */
import type { SystemDeptApi } from '#/api/system/dept';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getSimpleDeptList } from '#/api/system/dept';

import SelectModal from './select-modal.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  getCheckedKeys: vi.fn<() => (number | string)[]>(() => []),
  handleTree: vi.fn((value: unknown) => value),
  modalApi: {
    close: vi.fn(() => Promise.resolve()),
    getData: vi.fn(),
    lock: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
  setCheckedKeys: vi.fn(),
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenModal: vi.fn((config: ModalConfig) => {
      state.modalConfig = config;
      return [
        defineComponent({
          name: 'ModalStub',
          setup(_props, { slots }) {
            return () => h('section', slots.default?.());
          },
        }),
        state.modalApi,
      ];
    }),
  };
});

vi.mock('@vben/utils', () => ({
  handleTree: vi.fn((value: unknown) => state.handleTree(value)),
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const slotOnly = (name: string) =>
    defineComponent({
      name,
      setup(_props, { slots }) {
        return () => h('div', slots.default?.());
      },
    });
  return {
    ElCard: slotOnly('ElCard'),
    ElCol: slotOnly('ElCol'),
    ElRow: slotOnly('ElRow'),
    ElTree: defineComponent({
      name: 'ElTree',
      props: {
        checkStrictly: Boolean,
        data: { type: Array, default: () => [] },
        nodeKey: { type: String, default: 'id' },
        showCheckbox: Boolean,
      },
      emits: ['check'],
      setup(props) {
        return {
          getCheckedKeys: state.getCheckedKeys,
          setCheckedKeys: state.setCheckedKeys,
          nodeCount: () => (props.data as unknown[]).length,
        };
      },
      template: `
        <div data-test="dept-tree" :data-nodes="nodeCount()">
          <button data-test="tree-check" @click="$emit('check', {}, { checkedKeys: ['2', '3'] })">
            勾选
          </button>
        </div>
      `,
    }),
  };
});

vi.mock('#/api/system/dept', () => ({
  getSimpleDeptList: vi.fn(),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function department(id: number, name: string): SystemDeptApi.Dept {
  return {
    createTime: new Date('2026-09-01T00:00:00Z'),
    email: '',
    id,
    leaderUserId: null,
    name,
    phone: '',
    sort: 0,
    status: 0,
  };
}

function mountModal(options: Record<string, unknown> = {}) {
  return mount(SelectModal, { props: options });
}

describe('dept select modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.getCheckedKeys.mockReturnValue([]);
    state.handleTree.mockImplementation((value: unknown) => value);
    vi.mocked(getSimpleDeptList).mockResolvedValue([
      department(2, '研发一部'),
      department(3, '研发二部'),
    ]);
  });

  it('loads the dept tree and restores previously selected ids on open', async () => {
    state.handleTree.mockReturnValue([
      department(2, '研发一部'),
      department(3, '研发二部'),
    ]);
    state.modalApi.getData.mockReturnValue({
      selectedList: [department(2, '研发一部')],
    });
    const wrapper = mountModal();

    await modalConfig().onOpenChange(true);
    await flushPromises();

    expect(getSimpleDeptList).toHaveBeenCalledOnce();
    expect(state.handleTree).toHaveBeenCalledWith([
      department(2, '研发一部'),
      department(3, '研发二部'),
    ]);
    expect(
      wrapper.find('[data-test="dept-tree"]').attributes('data-nodes'),
    ).toBe('2');
    expect(state.setCheckedKeys).toHaveBeenCalledWith([2]);
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('emits the departments matching the checked ids on confirm', async () => {
    const wrapper = mountModal();
    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(true);
    await wrapper.find('[data-test="tree-check"]').trigger('click');

    await modalConfig().onConfirm();

    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual([
      department(2, '研发一部'),
      department(3, '研发二部'),
    ]);
    expect(state.modalApi.close).toHaveBeenCalledOnce();
  });

  it('reads checked keys from the tree in check-strictly mode', async () => {
    state.getCheckedKeys.mockReturnValue([3]);
    const wrapper = mountModal({ checkStrictly: true });
    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(true);

    await modalConfig().onConfirm();

    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual([
      department(3, '研发二部'),
    ]);
  });

  it('keeps only the last selection in single mode', async () => {
    const wrapper = mountModal({ multiple: false });
    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(true);

    await wrapper.find('[data-test="tree-check"]').trigger('click');
    await modalConfig().onConfirm();

    expect(wrapper.emitted('confirm')?.[0]?.[0]).toEqual([
      department(3, '研发二部'),
    ]);
    expect(state.setCheckedKeys).toHaveBeenCalledWith([3]);
  });

  it('skips loading when opened without data and clears state on close', async () => {
    state.modalApi.getData.mockReturnValue(undefined);
    mountModal();

    await modalConfig().onOpenChange(true);
    expect(getSimpleDeptList).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();

    await modalConfig().onOpenChange(false);
    state.modalApi.getData.mockReturnValue({});
    await modalConfig().onOpenChange(true);
    expect(getSimpleDeptList).toHaveBeenCalledOnce();
  });
});

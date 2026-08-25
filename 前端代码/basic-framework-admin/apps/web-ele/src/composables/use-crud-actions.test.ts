import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useCrudDeleteActions } from './use-crud-actions';

const { closeLoadingMock, showConfirmDialogMock, showSuccessMessageMock } =
  vi.hoisted(() => ({
    closeLoadingMock: vi.fn(),
    showConfirmDialogMock: vi.fn(),
    showSuccessMessageMock: vi.fn(),
  }));

vi.mock('element-plus', () => ({
  ElLoading: {
    service: vi.fn(() => ({ close: closeLoadingMock })),
  },
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: showConfirmDialogMock,
  showSuccessMessage: showSuccessMessageMock,
}));

describe('useCrudDeleteActions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    showConfirmDialogMock.mockResolvedValue('confirm');
  });

  it('preserves selection and closes loading when batch deletion fails', async () => {
    const failure = new Error('conflict');
    const batchDeleteApi = vi.fn().mockRejectedValue(failure);
    const refresh = vi.fn();
    const actions = useCrudDeleteActions({
      batchDeleteApi,
      deleteApi: vi.fn(),
      getRowKey: (row: { id: number }) => row.id,
      refresh,
    });
    actions.checkedIds.value = [1, 2];

    await expect(actions.handleDeleteBatch()).rejects.toBe(failure);

    expect(batchDeleteApi).toHaveBeenCalledWith([1, 2]);
    expect(actions.checkedIds.value).toEqual([1, 2]);
    expect(refresh).not.toHaveBeenCalled();
    expect(showSuccessMessageMock).not.toHaveBeenCalled();
    expect(closeLoadingMock).toHaveBeenCalledOnce();
  });
});

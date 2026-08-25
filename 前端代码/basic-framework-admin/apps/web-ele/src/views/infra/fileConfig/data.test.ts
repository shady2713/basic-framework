import { describe, expect, it, vi } from 'vitest';

import { useFormSchema, useGridFormSchema } from './data';

const dictOptions = [
  { label: '数据库', value: 1 },
  { label: '本地磁盘', value: 10 },
  { label: 'FTP 服务器', value: 11 },
  { label: 'SFTP 服务器', value: 12 },
  { label: 'S3 对象存储', value: 20 },
];

vi.mock('@vben/constants', () => ({
  DICT_TYPE: {
    INFRA_BOOLEAN_STRING: 'infra_boolean_string',
    INFRA_FILE_STORAGE: 'infra_file_storage',
  },
}));

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => dictOptions),
}));

vi.mock('#/utils', () => ({
  getRangePickerDefaultProps: () => ({}),
}));

function getStorageOptions(schema: any[]) {
  return (
    schema.find((item) => item.fieldName === 'storage')?.componentProps
      ?.options ?? []
  );
}

function getField(schema: any[], fieldName: string) {
  return schema.find((item) => item.fieldName === fieldName);
}

describe('fileConfig storage options', () => {
  it('编辑表单只展示本地存储和对象存储', () => {
    expect(
      getStorageOptions(useFormSchema()).map(
        (item: { value: number }) => item.value,
      ),
    ).toEqual([10, 20]);
  });

  it('搜索表单只展示本地存储和对象存储', () => {
    expect(
      getStorageOptions(useGridFormSchema()).map(
        (item: { value: number }) => item.value,
      ),
    ).toEqual([10, 20]);
  });

  it('创建 S3 配置时密钥必填、编辑时允许留空保留', () => {
    const field = getField(useFormSchema(), 'config.accessSecret');

    expect(field.component).toBe('VbenInputPassword');
    expect(
      field.dependencies.rules({ id: undefined, storage: 20 }).safeParse('')
        .success,
    ).toBe(false);
    expect(
      field.dependencies.rules({ id: 1, storage: 20 }).safeParse('').success,
    ).toBe(true);
  });
});

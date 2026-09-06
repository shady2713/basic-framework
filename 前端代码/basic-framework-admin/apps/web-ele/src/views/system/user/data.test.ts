import { describe, expect, it, vi } from 'vitest';

import {
  useAssignRoleFormSchema,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
  useImportFormSchema,
  useResetPasswordFormSchema,
} from './data';

function createRuleChain() {
  return {
    default: vi.fn(() => createRuleChain()),
    email: vi.fn(() => createRuleChain()),
    max: vi.fn(() => createRuleChain()),
    min: vi.fn(() => createRuleChain()),
    optional: vi.fn(() => createRuleChain()),
    or: vi.fn(() => createRuleChain()),
    regex: vi.fn(() => createRuleChain()),
    refine: vi.fn(() => createRuleChain()),
  };
}

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: {
    DISABLE: 1,
    ENABLE: 0,
  },
  DICT_TYPE: {
    COMMON_STATUS: 'common_status',
    SYSTEM_USER_SEX: 'system_user_sex',
  },
}));

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => []),
}));

vi.mock('@vben/utils', () => ({
  handleTree: (data: unknown) => data,
  MOBILE_REGEX: /^1[3-9]\d{9}$/,
}));

vi.mock('#/api/system/dept', () => ({
  getDeptList: vi.fn(async () => []),
}));

vi.mock('#/api/system/post', () => ({
  getSimplePostList: vi.fn(async () => []),
}));

vi.mock('#/api/system/role', () => ({
  getSimpleRoleList: vi.fn(async () => []),
}));

vi.mock('#/utils', () => ({
  getRangePickerDefaultProps: () => ({}),
}));

vi.mock('#/adapter/form', () => ({
  buildOptionalEmailSchema: vi.fn(() => 'optionalEmail'),
  buildOptionalMobileSchema: vi.fn(() => 'optionalMobile'),
  buildOptionalRemarkSchema: vi.fn(() => 'optionalRemark'),
  buildRequiredNicknameSchema: vi.fn(() => 'nicknameRequired'),
  buildRequiredPasswordSchema: vi.fn(() => 'passwordRequired'),
  buildRequiredUsernameSchema: vi.fn(() => 'usernameRequired'),
  z: {
    boolean: vi.fn(() => createRuleChain()),
    literal: vi.fn(() => createRuleChain()),
    number: vi.fn(() => createRuleChain()),
    string: vi.fn(() => createRuleChain()),
  },
}));

describe('system user form schema', () => {
  it('用户名与新增密码使用共享规则', () => {
    const schema = useFormSchema();
    const usernameField = schema.find((item) => item.fieldName === 'username');
    const passwordField = schema.find((item) => item.fieldName === 'password');

    expect(usernameField?.rules).toBe('usernameRequired');
    // 锁定新增用户密码项，避免后续回退成仅必填但不校验复杂度的表单配置。
    expect(passwordField).toMatchObject({
      component: 'VbenInputPassword',
      rules: 'passwordRequired',
    });
    expect(passwordField?.componentProps).toMatchObject({
      passwordStrength: true,
    });
  });

  it('keeps password reset, role assignment, import and grid contracts', async () => {
    expect(
      useResetPasswordFormSchema().map(({ fieldName }) => fieldName),
    ).toEqual(['id', 'newPassword', 'confirmPassword']);
    expect(useImportFormSchema().map(({ fieldName }) => fieldName)).toEqual([
      'file',
      'updateSupport',
    ]);
    expect(useGridFormSchema().map(({ fieldName }) => fieldName)).toEqual([
      'username',
      'mobile',
      'createTime',
    ]);

    const roleIds = useAssignRoleFormSchema().find(
      ({ fieldName }) => fieldName === 'roleIds',
    );
    const afterFetch = (
      roleIds?.componentProps as {
        afterFetch: (
          roles: Array<{ name: string; status: number }>,
        ) => Promise<
          Array<{ disabled: boolean; name: string; status: number }>
        >;
      }
    ).afterFetch;
    await expect(
      afterFetch([
        { name: '管理员', status: 0 },
        { name: '停用角色', status: 1 },
      ]),
    ).resolves.toEqual([
      { disabled: false, name: '管理员', status: 0 },
      { disabled: true, name: '停用角色', status: 1 },
    ]);

    const statusChange = vi.fn(async () => true);
    const statusColumn = useGridColumns(statusChange)?.find(
      ({ field }) => field === 'status',
    );
    expect(statusColumn?.cellRender).toMatchObject({
      attrs: { beforeChange: statusChange },
      name: 'CellSwitch',
    });
  });
});

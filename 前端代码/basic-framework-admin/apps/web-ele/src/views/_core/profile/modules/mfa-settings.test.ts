/* eslint-disable vue/one-component-per-file -- 测试内的轻量组件仅用于隔离 Element Plus。 */
import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getUserMfaEnrollmentMethods,
  getUserMfaFactors,
  getUserMfaMethods,
  removeUserMfaFactor,
  resetUserMfaRecoveryCodes,
  startUserTotpEnrollment,
} from '#/api/system/user/profile';
import { showConfirmDialog, showSuccessMessage } from '#/utils/feedback';
import { requestMfaStepUp } from '#/utils/mfa-step-up';

import MfaSettings from './mfa-settings.vue';

const testState = vi.hoisted(() => ({
  cancellation: new Error('user cancelled'),
  logError: vi.fn(),
  qrCodeToDataUrl: vi.fn(),
}));

vi.mock('@vben/utils', () => ({
  logError: testState.logError,
}));

vi.mock('qrcode', () => ({
  default: { toDataURL: testState.qrCodeToDataUrl },
}));

vi.mock('#/api/system/user/profile', () => ({
  finishManagedUserTotpEnrollment: vi.fn(),
  finishUserTotpEnrollment: vi.fn(),
  getUserMfaEnrollmentMethods: vi.fn(),
  getUserMfaFactors: vi.fn(),
  getUserMfaMethods: vi.fn(),
  removeUserMfaFactor: vi.fn(),
  resetUserMfaRecoveryCodes: vi.fn(),
  startManagedUserTotpEnrollment: vi.fn(),
  startUserTotpEnrollment: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showConfirmDialog: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('#/utils/mfa-step-up', () => ({
  isMfaStepUpCancelled: (error: unknown) => error === testState.cancellation,
  requestMfaStepUp: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const { defineComponent } = await import('vue');
  return {
    ElAlert: defineComponent({
      name: 'ElAlert',
      template: '<aside><slot /></aside>',
    }),
    ElButton: defineComponent({
      name: 'ElButton',
      props: { disabled: Boolean, loading: Boolean },
      emits: ['click'],
      template: `
        <button :disabled="disabled" @click="$emit('click')"><slot /></button>
      `,
    }),
    ElInput: defineComponent({
      name: 'ElInput',
      props: { modelValue: { default: '', type: String } },
      emits: ['update:modelValue'],
      template: `
        <input
          data-test="text-input"
          :value="modelValue"
          @input="$emit('update:modelValue', $event.target.value)"
        />
      `,
    }),
  };
});

type SettingsWrapper = ReturnType<typeof mountSettings>;

function mountSettings() {
  return mount(MfaSettings);
}

function findButton(wrapper: SettingsWrapper, label: string) {
  const button = wrapper
    .findAll('button')
    .find((candidate) => candidate.text().includes(label));
  if (!button) {
    throw new Error(`未找到按钮：${label}`);
  }
  return button;
}

describe('profile MFA settings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getUserMfaMethods).mockResolvedValue([]);
    vi.mocked(getUserMfaEnrollmentMethods).mockResolvedValue(['TOTP']);
    vi.mocked(getUserMfaFactors).mockResolvedValue([]);
    vi.mocked(requestMfaStepUp).mockResolvedValue(undefined);
    vi.mocked(showConfirmDialog).mockResolvedValue({} as never);
    testState.qrCodeToDataUrl.mockResolvedValue('data:image/png;base64,test');
  });

  it('首次 TOTP 注册要求当前密码并生成本地二维码', async () => {
    vi.mocked(startUserTotpEnrollment).mockResolvedValue({
      enrollmentToken: 'enrollment-token',
      otpauthUri: 'otpauth://totp/example',
      secret: 'TOTP-SECRET',
    });
    const wrapper = mountSettings();
    await flushPromises();

    const startButton = findButton(wrapper, '使用动态验证码');
    expect(startButton.attributes('disabled')).toBeDefined();
    await wrapper.find('[data-test="text-input"]').setValue('current-password');
    await startButton.trigger('click');
    await flushPromises();

    expect(startUserTotpEnrollment).toHaveBeenCalledWith('current-password');
    expect(testState.qrCodeToDataUrl).toHaveBeenCalledWith(
      'otpauth://totp/example',
      expect.objectContaining({ errorCorrectionLevel: 'M', width: 220 }),
    );
    expect(wrapper.text()).toContain('TOTP-SECRET');
    expect(testState.logError).not.toHaveBeenCalled();
  });

  it('取消移除确认时静默结束且不触发二次验证', async () => {
    vi.mocked(getUserMfaMethods).mockResolvedValue(['TOTP']);
    vi.mocked(getUserMfaFactors).mockResolvedValue([
      {
        createTime: '2026-08-30 03:00:00',
        id: 42,
        name: '主认证器',
        type: 'TOTP',
      },
    ]);
    vi.mocked(showConfirmDialog).mockRejectedValue(testState.cancellation);
    const wrapper = mountSettings();
    await flushPromises();

    await findButton(wrapper, '移除').trigger('click');
    await flushPromises();

    expect(requestMfaStepUp).not.toHaveBeenCalled();
    expect(removeUserMfaFactor).not.toHaveBeenCalled();
    expect(testState.logError).not.toHaveBeenCalled();
  });

  it('确认移除后先完成二次验证再变更因子', async () => {
    vi.mocked(getUserMfaMethods).mockResolvedValue(['TOTP']);
    vi.mocked(getUserMfaFactors).mockResolvedValue([
      {
        createTime: '2026-08-30 03:00:00',
        id: 42,
        name: '主认证器',
        type: 'TOTP',
      },
    ]);
    vi.mocked(removeUserMfaFactor).mockResolvedValue(true);
    const wrapper = mountSettings();
    await flushPromises();

    await findButton(wrapper, '移除').trigger('click');
    await flushPromises();

    expect(requestMfaStepUp).toHaveBeenCalledOnce();
    expect(removeUserMfaFactor).toHaveBeenCalledWith(42);
    expect(requestMfaStepUp).toHaveBeenCalledBefore(
      vi.mocked(removeUserMfaFactor),
    );
    expect(showSuccessMessage).toHaveBeenCalledWith('MFA 因子已移除');
    expect(getUserMfaMethods).toHaveBeenCalledTimes(2);
  });

  it('二次验证取消后不执行恢复码重置也不记错误', async () => {
    vi.mocked(getUserMfaMethods).mockResolvedValue(['TOTP']);
    vi.mocked(requestMfaStepUp).mockRejectedValue(testState.cancellation);
    const wrapper = mountSettings();
    await flushPromises();

    await findButton(wrapper, '重置恢复码').trigger('click');
    await flushPromises();

    expect(resetUserMfaRecoveryCodes).not.toHaveBeenCalled();
    expect(testState.logError).not.toHaveBeenCalled();
  });
});

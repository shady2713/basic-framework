/* eslint-disable vue/one-component-per-file -- 测试内的轻量组件仅用于隔离 UI 依赖。 */
import type { VueWrapper } from '@vue/test-utils';

import type { AuthApi } from '#/api/core/auth';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  finishRequiredTotpEnrollmentApi,
  startRequiredTotpEnrollmentApi,
  verifyRecoveryCodeApi,
  verifyTotpApi,
} from '#/api/core/auth';

import Login from './login.vue';

const authStore = vi.hoisted(() => ({
  authLogin: vi.fn(),
  completeMfaLogin: vi.fn(),
  loginLoading: false,
}));

const qrCode = vi.hoisted(() => ({
  toDataURL: vi.fn(),
}));

vi.mock('#/store', () => ({ useAuthStore: () => authStore }));

vi.mock('@vben/hooks', () => ({ isCaptchaEnable: () => false }));

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

vi.mock('@vben/utils', () => ({ logError: vi.fn() }));

vi.mock('#/adapter/form', () => ({
  buildLoginPasswordSchema: vi.fn(() => []),
  buildRequiredUsernameSchema: vi.fn(() => []),
}));

vi.mock('#/api/core/auth', () => ({
  checkCaptcha: vi.fn(),
  finishRequiredTotpEnrollmentApi: vi.fn(),
  getCaptcha: vi.fn(),
  startRequiredTotpEnrollmentApi: vi.fn(),
  verifyRecoveryCodeApi: vi.fn(),
  verifyTotpApi: vi.fn(),
}));

vi.mock('qrcode', () => ({ default: qrCode }));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    AuthenticationLogin: defineComponent({
      name: 'AuthenticationLogin',
      emits: ['submit'],
      template: `
        <button
          data-test="credential-submit"
          @click="$emit('submit', { username: 'admin', password: 'password' })"
        >
          登录
        </button>
      `,
    }),
    Verification: defineComponent({
      name: 'VerificationStub',
      template: '<div />',
    }),
  };
});

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
    ElDialog: defineComponent({
      name: 'ElDialog',
      props: { modelValue: Boolean },
      emits: ['update:modelValue'],
      template:
        '<section v-if="modelValue" data-test="mfa-dialog"><slot /></section>',
    }),
    ElInput: defineComponent({
      name: 'ElInput',
      inheritAttrs: false,
      props: { modelValue: { default: '', type: String } },
      emits: ['update:modelValue'],
      template: `
        <input
          v-bind="$attrs"
          :value="modelValue"
          @input="$emit('update:modelValue', $event.target.value)"
        />
      `,
    }),
    ElRadioButton: defineComponent({
      name: 'ElRadioButton',
      template: '<span><slot /></span>',
    }),
    ElRadioGroup: defineComponent({
      name: 'ElRadioGroup',
      template: '<div><slot /></div>',
    }),
  };
});

interface LoginComponent {
  startTotpEnrollmentFromLogin: () => Promise<void>;
  submitCredentials: (values: AuthApi.LoginParams) => Promise<void>;
  submitMfa: () => Promise<void>;
}

function mountLogin() {
  return mount(Login);
}

function loginComponent(wrapper: VueWrapper) {
  return wrapper.vm as unknown as LoginComponent;
}

function findButton(wrapper: VueWrapper, label: string) {
  const button = wrapper
    .findAll('button')
    .find((candidate) => candidate.text().includes(label));
  if (!button) {
    throw new Error(`未找到按钮：${label}`);
  }
  return button;
}

function mockMfaChallenge(result: Partial<AuthApi.LoginResult> = {}) {
  authStore.authLogin.mockResolvedValue({
    loginResult: {
      mfaMethods: ['TOTP'],
      mfaRequired: true,
      mfaToken: 'mfa-token',
      userId: 1,
      ...result,
    },
    userInfo: null,
  });
}

describe('login MFA flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    qrCode.toDataURL.mockResolvedValue('data:image/png;base64,qr');
  });

  it('缺少挑战令牌或可用方法时失败关闭', async () => {
    mockMfaChallenge({ mfaMethods: [], mfaToken: undefined });
    const wrapper = mountLogin();

    await expect(
      loginComponent(wrapper).submitCredentials({
        password: 'password',
        username: 'admin',
      }),
    ).rejects.toThrow('MFA login challenge is incomplete');

    expect(wrapper.find('[data-test="mfa-dialog"]').exists()).toBe(false);
    expect(authStore.completeMfaLogin).not.toHaveBeenCalled();
  });

  it('只提交格式正确的六位 TOTP 并在成功后接管令牌', async () => {
    mockMfaChallenge();
    vi.mocked(verifyTotpApi).mockResolvedValue({
      accessToken: 'access-token',
      userId: 1,
    });
    const wrapper = mountLogin();
    await loginComponent(wrapper).submitCredentials({
      password: 'password',
      username: 'admin',
    });

    const input = wrapper.find('input[aria-label="6 位动态验证码"]');
    const submitButton = findButton(wrapper, '验证并登录');
    await input.setValue('abcdef');
    expect(submitButton.attributes('disabled')).toBeDefined();
    await loginComponent(wrapper).submitMfa();
    expect(verifyTotpApi).not.toHaveBeenCalled();

    await input.setValue('123456');
    await submitButton.trigger('click');
    await flushPromises();

    expect(verifyTotpApi).toHaveBeenCalledWith('mfa-token', '123456');
    expect(authStore.completeMfaLogin).toHaveBeenCalledWith({
      accessToken: 'access-token',
      userId: 1,
    });
    expect(wrapper.find('[data-test="mfa-dialog"]').exists()).toBe(false);
  });

  it('拒绝缺少访问令牌的 TOTP 完成响应', async () => {
    mockMfaChallenge();
    vi.mocked(verifyTotpApi).mockResolvedValue({ userId: 1 });
    const wrapper = mountLogin();
    const component = loginComponent(wrapper);
    await component.submitCredentials({
      password: 'password',
      username: 'admin',
    });

    await wrapper.find('input[aria-label="6 位动态验证码"]').setValue('123456');
    await expect(component.submitMfa()).rejects.toThrow(
      'MFA verification result did not include an access token',
    );

    expect(verifyTotpApi).toHaveBeenCalledWith('mfa-token', '123456');
    expect(authStore.completeMfaLogin).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="mfa-dialog"]').exists()).toBe(false);
  });

  it('规范化恢复码后再提交', async () => {
    mockMfaChallenge({ mfaMethods: ['RECOVERY_CODE'] });
    vi.mocked(verifyRecoveryCodeApi).mockResolvedValue({
      accessToken: 'access-token',
      userId: 1,
    });
    const wrapper = mountLogin();
    const component = loginComponent(wrapper);
    await component.submitCredentials({
      password: 'password',
      username: 'admin',
    });

    await wrapper
      .find('input[aria-label="一次性恢复码"]')
      .setValue('abcd-efgh-ijkl-mnop ');
    await component.submitMfa();

    expect(verifyRecoveryCodeApi).toHaveBeenCalledWith(
      'mfa-token',
      'ABCD-EFGH-IJKL-MNOP',
    );
  });

  it('totp 强制注册只在恢复码被确认保存后完成登录', async () => {
    mockMfaChallenge({ mfaEnrollmentRequired: true });
    vi.mocked(startRequiredTotpEnrollmentApi).mockResolvedValue({
      enrollmentToken: 'enrollment-token',
      otpauthUri: 'otpauth://totp/example',
      secret: 'TOTPSECRET',
    });
    vi.mocked(finishRequiredTotpEnrollmentApi).mockResolvedValue({
      accessToken: 'access-token',
      recoveryCodes: ['ABCD-EFGH-IJKL-MNOP'],
      userId: 1,
    });
    const wrapper = mountLogin();
    await loginComponent(wrapper).submitCredentials({
      password: 'password',
      username: 'admin',
    });

    await findButton(wrapper, '使用动态验证码').trigger('click');
    await flushPromises();
    expect(qrCode.toDataURL).toHaveBeenCalledWith(
      'otpauth://totp/example',
      expect.objectContaining({ width: 220 }),
    );

    await wrapper.find('input[aria-label="6 位动态验证码"]').setValue('123456');
    await loginComponent(wrapper).submitMfa();
    expect(authStore.completeMfaLogin).not.toHaveBeenCalled();

    await findButton(wrapper, '我已安全保存').trigger('click');
    await flushPromises();
    expect(authStore.completeMfaLogin).toHaveBeenCalledWith(
      expect.objectContaining({ accessToken: 'access-token' }),
    );
  });
});

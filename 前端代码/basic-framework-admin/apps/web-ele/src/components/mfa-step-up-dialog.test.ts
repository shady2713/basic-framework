/* eslint-disable vue/one-component-per-file -- 测试内的轻量组件仅用于隔离 Element Plus。 */
import { flushPromises, mount } from '@vue/test-utils';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  finishMfaStepUpRecoveryApi,
  finishMfaStepUpTotpApi,
  finishMfaStepUpWebAuthnApi,
  startMfaStepUpApi,
  startMfaStepUpWebAuthnApi,
} from '#/api/core/auth';
import { MfaStepUpCancelledError } from '#/utils/mfa-step-up';
import { getWebAuthnCredential } from '#/utils/webauthn';

import MfaStepUpDialog from './mfa-step-up-dialog.vue';

const coordinator = vi.hoisted(() => ({
  handler: undefined as (() => Promise<void>) | undefined,
  unregister: vi.fn(),
}));

vi.mock('#/api/core/auth', () => ({
  finishMfaStepUpRecoveryApi: vi.fn(),
  finishMfaStepUpTotpApi: vi.fn(),
  finishMfaStepUpWebAuthnApi: vi.fn(),
  startMfaStepUpApi: vi.fn(),
  startMfaStepUpWebAuthnApi: vi.fn(),
}));

vi.mock('#/utils/mfa-step-up', async (importOriginal) => ({
  ...(await importOriginal()),
  registerMfaStepUpHandler: vi.fn((handler: () => Promise<void>) => {
    coordinator.handler = handler;
    return coordinator.unregister;
  }),
}));

vi.mock('#/utils/webauthn', () => ({
  getWebAuthnCredential: vi.fn(),
}));

vi.mock('element-plus', async () => {
  const { defineComponent } = await import('vue');
  return {
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
      emits: ['close', 'update:modelValue'],
      template: `
        <section v-if="modelValue" data-test="dialog">
          <slot />
          <footer><slot name="footer" /></footer>
        </section>
      `,
    }),
    ElInput: defineComponent({
      name: 'ElInput',
      inheritAttrs: false,
      props: { modelValue: { default: '', type: String } },
      emits: ['update:modelValue'],
      template: `
        <input
          data-test="code-input"
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

function mountDialog() {
  return mount(MfaStepUpDialog);
}

function requirePromptHandler() {
  expect(coordinator.handler).toBeTypeOf('function');
  return coordinator.handler as () => Promise<void>;
}

function findButton(wrapper: ReturnType<typeof mountDialog>, label: string) {
  const button = wrapper
    .findAll('button')
    .find((candidate) => candidate.text().includes(label));
  if (!button) {
    throw new Error(`未找到按钮：${label}`);
  }
  return button;
}

describe('mfa step-up dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    coordinator.handler = undefined;
  });

  afterEach(() => {
    coordinator.handler = undefined;
  });

  it('服务端未提供令牌或可用方法时失败关闭', async () => {
    vi.mocked(startMfaStepUpApi).mockResolvedValue({
      mfaMethods: [],
      userId: 1,
    });
    const wrapper = mountDialog();

    const prompt = requirePromptHandler()();

    await expect(prompt).rejects.toBeInstanceOf(MfaStepUpCancelledError);
    expect(wrapper.find('[data-test="dialog"]').exists()).toBe(false);
    expect(finishMfaStepUpRecoveryApi).not.toHaveBeenCalled();
  });

  it('优先使用 WebAuthn 并只在服务端确认后放行', async () => {
    vi.mocked(startMfaStepUpApi).mockResolvedValue({
      mfaMethods: ['TOTP', 'WEBAUTHN'],
      mfaToken: 'step-up-token',
      userId: 1,
    });
    vi.mocked(startMfaStepUpWebAuthnApi).mockResolvedValue({
      ceremonyToken: 'ceremony-token',
      optionsJson: '{"publicKey":{}}',
    });
    vi.mocked(getWebAuthnCredential).mockResolvedValue('{"id":"credential"}');
    const wrapper = mountDialog();

    const prompt = requirePromptHandler()();
    await flushPromises();
    await findButton(wrapper, '验证并继续').trigger('click');
    await expect(prompt).resolves.toBeUndefined();

    expect(startMfaStepUpWebAuthnApi).toHaveBeenCalledWith('step-up-token');
    expect(finishMfaStepUpWebAuthnApi).toHaveBeenCalledWith(
      'ceremony-token',
      '{"id":"credential"}',
    );
    expect(finishMfaStepUpTotpApi).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="dialog"]').exists()).toBe(false);
  });

  it('只提交格式正确的六位 TOTP', async () => {
    vi.mocked(startMfaStepUpApi).mockResolvedValue({
      mfaMethods: ['TOTP'],
      mfaToken: 'step-up-token',
      userId: 1,
    });
    const wrapper = mountDialog();

    const prompt = requirePromptHandler()();
    await flushPromises();
    const verifyButton = findButton(wrapper, '验证并继续');
    expect(verifyButton.attributes('disabled')).toBeDefined();

    await wrapper.find('[data-test="code-input"]').setValue('123456');
    await verifyButton.trigger('click');
    await expect(prompt).resolves.toBeUndefined();

    expect(finishMfaStepUpTotpApi).toHaveBeenCalledWith(
      'step-up-token',
      '123456',
    );
  });

  it('验证失败后清除一次性凭据并保留重试入口', async () => {
    vi.mocked(startMfaStepUpApi).mockResolvedValue({
      mfaMethods: ['RECOVERY_CODE'],
      mfaToken: 'step-up-token',
      userId: 1,
    });
    vi.mocked(finishMfaStepUpRecoveryApi).mockRejectedValue(
      new Error('verification failed'),
    );
    const wrapper = mountDialog();

    const prompt = requirePromptHandler()();
    await flushPromises();
    const input = wrapper.find('[data-test="code-input"]');
    await input.setValue('ABCD-EFGH-IJKL-MNOP');
    await findButton(wrapper, '验证并继续').trigger('click');
    await flushPromises();

    expect(finishMfaStepUpRecoveryApi).toHaveBeenCalledWith(
      'step-up-token',
      'ABCD-EFGH-IJKL-MNOP',
    );
    expect(input.element).toHaveProperty('value', '');
    expect(wrapper.find('[data-test="dialog"]').exists()).toBe(true);

    await findButton(wrapper, '取消').trigger('click');
    await expect(prompt).rejects.toBeInstanceOf(MfaStepUpCancelledError);
  });

  it('组件卸载时注销处理器并拒绝未完成的验证', async () => {
    vi.mocked(startMfaStepUpApi).mockResolvedValue({
      mfaMethods: ['TOTP'],
      mfaToken: 'step-up-token',
      userId: 1,
    });
    const wrapper = mountDialog();
    const prompt = requirePromptHandler()();
    await flushPromises();

    wrapper.unmount();

    await expect(prompt).rejects.toBeInstanceOf(MfaStepUpCancelledError);
    expect(coordinator.unregister).toHaveBeenCalledOnce();
  });
});

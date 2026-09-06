<script setup lang="ts">
import type { AuthApi } from '#/api/core/auth';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import {
  ElButton,
  ElDialog,
  ElInput,
  ElRadioButton,
  ElRadioGroup,
} from 'element-plus';

import {
  finishMfaStepUpRecoveryApi,
  finishMfaStepUpTotpApi,
  startMfaStepUpApi,
} from '#/api/core/auth';
import {
  MfaStepUpCancelledError,
  registerMfaStepUpHandler,
} from '#/utils/mfa-step-up';

type StepUpMethod = 'RECOVERY_CODE' | 'TOTP';

const challenge = ref<AuthApi.LoginResult>();
const code = ref('');
const loading = ref(false);
const method = ref<StepUpMethod>('TOTP');
const visible = ref(false);

let rejectPrompt: ((reason: unknown) => void) | undefined;
let resolvePrompt: (() => void) | undefined;
let unregisterHandler: (() => void) | undefined;

const methods = computed(() => challenge.value?.mfaMethods ?? []);
const canVerify = computed(() =>
  method.value === 'TOTP'
    ? /^\d{6}$/.test(code.value)
    : code.value.trim().length > 0,
);

function selectDefaultMethod(
  availableMethods: readonly unknown[],
): StepUpMethod | undefined {
  if (availableMethods.includes('TOTP')) return 'TOTP';
  if (availableMethods.includes('RECOVERY_CODE')) return 'RECOVERY_CODE';
  return undefined;
}

function clearPrompt() {
  challenge.value = undefined;
  code.value = '';
  rejectPrompt = undefined;
  resolvePrompt = undefined;
}

function cancelPrompt() {
  const reject = rejectPrompt;
  visible.value = false;
  clearPrompt();
  reject?.(new MfaStepUpCancelledError());
}

async function openPrompt() {
  return new Promise<void>((resolve, reject) => {
    resolvePrompt = resolve;
    rejectPrompt = reject;
    loading.value = true;
    startMfaStepUpApi()
      .then((result) => {
        const availableMethods = Array.isArray(result.mfaMethods)
          ? result.mfaMethods
          : [];
        const defaultMethod = selectDefaultMethod(availableMethods);
        if (!result.mfaToken || !defaultMethod) {
          throw new MfaStepUpCancelledError(
            'MFA step-up challenge is incomplete',
          );
        }
        challenge.value = result;
        method.value = defaultMethod;
        visible.value = true;
      })
      .catch(() => {
        visible.value = false;
        clearPrompt();
        reject(new MfaStepUpCancelledError('MFA step-up could not start'));
      })
      .finally(() => {
        loading.value = false;
      });
  });
}

async function finishPrompt() {
  const mfaToken = challenge.value?.mfaToken;
  if (!mfaToken || !canVerify.value) return;
  loading.value = true;
  try {
    await (method.value === 'TOTP'
      ? finishMfaStepUpTotpApi(mfaToken, code.value)
      : finishMfaStepUpRecoveryApi(mfaToken, code.value));
    const resolve = resolvePrompt;
    visible.value = false;
    clearPrompt();
    resolve?.();
  } catch {
    code.value = '';
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  unregisterHandler = registerMfaStepUpHandler(openPrompt);
});

onBeforeUnmount(() => {
  unregisterHandler?.();
  rejectPrompt?.(new MfaStepUpCancelledError('MFA dialog unmounted'));
  clearPrompt();
});
</script>

<template>
  <ElDialog
    v-model="visible"
    :close-on-click-modal="!loading"
    :close-on-press-escape="!loading"
    :show-close="!loading"
    title="高风险操作二次验证"
    width="min(92vw, 460px)"
    @close="cancelPrompt"
  >
    <p class="mb-4 text-sm text-gray-500">
      为保护账号与系统配置，请使用已绑定的验证因子确认本次操作。
    </p>
    <ElRadioGroup v-model="method" class="mb-4">
      <ElRadioButton v-if="methods.includes('TOTP')" value="TOTP">
        动态验证码
      </ElRadioButton>
      <ElRadioButton
        v-if="methods.includes('RECOVERY_CODE')"
        value="RECOVERY_CODE"
      >
        恢复码
      </ElRadioButton>
    </ElRadioGroup>
    <ElInput
      v-model="code"
      :maxlength="method === 'TOTP' ? 6 : 19"
      :placeholder="method === 'TOTP' ? '6 位动态验证码' : '一次性恢复码'"
      autocomplete="one-time-code"
      @keyup.enter="finishPrompt"
    />
    <template #footer>
      <ElButton :disabled="loading" @click="cancelPrompt">取消</ElButton>
      <ElButton
        :disabled="!canVerify"
        :loading="loading"
        type="primary"
        @click="finishPrompt"
      >
        验证并继续
      </ElButton>
    </template>
  </ElDialog>
</template>

<script lang="ts" setup>
import type { VbenFormSchema } from '@vben/common-ui';

import type { AuthApi } from '#/api/core/auth';

import { computed, nextTick, ref } from 'vue';

import { AuthenticationLogin, Verification } from '@vben/common-ui';
import { isCaptchaEnable } from '@vben/hooks';
import { $t } from '@vben/locales';
import { logError } from '@vben/utils';

import {
  ElAlert,
  ElButton,
  ElDialog,
  ElInput,
  ElRadioButton,
  ElRadioGroup,
} from 'element-plus';
import QRCode from 'qrcode';

import {
  buildLoginPasswordSchema,
  buildRequiredUsernameSchema,
} from '#/adapter/form';
import {
  checkCaptcha,
  finishRequiredTotpEnrollmentApi,
  finishRequiredWebAuthnEnrollmentApi,
  finishWebAuthnAuthenticationApi,
  getCaptcha,
  startRequiredTotpEnrollmentApi,
  startRequiredWebAuthnEnrollmentApi,
  startWebAuthnAuthenticationApi,
  verifyRecoveryCodeApi,
  verifyTotpApi,
} from '#/api/core/auth';
import { useAuthStore } from '#/store';
import {
  createWebAuthnCredential,
  getWebAuthnCredential,
} from '#/utils/webauthn';

defineOptions({ name: 'Login' });

const authStore = useAuthStore();
const captchaEnable = isCaptchaEnable();

const loginRef = ref();
const verifyRef = ref();
const mfaVisible = ref(false);
const mfaLoading = ref(false);
type MfaMethod = 'RECOVERY_CODE' | 'TOTP' | 'WEBAUTHN';

const mfaMode = ref<MfaMethod>('TOTP');
const mfaCode = ref('');
const recoveryCode = ref('');
const pendingLogin = ref<AuthApi.LoginResult>();
const totpSetup = ref<AuthApi.TotpSetupResult>();
const qrCodeDataUrl = ref('');
const recoveryCodes = ref<string[]>([]);
const availableMfaMethods = computed<MfaMethod[]>(
  () => pendingLogin.value?.mfaMethods ?? [],
);

const captchaType = 'blockPuzzle';

async function handleLogin(values: Record<string, unknown>) {
  if (captchaEnable) {
    verifyRef.value.show();
    return;
  }
  await submitCredentials(values);
}

async function handleVerifySuccess({
  captchaVerification,
}: {
  captchaVerification: string;
}) {
  try {
    await submitCredentials({
      ...(await loginRef.value.getFormApi().getValues()),
      captchaVerification,
    });
  } catch (error) {
    logError('auth:login:verify', error);
  }
}

async function submitCredentials(values: AuthApi.LoginParams) {
  const { loginResult } = await authStore.authLogin('username', values);
  if (!loginResult.mfaRequired) {
    return;
  }
  pendingLogin.value = loginResult;
  mfaMode.value = selectDefaultMfaMethod(loginResult.mfaMethods ?? []);
  mfaVisible.value = true;
}

async function startTotpEnrollment(mfaToken: string) {
  mfaLoading.value = true;
  try {
    totpSetup.value = await startRequiredTotpEnrollmentApi(mfaToken);
    qrCodeDataUrl.value = await QRCode.toDataURL(totpSetup.value.otpauthUri, {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 220,
    });
    await nextTick();
  } catch (error) {
    logError('login:mfa:start-totp', error);
    resetMfaDialog();
  } finally {
    mfaLoading.value = false;
  }
}

async function startTotpEnrollmentFromLogin() {
  const mfaToken = pendingLogin.value?.mfaToken;
  if (!mfaToken) return;
  await startTotpEnrollment(mfaToken);
}

async function startWebAuthnEnrollment() {
  const mfaToken = pendingLogin.value?.mfaToken;
  if (!mfaToken) return;
  mfaLoading.value = true;
  try {
    const options = await startRequiredWebAuthnEnrollmentApi(mfaToken);
    const credentialJson = await createWebAuthnCredential(options.optionsJson);
    const result = await finishRequiredWebAuthnEnrollmentApi(
      options.ceremonyToken,
      credentialJson,
    );
    recoveryCodes.value = result.recoveryCodes ?? [];
    pendingLogin.value = result;
  } catch (error) {
    resetMfaDialog();
    throw error;
  } finally {
    mfaLoading.value = false;
  }
}

async function submitMfa() {
  if (!pendingLogin.value) return;
  mfaLoading.value = true;
  try {
    let result: AuthApi.LoginResult;
    if (totpSetup.value) {
      result = await finishRequiredTotpEnrollmentApi(
        totpSetup.value.enrollmentToken,
        mfaCode.value,
      );
      recoveryCodes.value = result.recoveryCodes ?? [];
      pendingLogin.value = result;
      return;
    }
    const mfaToken = pendingLogin.value.mfaToken;
    if (!mfaToken) return;
    if (mfaMode.value === 'WEBAUTHN') {
      const options = await startWebAuthnAuthenticationApi(mfaToken);
      const credentialJson = await getWebAuthnCredential(options.optionsJson);
      result = await finishWebAuthnAuthenticationApi(
        options.ceremonyToken,
        credentialJson,
      );
    } else if (mfaMode.value === 'TOTP') {
      result = await verifyTotpApi(mfaToken, mfaCode.value);
    } else {
      result = await verifyRecoveryCodeApi(mfaToken, recoveryCode.value);
    }
    mfaVisible.value = false;
    await authStore.completeMfaLogin(result);
  } catch (error) {
    resetMfaDialog();
    throw error;
  } finally {
    mfaLoading.value = false;
  }
}

function selectDefaultMfaMethod(methods: MfaMethod[]): MfaMethod {
  if (methods.includes('WEBAUTHN')) return 'WEBAUTHN';
  if (methods.includes('TOTP')) return 'TOTP';
  return 'RECOVERY_CODE';
}

function mfaMethodLabel(method: MfaMethod) {
  if (method === 'WEBAUTHN') return '安全密钥';
  if (method === 'TOTP') return '动态验证码';
  return '恢复码';
}

async function continueAfterRecoveryCodes() {
  if (!pendingLogin.value?.accessToken) return;
  const result = pendingLogin.value;
  resetMfaDialog();
  await authStore.completeMfaLogin(result);
}

function resetMfaDialog() {
  mfaVisible.value = false;
  mfaMode.value = 'TOTP';
  mfaCode.value = '';
  recoveryCode.value = '';
  pendingLogin.value = undefined;
  totpSetup.value = undefined;
  qrCodeDataUrl.value = '';
  recoveryCodes.value = [];
}

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: $t('authentication.usernameTip'),
      },
      fieldName: 'username',
      label: $t('authentication.username'),
      rules: buildRequiredUsernameSchema($t('authentication.username')),
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: $t('authentication.passwordTip'),
      },
      fieldName: 'password',
      label: $t('authentication.password'),
      rules: buildLoginPasswordSchema($t('authentication.password')),
    },
  ];
});
</script>

<template>
  <div>
    <AuthenticationLogin
      ref="loginRef"
      :form-schema="formSchema"
      :loading="authStore.loginLoading"
      :show-third-party-login="false"
      @submit="handleLogin"
    />
    <Verification
      ref="verifyRef"
      v-if="captchaEnable"
      :captcha-type="captchaType"
      :check-captcha-api="checkCaptcha"
      :get-captcha-api="getCaptcha"
      :img-size="{ width: '400px', height: '200px' }"
      mode="pop"
      @on-success="handleVerifySuccess"
    />
    <ElDialog
      v-model="mfaVisible"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      title="多因素认证"
      width="min(92vw, 460px)"
    >
      <template v-if="recoveryCodes.length > 0">
        <ElAlert
          :closable="false"
          show-icon
          title="请立即保存恢复码"
          type="warning"
        >
          每个恢复码只能使用一次；离开此页面后系统不会再次显示明文。
        </ElAlert>
        <div class="my-5 grid grid-cols-2 gap-2 font-mono text-sm">
          <code v-for="code in recoveryCodes" :key="code">{{ code }}</code>
        </div>
        <ElButton
          class="w-full"
          type="primary"
          @click="continueAfterRecoveryCodes"
        >
          我已安全保存，继续登录
        </ElButton>
      </template>

      <template v-else-if="totpSetup">
        <p class="mb-4 text-sm text-gray-600">
          超级管理员必须启用 MFA。请使用身份验证器扫描二维码，再输入 6
          位动态码。
        </p>
        <div class="flex flex-col items-center gap-3">
          <img
            v-if="qrCodeDataUrl"
            :src="qrCodeDataUrl"
            alt="TOTP 注册二维码"
          />
          <code class="break-all text-center text-xs">{{
            totpSetup.secret
          }}</code>
        </div>
        <ElInput
          v-model="mfaCode"
          class="mt-5"
          aria-label="6 位动态验证码"
          autocomplete="one-time-code"
          inputmode="numeric"
          maxlength="6"
          placeholder="请输入 6 位动态码"
        />
        <ElButton
          class="mt-4 w-full"
          :disabled="mfaCode.length !== 6"
          :loading="mfaLoading"
          type="primary"
          @click="submitMfa"
        >
          启用并登录
        </ElButton>
        <ElButton class="mt-2 w-full" @click="resetMfaDialog">
          返回重新登录
        </ElButton>
      </template>

      <template v-else-if="pendingLogin?.mfaEnrollmentRequired">
        <ElAlert
          :closable="false"
          class="mb-5"
          show-icon
          title="超级管理员必须启用多因素认证"
          type="warning"
        >
          推荐使用设备通行密钥或安全密钥；如果当前浏览器不支持，可使用动态验证码。
        </ElAlert>
        <ElButton
          v-if="availableMfaMethods.includes('WEBAUTHN')"
          class="w-full"
          :loading="mfaLoading"
          type="primary"
          @click="startWebAuthnEnrollment"
        >
          使用安全密钥（推荐）
        </ElButton>
        <ElButton
          v-if="availableMfaMethods.includes('TOTP')"
          class="mt-3 w-full"
          :disabled="mfaLoading"
          @click="startTotpEnrollmentFromLogin"
        >
          使用动态验证码
        </ElButton>
        <ElButton
          class="mt-2 w-full"
          :disabled="mfaLoading"
          @click="resetMfaDialog"
        >
          返回重新登录
        </ElButton>
      </template>

      <template v-else>
        <ElRadioGroup v-model="mfaMode" class="mb-4 w-full">
          <ElRadioButton
            v-for="method in availableMfaMethods"
            :key="method"
            :value="method"
          >
            {{ mfaMethodLabel(method) }}
          </ElRadioButton>
        </ElRadioGroup>
        <ElInput
          v-if="mfaMode === 'TOTP'"
          v-model="mfaCode"
          aria-label="6 位动态验证码"
          autocomplete="one-time-code"
          inputmode="numeric"
          maxlength="6"
          placeholder="请输入 6 位动态码"
        />
        <ElInput
          v-else-if="mfaMode === 'RECOVERY_CODE'"
          v-model="recoveryCode"
          aria-label="一次性恢复码"
          autocomplete="one-time-code"
          maxlength="19"
          placeholder="XXXX-XXXX-XXXX-XXXX"
        />
        <ElButton
          class="mt-4 w-full"
          :loading="mfaLoading"
          type="primary"
          @click="submitMfa"
        >
          {{ mfaMode === 'WEBAUTHN' ? '使用安全密钥验证' : '验证并登录' }}
        </ElButton>
        <ElButton class="mt-2 w-full" @click="resetMfaDialog">
          返回重新登录
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

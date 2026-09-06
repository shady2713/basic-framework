<script setup lang="ts">
import type { SystemUserProfileApi } from '#/api/system/user/profile';

import { computed, onMounted, ref } from 'vue';

import { logError } from '@vben/utils';

import { ElAlert, ElButton, ElInput } from 'element-plus';
import QRCode from 'qrcode';

import {
  finishManagedUserTotpEnrollment,
  finishUserTotpEnrollment,
  getUserMfaEnrollmentMethods,
  getUserMfaFactors,
  getUserMfaMethods,
  removeUserMfaFactor,
  resetUserMfaRecoveryCodes,
  startManagedUserTotpEnrollment,
  startUserTotpEnrollment,
} from '#/api/system/user/profile';
import { showConfirmDialog, showSuccessMessage } from '#/utils/feedback';
import { isMfaStepUpCancelled, requestMfaStepUp } from '#/utils/mfa-step-up';

const loading = ref(false);
const methods = ref<SystemUserProfileApi.MfaMethod[]>([]);
const enrollmentMethods = ref<SystemUserProfileApi.MfaMethod[]>([]);
const factors = ref<SystemUserProfileApi.MfaFactor[]>([]);
const password = ref('');
const totpSetup = ref<SystemUserProfileApi.MfaTotpSetupResult>();
const qrCodeDataUrl = ref('');
const totpCode = ref('');
const recoveryCodes = ref<string[]>([]);
const managedTotpEnrollment = ref(false);

const hasMfa = computed(() => methods.value.length > 0);
const mfaAvailable = computed(() => enrollmentMethods.value.length > 0);

async function loadMethods() {
  [methods.value, enrollmentMethods.value, factors.value] = await Promise.all([
    getUserMfaMethods(),
    getUserMfaEnrollmentMethods(),
    getUserMfaFactors(),
  ]);
}

async function startTotp() {
  if (!password.value) return;
  loading.value = true;
  try {
    totpSetup.value = await startUserTotpEnrollment(password.value);
    managedTotpEnrollment.value = false;
    qrCodeDataUrl.value = await QRCode.toDataURL(totpSetup.value.otpauthUri, {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 220,
    });
  } catch (error) {
    logError('profile:mfa:start-totp', error);
  } finally {
    loading.value = false;
  }
}

async function finishTotp() {
  if (!totpSetup.value || !/^\d{6}$/.test(totpCode.value)) return;
  loading.value = true;
  try {
    const result = managedTotpEnrollment.value
      ? await finishManagedUserTotpEnrollment(
          totpSetup.value.enrollmentToken,
          totpCode.value,
        )
      : await finishUserTotpEnrollment(
          totpSetup.value.enrollmentToken,
          totpCode.value,
        );
    recoveryCodes.value = result.recoveryCodes;
    password.value = '';
    totpSetup.value = undefined;
    totpCode.value = '';
    managedTotpEnrollment.value = false;
    await loadMethods();
  } catch (error) {
    logError('profile:mfa:finish-totp', error);
  } finally {
    loading.value = false;
  }
}

async function withStepUp(context: string, action: () => Promise<void>) {
  loading.value = true;
  try {
    await requestMfaStepUp();
    await action();
  } catch (error) {
    if (!isMfaStepUpCancelled(error)) {
      logError(context, error);
    }
  } finally {
    loading.value = false;
  }
}

async function startManagedTotp() {
  await withStepUp('profile:mfa:start-managed-totp', async () => {
    totpSetup.value = await startManagedUserTotpEnrollment();
    managedTotpEnrollment.value = true;
    qrCodeDataUrl.value = await QRCode.toDataURL(totpSetup.value.otpauthUri, {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 220,
    });
  });
}

async function removeFactor(factor: SystemUserProfileApi.MfaFactor) {
  try {
    await showConfirmDialog(`确认移除“${factor.name}”吗？`, {
      confirmButtonText: '移除',
      type: 'warning',
    });
  } catch {
    // 用户取消确认属于预期交互，不记录为系统错误。
    return;
  }
  await withStepUp('profile:mfa:remove-factor', async () => {
    await removeUserMfaFactor(factor.id);
    showSuccessMessage('MFA 因子已移除');
    await loadMethods();
  });
}

async function resetRecoveryCodes() {
  await withStepUp('profile:mfa:reset-recovery-codes', async () => {
    const result = await resetUserMfaRecoveryCodes();
    recoveryCodes.value = result.recoveryCodes;
  });
}

onMounted(loadMethods);
</script>

<template>
  <div class="mt-4 max-w-xl space-y-4">
    <template v-if="recoveryCodes.length > 0">
      <ElAlert
        :closable="false"
        show-icon
        title="请立即离线保存恢复码"
        type="warning"
      >
        每个恢复码只能使用一次，关闭页面后无法再次查看明文。
      </ElAlert>
      <div class="grid grid-cols-2 gap-2 font-mono text-sm">
        <code v-for="code in recoveryCodes" :key="code">{{ code }}</code>
      </div>
      <ElButton type="primary" @click="recoveryCodes = []">
        我已安全保存
      </ElButton>
    </template>

    <template v-else-if="totpSetup">
      <p class="text-sm text-gray-600">
        使用认证器扫描二维码，并输入当前 6 位验证码。
      </p>
      <img
        :src="qrCodeDataUrl"
        alt="TOTP 注册二维码"
        class="h-[220px] w-[220px]"
      />
      <code class="block break-all text-xs">{{ totpSetup.secret }}</code>
      <ElInput v-model="totpCode" maxlength="6" placeholder="6 位动态验证码" />
      <ElButton :loading="loading" type="primary" @click="finishTotp">
        {{ managedTotpEnrollment ? '验证并轮换' : '验证并启用' }}
      </ElButton>
    </template>

    <template v-else-if="hasMfa">
      <ElAlert
        :closable="false"
        show-icon
        title="多因素认证已启用"
        type="success"
      >
        当前方法：{{ methods.join('、') }}。新增、轮换、移除因子或重置恢复码前，
        系统会要求使用已有因子完成二次验证。
      </ElAlert>
      <div class="space-y-2">
        <div
          v-for="factor in factors"
          :key="factor.id"
          class="flex items-center justify-between rounded border border-gray-200 p-3"
        >
          <div>
            <div class="font-medium">{{ factor.name }}</div>
            <div class="text-xs text-gray-500">{{ factor.createTime }}</div>
          </div>
          <ElButton
            :loading="loading"
            plain
            type="danger"
            @click="removeFactor(factor)"
          >
            移除
          </ElButton>
        </div>
      </div>
      <div class="flex flex-wrap gap-3">
        <ElButton :loading="loading" @click="startManagedTotp">
          {{ methods.includes('TOTP') ? '轮换动态验证码' : '添加动态验证码' }}
        </ElButton>
        <ElButton :loading="loading" @click="resetRecoveryCodes">
          重置恢复码
        </ElButton>
      </div>
    </template>

    <template v-else-if="!mfaAvailable">
      <ElAlert
        :closable="false"
        show-icon
        title="当前部署未启用多因素认证"
        type="info"
      >
        启用后可在此注册动态验证码，并使用恢复码作为备用验证方式。
      </ElAlert>
    </template>

    <template v-else>
      <ElAlert
        :closable="false"
        show-icon
        title="建议启用多因素认证"
        type="info"
      >
        首次启用需要重新输入当前密码，然后使用身份验证器扫描二维码绑定动态验证码。
      </ElAlert>
      <ElInput
        v-model="password"
        autocomplete="current-password"
        placeholder="当前密码"
        show-password
        type="password"
      />
      <div class="flex gap-3">
        <ElButton
          v-if="enrollmentMethods.includes('TOTP')"
          :disabled="!password || loading"
          type="primary"
          @click="startTotp"
        >
          使用动态验证码
        </ElButton>
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { useElementPlusDesignTokens } from '@vben/hooks';

import { ElConfigProvider } from 'element-plus';

import MfaStepUpDialog from '#/components/mfa-step-up-dialog.vue';
import { elementLocale } from '#/locales';

defineOptions({ name: 'App' });

useElementPlusDesignTokens();
</script>

<template>
  <ElConfigProvider :locale="elementLocale">
    <RouterView />
    <MfaStepUpDialog />
  </ElConfigProvider>
</template>

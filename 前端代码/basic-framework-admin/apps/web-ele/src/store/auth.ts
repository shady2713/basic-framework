import type { AuthPermissionInfo, UserInfo } from '@vben/types';

import type { AuthApi } from '#/api';

import { ref } from 'vue';
import { useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { resetAllStores, useAccessStore, useUserStore } from '@vben/stores';

import { defineStore } from 'pinia';

import {
  getAuthPermissionInfoApi,
  loginApi,
  logoutApi,
  refreshTokenApi,
} from '#/api';
import { $t } from '#/locales';
import { showSuccessNotification } from '#/utils/feedback';

export const useAuthStore = defineStore('auth', () => {
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const router = useRouter();

  const loginLoading = ref(false);
  const sessionRestoreAttempted = ref(false);

  /**
   * 异步处理登录操作
   * Asynchronously handle the login process
   * @param type 登录类型
   * @param params 登录表单数据
   * @param onSuccess 登录成功后的回调函数
   */
  async function authLogin(
    type: 'username',
    params: AuthApi.LoginParams,
    onSuccess?: () => Promise<void> | void,
  ) {
    // 异步处理用户登录操作并获取 accessToken
    let userInfo: null | UserInfo = null;
    try {
      let loginResult: AuthApi.LoginResult;
      loginLoading.value = true;
      switch (type) {
        case 'username': {
          // 用户名密码登录的表单已强制校验密码必填，此处 password 必然存在
          if (params.password === undefined) {
            throw new Error('password is required for account login');
          }
          loginResult = await loginApi(params);
          break;
        }
        default: {
          throw new Error(`Unsupported login type: ${type}`);
        }
      }
      userInfo = await completeLogin(loginResult, onSuccess);
      return { loginResult, userInfo };
    } finally {
      loginLoading.value = false;
    }
  }

  /** MFA 验证完成后接管令牌并进入系统。 */
  async function completeMfaLogin(
    loginResult: AuthApi.LoginResult,
    onSuccess?: () => Promise<void> | void,
  ) {
    if (!loginResult.accessToken) {
      throw new Error('MFA login result did not include an access token');
    }
    loginLoading.value = true;
    try {
      return await completeLogin(loginResult, onSuccess);
    } finally {
      loginLoading.value = false;
    }
  }

  async function completeLogin(
    loginResult: AuthApi.LoginResult,
    onSuccess?: () => Promise<void> | void,
  ) {
    let userInfo: null | UserInfo = null;
    const { accessToken } = loginResult;

    if (accessToken) {
      accessStore.setAccessToken(accessToken);

      // 获取用户信息并存储到 userStore、accessStore 中
      const fetchUserInfoResult = await fetchUserInfo();

      userInfo = fetchUserInfoResult.user;

      if (accessStore.loginExpired) {
        accessStore.setLoginExpired(false);
      } else {
        onSuccess
          ? await onSuccess?.()
          : await router.push(preferences.app.defaultHomePath);
      }

      if (userInfo?.nickname) {
        showSuccessNotification({
          message: `${$t('authentication.loginSuccessDesc')}:${userInfo.nickname}`,
          duration: 3,
          title: $t('authentication.loginSuccess'),
        });
      }
    }
    return userInfo;
  }

  /** 退出登录，重置所有 store 状态并跳转到登录页 */
  async function logout(redirect: boolean = true) {
    try {
      await logoutApi(accessStore.accessToken);
    } catch {
      // 不做任何处理
    }
    resetAllStores();
    accessStore.setLoginExpired(false);

    // 回登录页带上当前路由地址
    const currentRoute = router.currentRoute.value;
    await router.replace({
      path: LOGIN_PATH,
      query:
        redirect && currentRoute.path !== LOGIN_PATH
          ? {
              redirect: encodeURIComponent(currentRoute.fullPath),
            }
          : {},
    });
  }

  /** 获取当前用户权限信息并更新到各个 store 中 */
  async function fetchUserInfo() {
    // 加载
    const authPermissionInfo: AuthPermissionInfo | null =
      await getAuthPermissionInfoApi();
    // userStore
    userStore.setUserInfo(authPermissionInfo.user);
    userStore.setUserRoles(authPermissionInfo.roles);
    // accessStore
    accessStore.setAccessMenus(authPermissionInfo.menus);
    accessStore.setAccessCodes(authPermissionInfo.permissions);
    return authPermissionInfo;
  }

  async function restoreSession() {
    if (sessionRestoreAttempted.value) {
      return Boolean(accessStore.accessToken);
    }
    sessionRestoreAttempted.value = true;
    try {
      const response = await refreshTokenApi();
      const accessToken = response?.data?.data?.accessToken;
      if (!accessToken) {
        return false;
      }
      accessStore.setAccessToken(accessToken);
      return true;
    } catch {
      return false;
    }
  }

  function $reset() {
    loginLoading.value = false;
    sessionRestoreAttempted.value = false;
  }

  return {
    $reset,
    authLogin,
    completeMfaLogin,
    fetchUserInfo,
    loginLoading,
    logout,
    restoreSession,
  };
});

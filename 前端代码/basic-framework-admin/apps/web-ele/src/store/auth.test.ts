import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getAuthPermissionInfoApi, loginApi, refreshTokenApi } from '#/api';

import { useAuthStore } from './auth';

const { accessStore, resetAllStoresMock, router } = vi.hoisted(() => ({
  accessStore: {
    accessToken: null as null | string,
    loginExpired: false,
    setAccessCodes: vi.fn(),
    setAccessMenus: vi.fn(),
    setAccessToken: vi.fn(),
    setLoginExpired: vi.fn(),
  },
  resetAllStoresMock: vi.fn(),
  router: {
    currentRoute: {
      value: {
        fullPath: '/auth/login?redirect=%2Fdashboard',
        path: '/auth/login',
      },
    },
    push: vi.fn(),
    replace: vi.fn(),
  },
}));

vi.mock('vue-router', () => ({ useRouter: () => router }));

vi.mock('@vben/stores', () => ({
  resetAllStores: resetAllStoresMock,
  useAccessStore: () => accessStore,
  useUserStore: () => ({
    setUserInfo: vi.fn(),
    setUserRoles: vi.fn(),
  }),
}));

vi.mock('#/api', () => ({
  getAuthPermissionInfoApi: vi.fn(),
  loginApi: vi.fn(),
  logoutApi: vi.fn(),
  refreshTokenApi: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));

vi.mock('#/utils/feedback', () => ({
  showSuccessNotification: vi.fn(),
}));

describe('auth store logout', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    accessStore.accessToken = null;
    accessStore.setAccessToken.mockImplementation((token: null | string) => {
      accessStore.accessToken = token;
    });
    setActivePinia(createPinia());
  });

  it('does not nest the login page in its own redirect query', async () => {
    const store = useAuthStore();

    await store.logout();

    expect(resetAllStoresMock).toHaveBeenCalledOnce();
    expect(router.replace).toHaveBeenCalledWith({
      path: '/auth/login',
      query: {},
    });
  });

  it('does not accept an access token before MFA is completed', async () => {
    const mfaToken = ['one', 'time', 'token'].join('-');
    const testPassword = ['pass', 'word'].join('');
    vi.mocked(loginApi).mockResolvedValue({
      mfaEnrollmentRequired: false,
      mfaRequired: true,
      mfaToken,
      userId: 1,
      expiresTime: 0,
    });
    const store = useAuthStore();

    const { loginResult, userInfo } = await store.authLogin('username', {
      password: testPassword,
      username: 'admin',
    });

    expect(loginResult.mfaRequired).toBe(true);
    expect(userInfo).toBeNull();
    expect(accessStore.setAccessToken).not.toHaveBeenCalled();
  });

  it('accepts an access token only after MFA completion', async () => {
    const accessToken = ['access', 'token'].join('-');
    vi.mocked(getAuthPermissionInfoApi).mockResolvedValue({
      menus: [],
      permissions: [],
      roles: [],
      user: { nickname: '管理员', userId: 1 },
    } as never);
    const store = useAuthStore();

    await store.completeMfaLogin({
      accessToken,
      expiresTime: 0,
      userId: 1,
    });

    expect(accessStore.setAccessToken).toHaveBeenCalledWith(accessToken);
    expect(router.push).toHaveBeenCalledOnce();
  });

  it('restores an access token from the HttpOnly refresh session once', async () => {
    vi.mocked(refreshTokenApi).mockResolvedValue({
      data: { data: { accessToken: 'restored-access-token' } },
    } as never);
    const store = useAuthStore();

    await expect(store.restoreSession()).resolves.toBe(true);
    await expect(store.restoreSession()).resolves.toBe(true);

    expect(refreshTokenApi).toHaveBeenCalledOnce();
    expect(accessStore.setAccessToken).toHaveBeenCalledWith(
      'restored-access-token',
    );
  });
});

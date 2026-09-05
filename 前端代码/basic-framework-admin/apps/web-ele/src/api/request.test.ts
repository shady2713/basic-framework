import { beforeEach, describe, expect, it, vi } from 'vitest';

import { refreshTokenApi } from './core';
import { baseRequestClient, requestClient } from './request';

interface TestRequestConfig {
  headers: Record<string, null | string>;
  url?: string;
}

interface TestRequestInterceptor {
  fulfilled: (config: TestRequestConfig) => Promise<TestRequestConfig>;
}

interface TestResponseInterceptor {
  rejected?: (error: unknown) => Promise<unknown> | unknown;
}

interface AuthenticateOptions {
  doReAuthenticate: () => Promise<void>;
  doRefreshToken: () => Promise<string>;
}

type ErrorMessageCallback = (message: string, error: unknown) => void;

const mocks = vi.hoisted(() => ({
  accessStore: {
    accessToken: null as null | string,
    isAccessChecked: false,
    setAccessToken: vi.fn(),
    setLoginExpired: vi.fn(),
  },
  authenticateOptions: undefined as AuthenticateOptions | undefined,
  clientInstances: [] as Array<{
    options: Record<string, unknown>;
    request: ReturnType<typeof vi.fn>;
  }>,
  errorMessageCallback: undefined as ErrorMessageCallback | undefined,
  logWarn: vi.fn(),
  logout: vi.fn(),
  requestInterceptors: [] as TestRequestInterceptor[],
  responseInterceptors: [] as TestResponseInterceptor[],
  retryAfterMfaStepUp: vi.fn(),
  showRequestError: vi.fn(),
}));

vi.mock('@vben/hooks', () => ({ useAppConfig: () => ({ apiURL: '/api' }) }));
vi.mock('@vben/preferences', () => ({
  preferences: {
    app: {
      enableRefreshToken: true,
      locale: 'zh-CN',
      loginExpiredMode: 'modal',
    },
  },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => mocks.accessStore }));
vi.mock('@vben/utils', () => ({ logWarn: mocks.logWarn }));
vi.mock('@vben/request', () => {
  class MockRequestClient {
    request = vi.fn(async () => 'retried');

    constructor(options: Record<string, unknown>) {
      mocks.clientInstances.push({ options, request: this.request });
    }

    addRequestInterceptor(config: TestRequestInterceptor) {
      mocks.requestInterceptors.push(config);
    }

    addResponseInterceptor(config: TestResponseInterceptor) {
      mocks.responseInterceptors.push(config);
    }
  }

  return {
    RequestClient: MockRequestClient,
    authenticateResponseInterceptor: vi.fn((options: AuthenticateOptions) => {
      mocks.authenticateOptions = options;
      return { kind: 'authenticate' };
    }),
    defaultResponseInterceptor: vi.fn(() => ({ kind: 'default' })),
    errorMessageResponseInterceptor: vi.fn((callback: ErrorMessageCallback) => {
      mocks.errorMessageCallback = callback;
      return { kind: 'error-message' };
    }),
  };
});
vi.mock('#/store', () => ({
  useAuthStore: () => ({ logout: mocks.logout }),
}));
vi.mock('#/utils/feedback', () => ({
  showRequestError: mocks.showRequestError,
}));
vi.mock('#/utils/mfa-step-up', () => ({
  retryAfterMfaStepUp: mocks.retryAfterMfaStepUp,
}));
vi.mock('./core', () => ({ refreshTokenApi: vi.fn() }));

describe('request client assembly', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.accessStore.accessToken = null;
    mocks.accessStore.isAccessChecked = false;
    mocks.accessStore.setAccessToken.mockImplementation(
      (token: null | string) => {
        mocks.accessStore.accessToken = token;
      },
    );
  });

  it('所有认证端点都启用同源 Cookie，业务客户端只返回 data', () => {
    expect(requestClient).toBeDefined();
    expect(baseRequestClient).toBeDefined();
    expect(mocks.clientInstances).toHaveLength(2);
    expect(mocks.clientInstances[0]?.options).toEqual({
      baseURL: '/api',
      responseReturn: 'data',
      withCredentials: true,
    });
    expect(mocks.clientInstances[1]?.options).toEqual({
      baseURL: '/api',
      withCredentials: true,
    });
  });

  it('请求头仅使用内存 access token 并携带当前语言', async () => {
    mocks.accessStore.accessToken = 'access-token';
    const config = { headers: {}, url: '/system/user' };

    await expect(mocks.requestInterceptors[0]?.fulfilled(config)).resolves.toBe(
      config,
    );
    expect(config.headers).toEqual({
      'Accept-Language': 'zh-CN',
      Authorization: 'Bearer access-token',
    });
  });

  it('刷新成功只保存新 access token，响应缺失 token 时抛稳定错误', async () => {
    vi.mocked(refreshTokenApi).mockResolvedValue({
      data: { data: { accessToken: 'new-access-token' } },
    } as never);

    await expect(mocks.authenticateOptions?.doRefreshToken()).resolves.toBe(
      'new-access-token',
    );
    expect(mocks.accessStore.setAccessToken).toHaveBeenCalledWith(
      'new-access-token',
    );

    vi.mocked(refreshTokenApi).mockResolvedValue({
      data: { data: {} },
    } as never);
    await expect(mocks.authenticateOptions?.doRefreshToken()).rejects.toThrow(
      'Refresh token response did not include an access token',
    );
  });

  it('会话失效时清除 token，并按配置显示过期弹窗', async () => {
    mocks.accessStore.accessToken = 'expired-token';
    mocks.accessStore.isAccessChecked = true;

    await mocks.authenticateOptions?.doReAuthenticate();

    expect(mocks.logWarn).toHaveBeenCalledWith('Authentication expired');
    expect(mocks.accessStore.setAccessToken).toHaveBeenCalledWith(null);
    expect(mocks.accessStore.setLoginExpired).toHaveBeenCalledWith(true);
    expect(mocks.logout).not.toHaveBeenCalled();
  });

  it('mfa 拦截器只使用原请求 URL 重试，最终错误交给统一反馈', async () => {
    const originalError = { config: { url: '/system/user/delete' } };
    mocks.retryAfterMfaStepUp.mockImplementation(
      async (
        error: unknown,
        retry: (config: TestRequestConfig) => Promise<unknown>,
      ) => {
        expect(error).toBe(originalError);
        return retry({ headers: {}, url: '/system/user/delete' });
      },
    );

    await expect(
      mocks.responseInterceptors[2]?.rejected?.(originalError),
    ).resolves.toBe('retried');
    expect(mocks.clientInstances[0]?.request).toHaveBeenCalledWith(
      '/system/user/delete',
      expect.objectContaining({ url: '/system/user/delete' }),
    );

    const responseError = new Error('safe error');
    mocks.errorMessageCallback?.('请求失败', responseError);
    expect(mocks.showRequestError).toHaveBeenCalledWith(
      responseError,
      '请求失败',
    );
  });
});

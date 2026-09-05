import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  checkCaptcha,
  finishMfaStepUpRecoveryApi,
  finishMfaStepUpTotpApi,
  finishMfaStepUpWebAuthnApi,
  finishRequiredTotpEnrollmentApi,
  finishRequiredWebAuthnEnrollmentApi,
  finishWebAuthnAuthenticationApi,
  getAuthPermissionInfoApi,
  getCaptcha,
  loginApi,
  logoutApi,
  refreshTokenApi,
  sendSmsCode,
  smsResetPassword,
  startMfaStepUpApi,
  startMfaStepUpWebAuthnApi,
  startRequiredTotpEnrollmentApi,
  startRequiredWebAuthnEnrollmentApi,
  startWebAuthnAuthenticationApi,
  verifyRecoveryCodeApi,
  verifyTotpApi,
} from './auth';

const requestClient = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));
const baseRequestClient = vi.hoisted(() => ({
  post: vi.fn(),
}));

vi.mock('#/api/request', () => ({ baseRequestClient, requestClient }));

describe('auth API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps login and MFA enrollment to their server contracts', async () => {
    const login = { password: 'password', username: 'admin' };

    await loginApi(login);
    await startRequiredTotpEnrollmentApi('mfa-token');
    await finishRequiredTotpEnrollmentApi('mfa-token', '123456');
    await verifyTotpApi('mfa-token', '654321');
    await verifyRecoveryCodeApi('mfa-token', 'recovery-code');
    await startRequiredWebAuthnEnrollmentApi('mfa-token');
    await finishRequiredWebAuthnEnrollmentApi('ceremony', 'credential');
    await startWebAuthnAuthenticationApi('mfa-token');
    await finishWebAuthnAuthenticationApi('ceremony', 'assertion');

    expect(requestClient.post.mock.calls).toEqual([
      ['/system/auth/login', login],
      ['/system/auth/mfa/totp/enroll/start', { mfaToken: 'mfa-token' }],
      [
        '/system/auth/mfa/totp/enroll/finish',
        { code: '123456', mfaToken: 'mfa-token' },
      ],
      [
        '/system/auth/mfa/totp/verify',
        { code: '654321', mfaToken: 'mfa-token' },
      ],
      [
        '/system/auth/mfa/recovery/verify',
        { mfaToken: 'mfa-token', recoveryCode: 'recovery-code' },
      ],
      ['/system/auth/mfa/webauthn/enroll/start', { mfaToken: 'mfa-token' }],
      [
        '/system/auth/mfa/webauthn/enroll/finish',
        { ceremonyToken: 'ceremony', credentialJson: 'credential' },
      ],
      [
        '/system/auth/mfa/webauthn/authenticate/start',
        { mfaToken: 'mfa-token' },
      ],
      [
        '/system/auth/mfa/webauthn/authenticate/finish',
        { ceremonyToken: 'ceremony', credentialJson: 'assertion' },
      ],
    ]);
  });

  it('maps every step-up method without leaking the first factor', async () => {
    await startMfaStepUpApi();
    await finishMfaStepUpTotpApi('mfa-token', '123456');
    await finishMfaStepUpRecoveryApi('mfa-token', 'recovery-code');
    await startMfaStepUpWebAuthnApi('mfa-token');
    await finishMfaStepUpWebAuthnApi('ceremony', 'assertion');

    expect(requestClient.post.mock.calls).toEqual([
      ['/system/auth/mfa/step-up/start'],
      [
        '/system/auth/mfa/step-up/totp/finish',
        { code: '123456', mfaToken: 'mfa-token' },
      ],
      [
        '/system/auth/mfa/step-up/recovery/finish',
        { mfaToken: 'mfa-token', recoveryCode: 'recovery-code' },
      ],
      ['/system/auth/mfa/step-up/webauthn/start', { mfaToken: 'mfa-token' }],
      [
        '/system/auth/mfa/step-up/webauthn/finish',
        { ceremonyToken: 'ceremony', credentialJson: 'assertion' },
      ],
    ]);
  });

  it('keeps refresh credentials in cookies and access tokens in headers', async () => {
    const captcha = { captchaType: 'blockPuzzle' as const };
    const captchaCheck = {
      captchaType: 'blockPuzzle' as const,
      pointJson: '{"x":1,"y":5}',
      token: 'captcha-token',
    };
    const sms = { mobile: '13800138000', scene: 1 };
    const reset = { code: '123456', mobile: sms.mobile, password: 'new-pass' };

    await refreshTokenApi();
    await logoutApi('access-token');
    await logoutApi(null);
    await getAuthPermissionInfoApi();
    await getCaptcha(captcha);
    await checkCaptcha(captchaCheck);
    await sendSmsCode(sms);
    await smsResetPassword(reset);

    expect(baseRequestClient.post.mock.calls).toEqual([
      ['/system/auth/refresh-token', {}],
      [
        '/system/auth/logout',
        {},
        { headers: { Authorization: 'Bearer access-token' } },
      ],
      ['/system/auth/logout', {}, undefined],
      ['/system/captcha/get', captcha],
      ['/system/captcha/check', captchaCheck],
    ]);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/system/auth/get-permission-info',
    );
    expect(requestClient.post).toHaveBeenNthCalledWith(
      1,
      '/system/auth/send-sms-code',
      sms,
    );
    expect(requestClient.post).toHaveBeenNthCalledWith(
      2,
      '/system/auth/reset-password',
      reset,
    );
  });
});

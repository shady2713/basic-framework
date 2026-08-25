import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  finishManagedUserTotpEnrollment,
  finishManagedUserWebAuthnEnrollment,
  finishUserTotpEnrollment,
  finishUserWebAuthnEnrollment,
  getUserMfaEnrollmentMethods,
  getUserMfaFactors,
  getUserMfaMethods,
  getUserProfile,
  removeUserMfaFactor,
  resetUserMfaRecoveryCodes,
  startManagedUserTotpEnrollment,
  startManagedUserWebAuthnEnrollment,
  startUserTotpEnrollment,
  startUserWebAuthnEnrollment,
  updateUserPassword,
  updateUserProfile,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('user profile API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps profile and MFA discovery endpoints', async () => {
    const profile = { email: 'user@example.com', nickname: 'user' };
    const password = { newPassword: 'new-pass', oldPassword: 'old-pass' };

    await getUserProfile();
    await updateUserProfile(profile);
    await updateUserPassword(password);
    await getUserMfaMethods();
    await getUserMfaEnrollmentMethods();
    await getUserMfaFactors();

    expect(requestClient.get.mock.calls).toEqual([
      ['/system/user/profile/get'],
      ['/system/user/profile/mfa/methods'],
      ['/system/user/profile/mfa/enrollment-methods'],
      ['/system/user/profile/mfa/factors'],
    ]);
    expect(requestClient.put.mock.calls).toEqual([
      ['/system/user/profile/update', profile],
      ['/system/user/profile/update-password', password],
    ]);
  });

  it('maps self-service enrollment to password-bound start endpoints', async () => {
    await startUserTotpEnrollment('password');
    await finishUserTotpEnrollment('mfa-token', '123456');
    await startUserWebAuthnEnrollment('password');
    await finishUserWebAuthnEnrollment('ceremony', 'credential');

    expect(requestClient.post.mock.calls).toEqual([
      ['/system/user/profile/mfa/totp/enroll/start', { password: 'password' }],
      [
        '/system/user/profile/mfa/totp/enroll/finish',
        { code: '123456', mfaToken: 'mfa-token' },
      ],
      [
        '/system/user/profile/mfa/webauthn/enroll/start',
        { password: 'password' },
      ],
      [
        '/system/user/profile/mfa/webauthn/enroll/finish',
        { ceremonyToken: 'ceremony', credentialJson: 'credential' },
      ],
    ]);
  });

  it('maps step-up-protected factor management endpoints', async () => {
    await startManagedUserTotpEnrollment();
    await finishManagedUserTotpEnrollment('mfa-token', '123456');
    await startManagedUserWebAuthnEnrollment();
    await finishManagedUserWebAuthnEnrollment('ceremony', 'credential');
    await removeUserMfaFactor(42);
    await resetUserMfaRecoveryCodes();

    expect(requestClient.post.mock.calls).toEqual([
      ['/system/user/profile/mfa/manage/totp/enroll/start'],
      [
        '/system/user/profile/mfa/manage/totp/enroll/finish',
        { code: '123456', mfaToken: 'mfa-token' },
      ],
      ['/system/user/profile/mfa/manage/webauthn/enroll/start'],
      [
        '/system/user/profile/mfa/manage/webauthn/enroll/finish',
        { ceremonyToken: 'ceremony', credentialJson: 'credential' },
      ],
      ['/system/user/profile/mfa/recovery-codes/reset'],
    ]);
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/system/user/profile/mfa/factors/42',
    );
  });
});

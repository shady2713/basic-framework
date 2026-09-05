import type { AxiosResponse, HttpResponse } from '@vben/request';
import type {
  AuthPermissionInfo,
  CaptchaChallenge,
  CaptchaCheckRequest,
  CaptchaGetRequest,
  CaptchaProtocolResponse,
} from '@vben/types';

import { baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    password?: string;
    username?: string;
    captchaVerification?: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    accessToken?: string;
    userId: number;
    expiresTime?: number;
    mfaRequired?: boolean;
    mfaEnrollmentRequired?: boolean;
    mfaToken?: string;
    mfaMethods?: Array<'RECOVERY_CODE' | 'TOTP' | 'WEBAUTHN'>;
    recoveryCodes?: string[];
  }

  export interface TotpSetupResult {
    enrollmentToken: string;
    otpauthUri: string;
    secret: string;
  }

  export interface WebAuthnOptionsResult {
    ceremonyToken: string;
    optionsJson: string;
  }

  /** 手机验证码获取接口参数 */
  export interface SmsCodeParams {
    mobile: string;
    scene: number;
  }

  /** 重置密码接口参数 */
  export interface ResetPasswordParams {
    password: string;
    mobile: string;
    code: string;
  }
}

/** 登录 */
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/system/auth/login', data);
}

/** 开始强制 TOTP 注册 */
export async function startRequiredTotpEnrollmentApi(mfaToken: string) {
  return requestClient.post<AuthApi.TotpSetupResult>(
    '/system/auth/mfa/totp/enroll/start',
    { mfaToken },
  );
}

/** 完成强制 TOTP 注册 */
export async function finishRequiredTotpEnrollmentApi(
  mfaToken: string,
  code: string,
) {
  return requestClient.post<AuthApi.LoginResult>(
    '/system/auth/mfa/totp/enroll/finish',
    { code, mfaToken },
  );
}

/** 使用 TOTP 完成登录 */
export async function verifyTotpApi(mfaToken: string, code: string) {
  return requestClient.post<AuthApi.LoginResult>(
    '/system/auth/mfa/totp/verify',
    { code, mfaToken },
  );
}

/** 使用一次性恢复码完成登录 */
export async function verifyRecoveryCodeApi(
  mfaToken: string,
  recoveryCode: string,
) {
  return requestClient.post<AuthApi.LoginResult>(
    '/system/auth/mfa/recovery/verify',
    { mfaToken, recoveryCode },
  );
}

/** 开始强制 WebAuthn 注册 */
export async function startRequiredWebAuthnEnrollmentApi(mfaToken: string) {
  return requestClient.post<AuthApi.WebAuthnOptionsResult>(
    '/system/auth/mfa/webauthn/enroll/start',
    { mfaToken },
  );
}

/** 完成强制 WebAuthn 注册 */
export async function finishRequiredWebAuthnEnrollmentApi(
  ceremonyToken: string,
  credentialJson: string,
) {
  return requestClient.post<AuthApi.LoginResult>(
    '/system/auth/mfa/webauthn/enroll/finish',
    { ceremonyToken, credentialJson },
  );
}

/** 开始 WebAuthn 登录认证 */
export async function startWebAuthnAuthenticationApi(mfaToken: string) {
  return requestClient.post<AuthApi.WebAuthnOptionsResult>(
    '/system/auth/mfa/webauthn/authenticate/start',
    { mfaToken },
  );
}

/** 完成 WebAuthn 登录认证 */
export async function finishWebAuthnAuthenticationApi(
  ceremonyToken: string,
  credentialJson: string,
) {
  return requestClient.post<AuthApi.LoginResult>(
    '/system/auth/mfa/webauthn/authenticate/finish',
    { ceremonyToken, credentialJson },
  );
}

/** 开始当前会话的 MFA 二次验证 */
export async function startMfaStepUpApi() {
  return requestClient.post<AuthApi.LoginResult>(
    '/system/auth/mfa/step-up/start',
  );
}

/** 使用 TOTP 完成当前会话的 MFA 二次验证 */
export async function finishMfaStepUpTotpApi(mfaToken: string, code: string) {
  return requestClient.post('/system/auth/mfa/step-up/totp/finish', {
    code,
    mfaToken,
  });
}

/** 使用恢复码完成当前会话的 MFA 二次验证 */
export async function finishMfaStepUpRecoveryApi(
  mfaToken: string,
  recoveryCode: string,
) {
  return requestClient.post('/system/auth/mfa/step-up/recovery/finish', {
    mfaToken,
    recoveryCode,
  });
}

/** 开始当前会话的 WebAuthn 二次验证 */
export async function startMfaStepUpWebAuthnApi(mfaToken: string) {
  return requestClient.post<AuthApi.WebAuthnOptionsResult>(
    '/system/auth/mfa/step-up/webauthn/start',
    { mfaToken },
  );
}

/** 完成当前会话的 WebAuthn 二次验证 */
export async function finishMfaStepUpWebAuthnApi(
  ceremonyToken: string,
  credentialJson: string,
) {
  return requestClient.post('/system/auth/mfa/step-up/webauthn/finish', {
    ceremonyToken,
    credentialJson,
  });
}

/** 刷新 accessToken */
export async function refreshTokenApi() {
  return baseRequestClient.post<
    AxiosResponse<HttpResponse<AuthApi.LoginResult>>
  >('/system/auth/refresh-token', {});
}

/** 退出登录 */
export async function logoutApi(accessToken: null | string) {
  return baseRequestClient.post(
    '/system/auth/logout',
    {},
    accessToken
      ? {
          headers: {
            Authorization: `Bearer ${accessToken}`,
          },
        }
      : undefined,
  );
}

/** 获取权限信息 */
export async function getAuthPermissionInfoApi() {
  return requestClient.get<AuthPermissionInfo>(
    '/system/auth/get-permission-info',
  );
}

/** 获取验证码 */
export async function getCaptcha(data: CaptchaGetRequest) {
  return baseRequestClient.post<
    AxiosResponse<CaptchaProtocolResponse<CaptchaChallenge>>
  >('/system/captcha/get', data);
}

/** 校验验证码 */
export async function checkCaptcha(data: CaptchaCheckRequest) {
  return baseRequestClient.post<AxiosResponse<CaptchaProtocolResponse>>(
    '/system/captcha/check',
    data,
  );
}

/** 获取登录验证码 */
export async function sendSmsCode(data: AuthApi.SmsCodeParams) {
  return requestClient.post('/system/auth/send-sms-code', data);
}

/** 通过短信重置密码 */
export async function smsResetPassword(data: AuthApi.ResetPasswordParams) {
  return requestClient.post('/system/auth/reset-password', data);
}

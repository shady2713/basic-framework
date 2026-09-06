import { requestClient } from '#/api/request';

export namespace SystemUserProfileApi {
  /** 用户个人中心信息 */
  export interface UserProfileRespVO {
    id: number;
    username: string;
    nickname: string;
    email?: string;
    mobile?: string;
    sex?: number;
    avatar?: string;
    loginIp: string;
    loginDate: string;
    createTime: string;
    roles: { id: number; name: string }[];
    dept: null | { id: number; name: string };
    posts: { id: number; name: string }[];
  }

  /** 更新密码请求 */
  export interface UpdatePasswordReqVO {
    oldPassword: string;
    newPassword: string;
  }

  /** 更新个人信息请求 */
  export interface UpdateProfileReqVO {
    nickname?: string;
    email?: string;
    mobile?: string;
    sex?: number;
    avatar?: string;
  }

  export type MfaMethod = 'TOTP';

  export interface MfaTotpSetupResult {
    enrollmentToken: string;
    otpauthUri: string;
    secret: string;
  }

  export interface MfaRecoveryCodesResult {
    recoveryCodes: string[];
  }

  export interface MfaFactor {
    createTime: string;
    id: number;
    name: string;
    type: MfaMethod;
  }
}

/** 获取登录用户信息 */
export function getUserProfile() {
  return requestClient.get<SystemUserProfileApi.UserProfileRespVO>(
    '/system/user/profile/get',
  );
}

/** 修改用户个人信息 */
export function updateUserProfile(
  data: SystemUserProfileApi.UpdateProfileReqVO,
) {
  return requestClient.put('/system/user/profile/update', data);
}

/** 修改用户个人密码 */
export function updateUserPassword(
  data: SystemUserProfileApi.UpdatePasswordReqVO,
) {
  return requestClient.put('/system/user/profile/update-password', data);
}

/** 查询当前用户已启用的 MFA 方法 */
export function getUserMfaMethods() {
  return requestClient.get<SystemUserProfileApi.MfaMethod[]>(
    '/system/user/profile/mfa/methods',
  );
}

/** 查询当前部署允许注册的 MFA 方法 */
export function getUserMfaEnrollmentMethods() {
  return requestClient.get<SystemUserProfileApi.MfaMethod[]>(
    '/system/user/profile/mfa/enrollment-methods',
  );
}

/** 查询当前用户可管理的 MFA 因子 */
export function getUserMfaFactors() {
  return requestClient.get<SystemUserProfileApi.MfaFactor[]>(
    '/system/user/profile/mfa/factors',
  );
}

/** 开始当前用户 TOTP 自助注册 */
export function startUserTotpEnrollment(password: string) {
  return requestClient.post<SystemUserProfileApi.MfaTotpSetupResult>(
    '/system/user/profile/mfa/totp/enroll/start',
    { password },
  );
}

/** 完成当前用户 TOTP 自助注册 */
export function finishUserTotpEnrollment(mfaToken: string, code: string) {
  return requestClient.post<SystemUserProfileApi.MfaRecoveryCodesResult>(
    '/system/user/profile/mfa/totp/enroll/finish',
    { code, mfaToken },
  );
}

/** 开始新增或轮换当前用户 TOTP 因子 */
export function startManagedUserTotpEnrollment() {
  return requestClient.post<SystemUserProfileApi.MfaTotpSetupResult>(
    '/system/user/profile/mfa/manage/totp/enroll/start',
  );
}

/** 完成新增或轮换当前用户 TOTP 因子 */
export function finishManagedUserTotpEnrollment(
  mfaToken: string,
  code: string,
) {
  return requestClient.post<SystemUserProfileApi.MfaRecoveryCodesResult>(
    '/system/user/profile/mfa/manage/totp/enroll/finish',
    { code, mfaToken },
  );
}

/** 移除当前用户 MFA 因子 */
export function removeUserMfaFactor(factorId: number) {
  return requestClient.delete<boolean>(
    `/system/user/profile/mfa/factors/${factorId}`,
  );
}

/** 重置当前用户 MFA 恢复码 */
export function resetUserMfaRecoveryCodes() {
  return requestClient.post<SystemUserProfileApi.MfaRecoveryCodesResult>(
    '/system/user/profile/mfa/recovery-codes/reset',
  );
}

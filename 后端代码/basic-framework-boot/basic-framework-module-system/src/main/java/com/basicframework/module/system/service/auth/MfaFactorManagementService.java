package com.basicframework.module.system.service.auth;

import com.basicframework.module.system.service.auth.dto.MfaFactorDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import java.util.List;

/** 已登录用户的 MFA 因子管理服务。调用方必须先执行近期二次验证。 */
public interface MfaFactorManagementService {

    /**
     * 查询当前用户的启用因子。
     *
     * @param userId 当前用户编号
     * @return 不含密钥材料的因子列表
     */
    List<MfaFactorDTO> getFactors(Long userId);

    /**
     * 开始新增或轮换 TOTP 因子。
     *
     * @param userId 当前用户编号
     * @param username 当前用户名
     * @return TOTP 设置材料
     */
    MfaTotpSetupDTO beginTotpEnrollment(Long userId, String username);

    /**
     * 完成新增或轮换 TOTP 因子，并同时轮换恢复码。
     *
     * @param userId 当前用户编号
     * @param enrollmentToken 一次性注册挑战
     * @param code 新 TOTP 密钥生成的验证码
     * @return 仅展示一次的新恢复码
     */
    List<String> completeTotpEnrollment(Long userId, String enrollmentToken, String code);

    /**
     * 移除当前用户的一个因子。
     *
     * @param userId 当前用户编号
     * @param factorId 因子编号
     */
    void removeFactor(Long userId, Long factorId);

    /**
     * 作废旧恢复码并生成新恢复码。
     *
     * @param userId 当前用户编号
     * @return 仅展示一次的新恢复码
     */
    List<String> resetRecoveryCodes(Long userId);
}

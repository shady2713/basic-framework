package com.basicframework.module.system.service.session;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;

/** 用户会话服务。 */
public interface UserSessionService {

    /**
     * 为用户创建会话。
     *
     * @param userId 用户编号
     * @param userType 用户类型
     * @return 含本次原始令牌的会话
     */
    UserSessionDO createSession(Long userId, Integer userType);

    /**
     * 使用一次性刷新令牌原子轮换会话令牌。
     *
     * @param refreshToken 原始刷新令牌
     * @return 含本次原始令牌的会话
     */
    UserSessionDO refreshSession(String refreshToken);

    /**
     * 按访问令牌查询会话，不校验是否过期。
     *
     * @param accessToken 原始访问令牌
     * @return 会话，不存在时返回 {@code null}
     */
    UserSessionDO getSessionByAccessToken(String accessToken);

    /**
     * 校验访问令牌并返回有效会话。
     *
     * @param accessToken 原始访问令牌
     * @return 有效会话
     */
    UserSessionDO checkAccessToken(String accessToken);

    /**
     * 按访问令牌撤销会话。
     *
     * @param accessToken 原始访问令牌
     * @return 被撤销的会话，不存在时返回 {@code null}
     */
    UserSessionDO removeSessionByAccessToken(String accessToken);

    /**
     * 按编号撤销会话。
     *
     * @param id 会话编号
     * @return 被撤销的会话，不存在时返回 {@code null}
     */
    UserSessionDO removeSessionById(Long id);

    /**
     * 按刷新令牌撤销会话。
     *
     * @param refreshToken 原始刷新令牌
     * @return 被撤销的会话，不存在时返回 {@code null}
     */
    UserSessionDO removeSessionByRefreshToken(String refreshToken);

    /**
     * 撤销用户的全部会话。
     *
     * @param userId 用户编号
     * @param userType 用户类型
     */
    void removeSessionsByUser(Long userId, Integer userType);

    /**
     * 查询仍处于绝对有效期内的会话。
     *
     * @param pageParam 分页参数
     * @param userId 用户编号筛选
     * @param userType 用户类型筛选
     * @return 会话分页
     */
    PageResult<UserSessionDO> getSessionPage(PageParam pageParam, Long userId, Integer userType);
}

package com.basicframework.module.system.api.session;

import com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO;

/** 用户会话校验契约。会话签发、刷新和撤销不跨模块暴露。 */
public interface UserSessionCommonApi {

    /**
     * 校验访问令牌。
     *
     * @param accessToken 访问令牌
     * @return 会话信息
     */
    UserSessionCheckRespDTO checkAccessToken(String accessToken);
}

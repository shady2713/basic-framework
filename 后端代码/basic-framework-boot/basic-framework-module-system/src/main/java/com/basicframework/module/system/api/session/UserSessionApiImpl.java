package com.basicframework.module.system.api.session;

import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.system.api.session.dto.UserSessionCheckRespDTO;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import com.basicframework.module.system.service.session.UserSessionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/** 用户会话校验契约实现。 */
@Service
public class UserSessionApiImpl implements UserSessionCommonApi {

    @Resource
    private UserSessionService userSessionService;

    @Override
    public UserSessionCheckRespDTO checkAccessToken(String accessToken) {
        UserSessionDO session = userSessionService.checkAccessToken(accessToken);
        return BeanUtils.toBean(session, UserSessionCheckRespDTO.class);
    }
}

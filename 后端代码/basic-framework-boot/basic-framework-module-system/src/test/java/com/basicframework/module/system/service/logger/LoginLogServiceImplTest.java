package com.basicframework.module.system.service.logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.system.dal.dataobject.logger.LoginLogDO;
import com.basicframework.module.system.dal.mysql.logger.LoginLogMapper;
import com.basicframework.module.system.service.logger.dto.LoginLogCreateReqDTO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link LoginLogServiceImpl} 单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
class LoginLogServiceImplTest {

    @InjectMocks
    private LoginLogServiceImpl loginLogService;

    @Mock
    private LoginLogMapper loginLogMapper;

    @Test
    void getLoginLog_delegatesToMapper() {
        LoginLogDO loginLog = new LoginLogDO();
        loginLog.setId(1L);
        when(loginLogMapper.selectById(1L)).thenReturn(loginLog);

        assertThat(loginLogService.getLoginLog(1L)).isSameAs(loginLog);
    }

    @Test
    void getLoginLogPage_delegatesToMapper() {
        PageResult<LoginLogDO> pageResult = new PageResult<>(List.of(new LoginLogDO()), 1L);
        when(loginLogMapper.selectPage(any(PageParam.class), any(), any(), any(), any()))
                .thenReturn(pageResult);

        assertThat(loginLogService.getLoginLogPage(new PageParam(), "127.0.0.1", "shady", null, true))
                .isSameAs(pageResult);
    }

    @Test
    void createLoginLog_mapsDtoFieldsAndInserts() {
        LoginLogCreateReqDTO reqDTO = new LoginLogCreateReqDTO();
        reqDTO.setLogType(0);
        reqDTO.setTraceId("trace-1");
        reqDTO.setUserId(100L);
        reqDTO.setUserType(1);
        reqDTO.setUsername("shady");
        reqDTO.setResult(1);
        reqDTO.setUserIp("127.0.0.1");
        reqDTO.setUserAgent("curl/8.0");

        loginLogService.createLoginLog(reqDTO);

        ArgumentCaptor<LoginLogDO> captor = ArgumentCaptor.forClass(LoginLogDO.class);
        verify(loginLogMapper).insert(captor.capture());
        LoginLogDO saved = captor.getValue();
        assertThat(saved.getLogType()).isZero();
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
        assertThat(saved.getUserId()).isEqualTo(100L);
        assertThat(saved.getUserType()).isEqualTo(1);
        assertThat(saved.getUsername()).isEqualTo("shady");
        assertThat(saved.getResult()).isEqualTo(1);
        assertThat(saved.getUserIp()).isEqualTo("127.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("curl/8.0");
    }
}

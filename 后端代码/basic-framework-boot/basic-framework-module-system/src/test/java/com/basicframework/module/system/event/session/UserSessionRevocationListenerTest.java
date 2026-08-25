package com.basicframework.module.system.event.session;

import static com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum.PASSWORD_CHANGED;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.module.system.service.session.UserSessionService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserSessionRevocationListenerTest {

    @InjectMocks
    private UserSessionRevocationListener listener;

    @Mock
    private UserSessionService userSessionService;

    @Test
    void onEvent_revokesEveryAffectedUser() {
        int userType = UserTypeEnum.ADMIN.getValue();

        listener.onEvent(new UserSessionRevocationEvent(Set.of(1L, 2L), userType, PASSWORD_CHANGED));

        verify(userSessionService).removeSessionsByUser(1L, userType);
        verify(userSessionService).removeSessionsByUser(2L, userType);
    }
}

package com.basicframework.module.system.dal.mysql.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminUserMapperTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), AdminUserMapper.class.getName()),
                AdminUserDO.class);
    }

    @Test
    void updatePasswordIfUnchanged_matchesUserAndExpectedHash() {
        AdminUserMapper mapper = mock(AdminUserMapper.class, CALLS_REAL_METHODS);
        doReturn(1).when(mapper).update(any(AdminUserDO.class), any(LambdaUpdateWrapper.class));

        int updated = mapper.updatePasswordIfUnchanged(7L, "expected-hash", "upgraded-hash");

        ArgumentCaptor<AdminUserDO> updateCaptor = ArgumentCaptor.forClass(AdminUserDO.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AdminUserDO>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(updateCaptor.capture(), wrapperCaptor.capture());
        assertThat(updated).isEqualTo(1);
        assertThat(updateCaptor.getValue().getPassword()).isEqualTo("upgraded-hash");
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("id", "password");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values()).contains(7L, "expected-hash");
    }
}

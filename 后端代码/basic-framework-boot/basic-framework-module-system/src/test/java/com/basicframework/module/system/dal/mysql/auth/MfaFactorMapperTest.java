package com.basicframework.module.system.dal.mysql.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MfaFactorMapperTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), MfaFactorMapper.class.getName()),
                MfaFactorDO.class);
    }

    @Test
    void enabledListsAlwaysBindUserAndEnabledState() {
        MfaFactorMapper mapper = mock(MfaFactorMapper.class, CALLS_REAL_METHODS);
        MfaFactorDO factor = new MfaFactorDO().setId(31L);
        doReturn(List.of(factor)).when(mapper).selectList(any(Wrapper.class));

        assertThat(mapper.selectEnabledByUserId(7L)).containsExactly(factor);

        ArgumentCaptor<Wrapper<MfaFactorDO>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<MfaFactorDO> wrapper = asLambdaWrapper(captor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("user_id", "enabled");
        assertThat(parameterValues(wrapper)).contains(7L, true);
    }

    @Test
    void lockedEnabledListAddsForUpdateWithoutDroppingIdentityFilters() {
        MfaFactorMapper mapper = mock(MfaFactorMapper.class, CALLS_REAL_METHODS);
        doReturn(List.of()).when(mapper).selectList(any(Wrapper.class));

        assertThat(mapper.selectEnabledByUserIdForUpdate(7L)).isEmpty();

        ArgumentCaptor<Wrapper<MfaFactorDO>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        LambdaQueryWrapper<MfaFactorDO> wrapper = asLambdaWrapper(captor.getValue());
        assertThat(parameterValues(wrapper)).contains(7L, true);
        assertThat(wrapper.getSqlSegment()).endsWith("FOR UPDATE");
    }

    @Test
    void userScopedSingleLookupsCannotCrossTheUserBoundary() {
        MfaFactorMapper mapper = mock(MfaFactorMapper.class, CALLS_REAL_METHODS);
        MfaFactorDO factor = new MfaFactorDO().setId(31L);
        doReturn(factor).when(mapper).selectOne(any(Wrapper.class));

        assertThat(mapper.selectEnabledByUserIdAndType(7L, 2)).isSameAs(factor);
        assertThat(mapper.selectEnabledByIdAndUserId(31L, 7L)).isSameAs(factor);

        ArgumentCaptor<Wrapper<MfaFactorDO>> captor = wrapperCaptor();
        verify(mapper, times(2)).selectOne(captor.capture());
        List<LambdaQueryWrapper<MfaFactorDO>> wrappers = captor.getAllValues().stream()
                .map(MfaFactorMapperTest::asLambdaWrapper)
                .toList();
        assertThat(wrappers.get(0).getSqlSegment()).contains("user_id", "factor_type", "enabled");
        assertThat(parameterValues(wrappers.get(0))).contains(7L, 2, true);
        assertThat(wrappers.get(1).getSqlSegment()).contains("id", "user_id", "enabled");
        assertThat(parameterValues(wrappers.get(1))).contains(31L, 7L, true);
    }

    @Test
    void deleteEnabledFactorBindsBothFactorAndUserIdentity() {
        MfaFactorMapper mapper = mock(MfaFactorMapper.class, CALLS_REAL_METHODS);
        doReturn(1).when(mapper).delete(any(Wrapper.class));

        assertThat(mapper.deleteEnabledByIdAndUserId(31L, 7L)).isEqualTo(1);

        ArgumentCaptor<Wrapper<MfaFactorDO>> captor = wrapperCaptor();
        verify(mapper).delete(captor.capture());
        LambdaQueryWrapper<MfaFactorDO> wrapper = asLambdaWrapper(captor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("id", "user_id", "enabled");
        assertThat(parameterValues(wrapper)).contains(31L, 7L, true);
    }

    @SuppressWarnings("unchecked")
    private static LambdaQueryWrapper<MfaFactorDO> asLambdaWrapper(Wrapper<MfaFactorDO> wrapper) {
        return (LambdaQueryWrapper<MfaFactorDO>) wrapper;
    }

    private static Collection<Object> parameterValues(LambdaQueryWrapper<MfaFactorDO> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Wrapper<MfaFactorDO>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }
}

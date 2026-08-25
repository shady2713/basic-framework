package com.basicframework.module.infra.service.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import com.baomidou.mybatisplus.annotation.DbType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatabaseTableServiceImplTest {

    @InjectMocks
    private DatabaseTableServiceImpl service;

    @Mock
    private DynamicDataSourceProperties properties;

    @Mock
    private DataSourceProperty primaryDataSource;

    @Test
    void getPrimaryDbType_returnsConfiguredType() {
        preparePrimaryDataSource("jdbc:mysql://localhost:3306/basic_framework");

        assertThat(service.getPrimaryDbType()).isEqualTo(DbType.MYSQL);
    }

    @Test
    void getPrimaryDbType_rejectsMissingPrimary() {
        when(properties.getPrimary()).thenReturn("master");
        when(properties.getDatasource()).thenReturn(Map.of());

        assertThatThrownBy(service::getPrimaryDbType)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("主数据源配置不存在");
    }

    @Test
    void getTable_rejectsBlankName() {
        assertThatThrownBy(() -> service.getTable(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("表名不能为空");
    }

    private void preparePrimaryDataSource(String url) {
        when(properties.getPrimary()).thenReturn("master");
        when(properties.getDatasource()).thenReturn(Map.of("master", primaryDataSource));
        when(primaryDataSource.getUrl()).thenReturn(url);
    }
}

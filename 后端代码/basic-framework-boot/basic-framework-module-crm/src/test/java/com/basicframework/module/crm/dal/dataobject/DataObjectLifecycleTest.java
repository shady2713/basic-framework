package com.basicframework.module.crm.dal.dataobject;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import com.basicframework.module.crm.dal.dataobject.crm.CustomerDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

class DataObjectLifecycleTest {

    @Test
    void customerTable_usesExplicitSoftDeleteBase() {
        assertThat(CustomerDO.class.getSuperclass()).isEqualTo(SoftDeletableDO.class);
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), CustomerDO.class.getName());
        assistant.setCurrentNamespace(CustomerDO.class.getName());
        assertThat(TableInfoHelper.initTableInfo(assistant, CustomerDO.class).isWithLogicDelete())
                .isTrue();
    }
}

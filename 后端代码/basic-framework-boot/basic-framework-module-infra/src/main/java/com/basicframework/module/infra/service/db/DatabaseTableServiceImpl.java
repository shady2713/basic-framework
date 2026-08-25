package com.basicframework.module.infra.service.db;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceProperties;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.generator.config.DataSourceConfig;
import com.baomidou.mybatisplus.generator.config.GlobalConfig;
import com.baomidou.mybatisplus.generator.config.StrategyConfig;
import com.baomidou.mybatisplus.generator.config.builder.ConfigBuilder;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.baomidou.mybatisplus.generator.config.rules.DateType;
import com.baomidou.mybatisplus.generator.query.SQLQuery;
import com.basicframework.framework.mybatis.core.util.JdbcUtils;
import jakarta.annotation.Resource;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

/**
 * 数据库表 Service 实现类
 *
 */
@Service
public class DatabaseTableServiceImpl implements DatabaseTableService {

    @Resource
    private DynamicDataSourceProperties dynamicDataSourceProperties;

    @Override
    public List<TableInfo> getTableList(String nameLike, String commentLike) {
        List<TableInfo> tables = getTableList0(null);
        return tables.stream()
                .filter(tableInfo ->
                        (StrUtil.isEmpty(nameLike) || tableInfo.getName().contains(nameLike))
                                && (StrUtil.isEmpty(commentLike)
                                        || tableInfo.getComment().contains(commentLike)))
                .collect(Collectors.toList());
    }

    @Override
    public TableInfo getTable(String name) {
        Assert.hasText(name, "表名不能为空");
        return CollUtil.getFirst(getTableList0(name));
    }

    @Override
    public DbType getPrimaryDbType() {
        return JdbcUtils.getDbType(getPrimaryDataSource().getUrl());
    }

    private List<TableInfo> getTableList0(String name) {
        DataSourceProperty config = getPrimaryDataSource();

        // 使用 MyBatis Plus Generator 解析表结构
        DataSourceConfig.Builder dataSourceConfigBuilder =
                new DataSourceConfig.Builder(config.getUrl(), config.getUsername(), config.getPassword());
        if (JdbcUtils.isSQLServer(config.getUrl())) {
            dataSourceConfigBuilder.databaseQueryClass(SQLQuery.class);
        }
        StrategyConfig.Builder strategyConfig = new StrategyConfig.Builder().enableSkipView(); // 忽略视图，业务上一般用不到
        if (StrUtil.isNotEmpty(name)) {
            strategyConfig.addInclude(Pattern.quote(name));
        } else {
            // 移除工作流和定时任务前缀的表名
            strategyConfig.addExclude(
                    "ACT_[\\S\\s]+|QRTZ_[\\S\\s]+|FLW_[\\S\\s]+|act_[\\S\\s]+|qrtz_[\\S\\s]+|flw_[\\S\\s]+");
            // 移除 ORACLE 相关的系统表
            strategyConfig.addExclude(
                    "IMPDP_[\\S\\s]+|ALL_[\\S\\s]+|HS_[\\S\\s]+|impdp_[\\S\\s]+|all_[\\S\\s]+|hs_[\\S\\s]+");
            strategyConfig.addExclude("[\\S\\s]+\\$[\\S\\s]+|[\\S\\s]+\\$"); // 表里不能有 $，一般有都是系统的表
        }

        GlobalConfig globalConfig =
                new GlobalConfig.Builder().dateType(DateType.TIME_PACK).build(); // 只使用 LocalDateTime 类型，不使用 LocalDate
        ConfigBuilder builder = new ConfigBuilder(
                null, dataSourceConfigBuilder.build(), strategyConfig.build(), null, globalConfig, null);
        List<TableInfo> tables = builder.getTableInfoList();
        tables.sort(Comparator.comparing(TableInfo::getName));
        return tables;
    }

    private DataSourceProperty getPrimaryDataSource() {
        String primary = dynamicDataSourceProperties.getPrimary();
        DataSourceProperty config = dynamicDataSourceProperties.getDatasource().get(primary);
        if (config == null) {
            throw new IllegalStateException("主数据源配置不存在");
        }
        return config;
    }
}

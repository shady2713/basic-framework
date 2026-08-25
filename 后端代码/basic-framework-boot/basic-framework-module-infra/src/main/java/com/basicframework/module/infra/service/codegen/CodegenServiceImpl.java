package com.basicframework.module.infra.service.codegen;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertMap;
import static com.basicframework.framework.common.util.collection.CollectionUtils.convertSet;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.*;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenColumnDO;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenTableDO;
import com.basicframework.module.infra.dal.mysql.codegen.CodegenColumnMapper;
import com.basicframework.module.infra.dal.mysql.codegen.CodegenTableMapper;
import com.basicframework.module.infra.enums.codegen.CodegenSceneEnum;
import com.basicframework.module.infra.enums.codegen.CodegenTemplateTypeEnum;
import com.basicframework.module.infra.framework.codegen.config.CodegenProperties;
import com.basicframework.module.infra.service.codegen.inner.CodegenBuilder;
import com.basicframework.module.infra.service.codegen.inner.CodegenEngine;
import com.basicframework.module.infra.service.db.DatabaseTableService;
import com.basicframework.module.system.api.permission.MenuReferenceCommonApi;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 代码生成 Service 实现类
 *
 */
@Service
public class CodegenServiceImpl implements CodegenService {

    @Resource
    private DatabaseTableService databaseTableService;

    @Resource
    private CodegenTableMapper codegenTableMapper;

    @Resource
    private CodegenColumnMapper codegenColumnMapper;

    @Resource
    private CodegenBuilder codegenBuilder;

    @Resource
    private CodegenEngine codegenEngine;

    @Resource
    private CodegenProperties codegenProperties;

    @Resource
    private MenuReferenceCommonApi menuReferenceApi;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> createCodegenList(String author, List<String> tableNames) {
        List<Long> ids = new ArrayList<>(tableNames.size());
        tableNames.forEach(tableName -> ids.add(createCodegen(author, tableName)));
        return ids;
    }

    private Long createCodegen(String author, String tableName) {
        // 从数据库中，获得数据库表结构
        TableInfo tableInfo = databaseTableService.getTable(tableName);
        // 导入
        return createCodegen0(author, tableInfo);
    }

    private Long createCodegen0(String author, TableInfo tableInfo) {
        // 校验导入的表和字段非空
        validateTableInfo(tableInfo);
        // 校验是否已经存在
        if (codegenTableMapper.selectByTableName(tableInfo.getName()) != null) {
            throw exception(CODEGEN_TABLE_EXISTS);
        }

        // 构建 CodegenTableDO 对象，插入到 DB 中
        CodegenTableDO table = codegenBuilder.buildTable(tableInfo);
        table.setScene(CodegenSceneEnum.ADMIN.getScene()); // 默认配置下，使用管理后台的模板
        table.setFrontType(codegenProperties.getFrontType());
        table.setAuthor(author);
        codegenTableMapper.insert(table);

        // 构建 CodegenColumnDO 数组，插入到 DB 中
        List<CodegenColumnDO> columns = codegenBuilder.buildColumns(table.getId(), tableInfo.getFields());
        // 如果没有主键，则使用第一个字段作为主键
        if (!tableInfo.isHavePrimaryKey()) {
            columns.get(0).setPrimaryKey(true);
        }
        codegenColumnMapper.insertBatch(columns);
        return table.getId();
    }

    @VisibleForTesting
    void validateTableInfo(TableInfo tableInfo) {
        if (tableInfo == null) {
            throw exception(CODEGEN_IMPORT_TABLE_NULL);
        }
        if (StrUtil.isEmpty(tableInfo.getComment())) {
            throw exception(CODEGEN_TABLE_INFO_TABLE_COMMENT_IS_NULL);
        }
        if (CollUtil.isEmpty(tableInfo.getFields())) {
            throw exception(CODEGEN_IMPORT_COLUMNS_NULL);
        }
        tableInfo.getFields().forEach(field -> {
            if (StrUtil.isEmpty(field.getComment())) {
                throw exception(CODEGEN_TABLE_INFO_COLUMN_COMMENT_IS_NULL, field.getName());
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCodegen(CodegenTableDO table, List<CodegenColumnDO> columns) {
        normalizeTableReferences(table);
        CodegenTableDO masterTable = lockCodegenUpdateTargets(table);
        Set<Long> ownedColumnIds = validateOwnedColumns(table.getId(), columns);
        validateTableReferences(table, masterTable, ownedColumnIds);

        // 更新 table 表定义
        codegenTableMapper.updateById(table);
        // 更新 column 字段定义
        columns.forEach(column -> codegenColumnMapper.updateById(column));
    }

    private Set<Long> validateOwnedColumns(Long tableId, List<CodegenColumnDO> columns) {
        Set<Long> ownedColumnIds = convertSet(codegenColumnMapper.selectListByTableId(tableId), CodegenColumnDO::getId);
        Set<Long> submittedColumnIds = new HashSet<>();
        for (CodegenColumnDO column : columns) {
            if (!Objects.equals(tableId, column.getTableId())
                    || column.getId() == null
                    || !ownedColumnIds.contains(column.getId())
                    || !submittedColumnIds.add(column.getId())) {
                throw exception(CODEGEN_COLUMN_NOT_BELONG_TABLE, column.getId(), tableId);
            }
        }
        return ownedColumnIds;
    }

    private void validateOwnedReference(Long tableId, Long columnId, Set<Long> ownedColumnIds) {
        if (!ownedColumnIds.contains(columnId)) {
            throw exception(CODEGEN_COLUMN_NOT_BELONG_TABLE, columnId, tableId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncCodegenFromDB(Long tableId) {
        // 校验是否已经存在
        CodegenTableDO table = validateAndLockCodegenTable(tableId);
        // 从数据库中，获得数据库表结构
        TableInfo tableInfo = databaseTableService.getTable(table.getTableName());
        // 执行同步
        syncCodegen0(tableId, tableInfo);
    }

    private void syncCodegen0(Long tableId, TableInfo tableInfo) {
        // 1. 校验导入的表和字段非空
        validateTableInfo(tableInfo);
        List<TableField> tableFields = tableInfo.getFields();

        // 2. 构建 CodegenColumnDO 数组，只同步新增的字段
        List<CodegenColumnDO> codegenColumns = codegenColumnMapper.selectListByTableId(tableId);
        Set<String> codegenColumnNames = convertSet(codegenColumns, CodegenColumnDO::getColumnName);

        // 3.1 计算需要【修改】的字段，插入时重新插入，删除时将原来的删除
        Map<String, CodegenColumnDO> codegenColumnDOMap = convertMap(codegenColumns, CodegenColumnDO::getColumnName);
        BiPredicate<TableField, CodegenColumnDO> primaryKeyPredicate = (tableField, codegenColumn) ->
                tableField.getMetaInfo().getJdbcType().name().equals(codegenColumn.getDataType())
                        && tableField.getMetaInfo().isNullable() == codegenColumn.getNullable()
                        && tableField.isKeyFlag() == codegenColumn.getPrimaryKey()
                        && tableField.getComment().equals(codegenColumn.getColumnComment());
        Set<String> modifyFieldNames = IntStream.range(0, tableFields.size())
                .mapToObj(index -> {
                    TableField tableField = tableFields.get(index);
                    String columnName = tableField.getColumnName();
                    CodegenColumnDO codegenColumn = codegenColumnDOMap.get(columnName);
                    if (codegenColumn == null) {
                        return null;
                    }
                    if (!primaryKeyPredicate.test(tableField, codegenColumn)
                            || codegenColumn.getOrdinalPosition() != index) {
                        return columnName;
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 3.2 计算需要【删除】的字段
        Set<String> tableFieldNames = convertSet(tableFields, TableField::getName);
        Set<Long> deleteColumnIds = codegenColumns.stream()
                .filter(column -> (!tableFieldNames.contains(column.getColumnName()))
                        || modifyFieldNames.contains(column.getColumnName()))
                .map(CodegenColumnDO::getId)
                .collect(Collectors.toSet());
        // 移除已经存在的字段
        tableFields.removeIf(column -> codegenColumnNames.contains(column.getColumnName())
                && (!modifyFieldNames.contains(column.getColumnName())));
        if (CollUtil.isEmpty(tableFields) && CollUtil.isEmpty(deleteColumnIds)) {
            throw exception(CODEGEN_SYNC_NONE_CHANGE);
        }

        // 4.1 插入新增的字段
        List<CodegenColumnDO> columns = codegenBuilder.buildColumns(tableId, tableFields);
        validateColumnsNotInUse(deleteColumnIds);
        codegenColumnMapper.insertBatch(columns);
        // 4.2 删除不存在的字段
        if (CollUtil.isNotEmpty(deleteColumnIds)) {
            codegenColumnMapper.deleteByIds(deleteColumnIds);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCodegen(Long tableId) {
        validateAndLockCodegenTable(tableId);
        validateCodegenTableNotReferenced(tableId);

        // 删除 table 表定义
        codegenTableMapper.deleteById(tableId);
        // 删除 column 字段定义
        codegenColumnMapper.deleteListByTableId(tableId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCodegenList(List<Long> tableIds) {
        if (CollUtil.isEmpty(tableIds)) {
            return;
        }
        if (tableIds.stream().anyMatch(Objects::isNull)) {
            throw exception(CODEGEN_TABLE_NOT_EXISTS);
        }
        List<Long> lockedTableIds = tableIds.stream().distinct().sorted().toList();
        lockedTableIds.forEach(this::validateAndLockCodegenTable);
        lockedTableIds.forEach(this::validateCodegenTableNotReferenced);
        // 批量删除 table 表定义
        codegenTableMapper.deleteByIds(lockedTableIds);
        // 批量删除 column 字段定义
        codegenColumnMapper.deleteListByTableId(lockedTableIds);
    }

    private CodegenTableDO validateAndLockCodegenTable(Long tableId) {
        if (tableId == null) {
            throw exception(CODEGEN_TABLE_NOT_EXISTS);
        }
        CodegenTableDO table = codegenTableMapper.selectByIdForUpdate(tableId);
        if (table == null) {
            throw exception(CODEGEN_TABLE_NOT_EXISTS);
        }
        return table;
    }

    private void normalizeTableReferences(CodegenTableDO table) {
        Integer scene = table.getScene();
        if (!CodegenSceneEnum.isSupported(scene)) {
            throw exception(CODEGEN_SCENE_INVALID, scene);
        }
        if (!CodegenSceneEnum.isAdmin(scene)) {
            table.setParentMenuId(null);
        }
        Integer templateType = table.getTemplateType();
        if (!CodegenTemplateTypeEnum.isSupported(templateType)) {
            throw exception(CODEGEN_TEMPLATE_TYPE_INVALID, templateType);
        }
        if (!CodegenTemplateTypeEnum.isSub(templateType)) {
            table.setMasterTableId(null).setSubJoinColumnId(null).setSubJoinMany(null);
        }
        if (!CodegenTemplateTypeEnum.isTree(templateType)) {
            table.setTreeParentColumnId(null).setTreeNameColumnId(null);
        }
    }

    private CodegenTableDO lockCodegenUpdateTargets(CodegenTableDO table) {
        Long tableId = table.getId();
        if (tableId == null) {
            throw exception(CODEGEN_TABLE_NOT_EXISTS);
        }
        Long masterTableId = CodegenTemplateTypeEnum.isSub(table.getTemplateType()) ? table.getMasterTableId() : null;
        List<Long> lockIds = Stream.of(tableId, masterTableId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        CodegenTableDO currentTable = null;
        CodegenTableDO masterTable = null;
        for (Long lockId : lockIds) {
            if (Objects.equals(lockId, tableId)) {
                currentTable = codegenTableMapper.selectByIdForUpdate(lockId);
            } else {
                masterTable = codegenTableMapper.selectByIdForShare(lockId);
            }
        }
        if (currentTable == null) {
            throw exception(CODEGEN_TABLE_NOT_EXISTS);
        }
        return masterTable;
    }

    private void validateTableReferences(CodegenTableDO table, CodegenTableDO masterTable, Set<Long> ownedColumnIds) {
        validateAndLockParentMenuReference(table);
        Integer templateType = table.getTemplateType();
        if (!CodegenTemplateTypeEnum.isMaster(templateType)) {
            validateCodegenTableNotReferenced(table.getId());
        }
        if (CodegenTemplateTypeEnum.isSub(templateType)) {
            validateSubTableReferences(table, masterTable, ownedColumnIds);
        } else if (CodegenTemplateTypeEnum.isTree(templateType)) {
            validateTreeTableReferences(table, ownedColumnIds);
        }
    }

    private void validateSubTableReferences(
            CodegenTableDO table, CodegenTableDO masterTable, Set<Long> ownedColumnIds) {
        if (table.getSubJoinMany() == null) {
            throw exception(CODEGEN_SUB_CONFIGURATION_INVALID);
        }
        if (Objects.equals(table.getId(), table.getMasterTableId())) {
            throw exception(CODEGEN_MASTER_TABLE_INVALID, table.getMasterTableId());
        }
        if (masterTable == null || !CodegenTemplateTypeEnum.isMaster(masterTable.getTemplateType())) {
            throw exception(CODEGEN_MASTER_TABLE_INVALID, table.getMasterTableId());
        }
        if (!ownedColumnIds.contains(table.getSubJoinColumnId())) {
            throw exception(CODEGEN_SUB_COLUMN_NOT_EXISTS, table.getSubJoinColumnId());
        }
    }

    private void validateTreeTableReferences(CodegenTableDO table, Set<Long> ownedColumnIds) {
        validateOwnedReference(table.getId(), table.getTreeParentColumnId(), ownedColumnIds);
        validateOwnedReference(table.getId(), table.getTreeNameColumnId(), ownedColumnIds);
        if (Objects.equals(table.getTreeParentColumnId(), table.getTreeNameColumnId())) {
            throw exception(CODEGEN_TREE_COLUMNS_DUPLICATE);
        }
    }

    private void validateAndLockParentMenuReference(CodegenTableDO table) {
        if (CodegenSceneEnum.isAdmin(table.getScene())
                && !menuReferenceApi.lockParentMenuIfAvailable(table.getParentMenuId())) {
            throw exception(CODEGEN_PARENT_MENU_INVALID, table.getParentMenuId());
        }
    }

    private void validateCodegenTableNotReferenced(Long tableId) {
        if (codegenTableMapper.selectCountByMasterTableId(tableId) > 0) {
            throw exception(CODEGEN_TABLE_HAS_SUB_TABLES, tableId);
        }
    }

    @VisibleForTesting
    void validateColumnsNotInUse(Collection<Long> columnIds) {
        for (Long columnId : columnIds) {
            if (codegenTableMapper.selectCountByReferenceColumnId(columnId) > 0) {
                throw exception(CODEGEN_COLUMN_IN_USE, columnId);
            }
        }
    }

    @Override
    public List<CodegenTableDO> getCodegenTableList() {
        return codegenTableMapper.selectList();
    }

    @Override
    public PageResult<CodegenTableDO> getCodegenTablePage(
            PageParam pageParam, String tableName, String tableComment, String className, LocalDateTime[] createTime) {
        return codegenTableMapper.selectPage(pageParam, tableName, tableComment, className, createTime);
    }

    @Override
    public CodegenTableDO getCodegenTable(Long id) {
        return codegenTableMapper.selectById(id);
    }

    @Override
    public List<CodegenColumnDO> getCodegenColumnListByTableId(Long tableId) {
        return codegenColumnMapper.selectListByTableId(tableId);
    }

    @Override
    public Map<String, String> generationCodes(Long tableId) {
        // 校验是否已经存在
        CodegenTableDO table = codegenTableMapper.selectById(tableId);
        if (table == null) {
            throw exception(CODEGEN_TABLE_NOT_EXISTS);
        }
        validateParentMenuReferenceForGeneration(table);
        List<CodegenColumnDO> columns = codegenColumnMapper.selectListByTableId(tableId);
        if (CollUtil.isEmpty(columns)) {
            throw exception(CODEGEN_COLUMN_NOT_EXISTS);
        }

        // 如果是主子表，则加载对应的子表信息
        List<CodegenTableDO> subTables = null;
        List<List<CodegenColumnDO>> subColumnsList = null;
        if (CodegenTemplateTypeEnum.isMaster(table.getTemplateType())) {
            // 校验子表存在
            subTables = codegenTableMapper.selectListByTemplateTypeAndMasterTableId(
                    CodegenTemplateTypeEnum.SUB.getType(), tableId);
            if (CollUtil.isEmpty(subTables)) {
                throw exception(CODEGEN_MASTER_GENERATION_FAIL_NO_SUB_TABLE);
            }
            // 校验子表的关联字段存在
            subColumnsList = new ArrayList<>();
            for (CodegenTableDO subTable : subTables) {
                List<CodegenColumnDO> subColumns = codegenColumnMapper.selectListByTableId(subTable.getId());
                if (CollUtil.findOne(subColumns, column -> column.getId().equals(subTable.getSubJoinColumnId()))
                        == null) {
                    throw exception(CODEGEN_SUB_COLUMN_NOT_EXISTS, subTable.getId());
                }
                subColumnsList.add(subColumns);
            }
        }

        DbType dbType = databaseTableService.getPrimaryDbType();
        // 执行生成
        return codegenEngine.execute(dbType, table, columns, subTables, subColumnsList);
    }

    private void validateParentMenuReferenceForGeneration(CodegenTableDO table) {
        if (!CodegenSceneEnum.isSupported(table.getScene())) {
            throw exception(CODEGEN_SCENE_INVALID, table.getScene());
        }
        if (CodegenSceneEnum.isAdmin(table.getScene())
                && !menuReferenceApi.isParentMenuAvailable(table.getParentMenuId())) {
            throw exception(CODEGEN_PARENT_MENU_INVALID, table.getParentMenuId());
        }
    }

    @Override
    public List<TableInfo> getDatabaseTableList(String name, String comment) {
        List<TableInfo> tables = databaseTableService.getTableList(name, comment);
        // 移除在 Codegen 中，已经存在的
        Set<String> existsTables = convertSet(codegenTableMapper.selectList(), CodegenTableDO::getTableName);
        tables.removeIf(table -> existsTables.contains(table.getName()));
        return tables;
    }
}

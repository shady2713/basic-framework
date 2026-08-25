package com.basicframework.module.infra.service.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenColumnDO;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenTableDO;
import com.basicframework.module.infra.dal.mysql.codegen.CodegenColumnMapper;
import com.basicframework.module.infra.dal.mysql.codegen.CodegenTableMapper;
import com.basicframework.module.infra.enums.ErrorCodeConstants;
import com.basicframework.module.infra.enums.codegen.CodegenSceneEnum;
import com.basicframework.module.infra.enums.codegen.CodegenTemplateTypeEnum;
import com.basicframework.module.infra.service.db.DatabaseTableService;
import com.basicframework.module.system.api.permission.MenuReferenceCommonApi;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CodegenServiceImplTest {

    private static final long TABLE_ID = 10L;

    @InjectMocks
    private CodegenServiceImpl service;

    @Mock
    private CodegenTableMapper tableMapper;

    @Mock
    private CodegenColumnMapper columnMapper;

    @Mock
    private DatabaseTableService databaseTableService;

    @Mock
    private MenuReferenceCommonApi menuReferenceApi;

    @Test
    void getCodegenTableList_returnsAllDefinitions() {
        List<CodegenTableDO> tables = List.of(new CodegenTableDO().setId(TABLE_ID));
        when(tableMapper.selectList()).thenReturn(tables);

        assertThat(service.getCodegenTableList()).isSameAs(tables);
    }

    @Test
    void getDatabaseTableList_excludesImportedTables() {
        TableInfo imported = mock(TableInfo.class);
        TableInfo available = mock(TableInfo.class);
        when(imported.getName()).thenReturn("system_user");
        when(available.getName()).thenReturn("system_role");
        when(databaseTableService.getTableList(null, null)).thenReturn(new ArrayList<>(List.of(imported, available)));
        when(tableMapper.selectList()).thenReturn(List.of(new CodegenTableDO().setTableName("system_user")));

        assertThat(service.getDatabaseTableList(null, null)).containsExactly(available);
    }

    @Test
    void queryMethods_delegateToMappers() {
        PageParam pageParam = new PageParam();
        PageResult<CodegenTableDO> page = new PageResult<>(List.of(), 0L);
        CodegenTableDO table = new CodegenTableDO().setId(TABLE_ID);
        List<CodegenColumnDO> columns = List.of(column(1L, TABLE_ID));
        when(tableMapper.selectPage(pageParam, null, null, null, null)).thenReturn(page);
        when(tableMapper.selectById(TABLE_ID)).thenReturn(table);
        when(columnMapper.selectListByTableId(TABLE_ID)).thenReturn(columns);

        assertThat(service.getCodegenTablePage(pageParam, null, null, null, null))
                .isSameAs(page);
        assertThat(service.getCodegenTable(TABLE_ID)).isSameAs(table);
        assertThat(service.getCodegenColumnListByTableId(TABLE_ID)).isSameAs(columns);
    }

    @Test
    void generationCodes_rejectsMissingTable() {
        assertErrorCode(() -> service.generationCodes(TABLE_ID), ErrorCodeConstants.CODEGEN_TABLE_NOT_EXISTS.getCode());
    }

    @Test
    void updateCodegen_updatesOnlyColumnsOwnedByCurrentTable() {
        CodegenTableDO table =
                table(CodegenTemplateTypeEnum.TREE).setTreeParentColumnId(1L).setTreeNameColumnId(2L);
        List<CodegenColumnDO> columns = List.of(column(1L, TABLE_ID), column(2L, TABLE_ID));
        prepareOwnedColumns(1L, 2L);

        service.updateCodegen(table, columns);

        verify(tableMapper).updateById(table);
        verify(columnMapper).updateById(columns.get(0));
        verify(columnMapper).updateById(columns.get(1));
    }

    @Test
    void updateCodegen_rejectsColumnOwnedByAnotherTable() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.ONE);
        prepareOwnedColumns(1L);

        assertOwnershipError(() -> service.updateCodegen(table, List.of(column(99L, TABLE_ID))));

        verify(tableMapper, never()).updateById(any(CodegenTableDO.class));
        verify(columnMapper, never()).updateById(any(CodegenColumnDO.class));
    }

    @Test
    void updateCodegen_rejectsSpoofedColumnTableId() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.ONE);
        prepareOwnedColumns(1L);

        assertOwnershipError(() -> service.updateCodegen(table, List.of(column(1L, 999L))));
    }

    @Test
    void updateCodegen_rejectsForeignTreeReference() {
        CodegenTableDO table =
                table(CodegenTemplateTypeEnum.TREE).setTreeParentColumnId(1L).setTreeNameColumnId(99L);
        prepareOwnedColumns(1L, 2L);

        assertOwnershipError(() -> service.updateCodegen(table, List.of(column(1L, TABLE_ID), column(2L, TABLE_ID))));
    }

    @Test
    void updateCodegen_rejectsDuplicateColumnIds() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.ONE);
        prepareOwnedColumns(1L);

        assertOwnershipError(() -> service.updateCodegen(table, List.of(column(1L, TABLE_ID), column(1L, TABLE_ID))));
    }

    @Test
    void updateCodegen_acceptsSubTableWithActiveMasterAndOwnedJoinColumn() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.SUB)
                .setMasterTableId(20L)
                .setSubJoinColumnId(1L)
                .setSubJoinMany(true);
        prepareOwnedColumns(1L);
        when(tableMapper.selectByIdForShare(20L))
                .thenReturn(new CodegenTableDO()
                        .setId(20L)
                        .setTemplateType(CodegenTemplateTypeEnum.MASTER_NORMAL.getType()));

        service.updateCodegen(table, List.of(column(1L, TABLE_ID)));

        verify(tableMapper).selectByIdForShare(20L);
        verify(tableMapper).updateById(table);
    }

    @Test
    void updateCodegen_locksMasterAndSubTableInStableIdOrder() {
        CodegenTableDO table = new CodegenTableDO()
                .setId(30L)
                .setScene(CodegenSceneEnum.APP.getScene())
                .setTemplateType(CodegenTemplateTypeEnum.SUB.getType())
                .setMasterTableId(20L)
                .setSubJoinColumnId(1L)
                .setSubJoinMany(true);
        when(tableMapper.selectByIdForShare(20L))
                .thenReturn(new CodegenTableDO()
                        .setId(20L)
                        .setTemplateType(CodegenTemplateTypeEnum.MASTER_NORMAL.getType()));
        when(tableMapper.selectByIdForUpdate(30L)).thenReturn(new CodegenTableDO().setId(30L));
        when(columnMapper.selectListByTableId(30L)).thenReturn(List.of(column(1L, 30L)));

        service.updateCodegen(table, List.of(column(1L, 30L)));

        InOrder inOrder = org.mockito.Mockito.inOrder(tableMapper);
        inOrder.verify(tableMapper).selectByIdForShare(20L);
        inOrder.verify(tableMapper).selectByIdForUpdate(30L);
    }

    @Test
    void updateCodegen_rejectsSubTableWhoseMasterIsNotMasterTemplate() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.SUB)
                .setMasterTableId(20L)
                .setSubJoinColumnId(1L)
                .setSubJoinMany(true);
        prepareOwnedColumns(1L);
        when(tableMapper.selectByIdForShare(20L))
                .thenReturn(new CodegenTableDO().setId(20L).setTemplateType(CodegenTemplateTypeEnum.ONE.getType()));

        assertErrorCode(
                () -> service.updateCodegen(table, List.of(column(1L, TABLE_ID))),
                ErrorCodeConstants.CODEGEN_MASTER_TABLE_INVALID.getCode());
    }

    @Test
    void updateCodegen_rejectsUnknownTemplateType() {
        CodegenTableDO table = new CodegenTableDO()
                .setId(TABLE_ID)
                .setScene(CodegenSceneEnum.APP.getScene())
                .setTemplateType(99);

        assertErrorCode(
                () -> service.updateCodegen(table, List.of(column(1L, TABLE_ID))),
                ErrorCodeConstants.CODEGEN_TEMPLATE_TYPE_INVALID.getCode());
    }

    @Test
    void updateCodegen_rejectsUnknownScene() {
        CodegenTableDO table = new CodegenTableDO()
                .setId(TABLE_ID)
                .setScene(99)
                .setTemplateType(CodegenTemplateTypeEnum.ONE.getType());

        assertErrorCode(
                () -> service.updateCodegen(table, List.of(column(1L, TABLE_ID))),
                ErrorCodeConstants.CODEGEN_SCENE_INVALID.getCode());
    }

    @Test
    void updateCodegen_locksValidAdminParentMenu() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.ONE)
                .setScene(CodegenSceneEnum.ADMIN.getScene())
                .setParentMenuId(100L);
        prepareOwnedColumns(1L);
        when(menuReferenceApi.lockParentMenuIfAvailable(100L)).thenReturn(true);

        service.updateCodegen(table, List.of(column(1L, TABLE_ID)));

        verify(menuReferenceApi).lockParentMenuIfAvailable(100L);
        verify(tableMapper).updateById(table);
    }

    @Test
    void updateCodegen_rejectsInvalidAdminParentMenu() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.ONE)
                .setScene(CodegenSceneEnum.ADMIN.getScene())
                .setParentMenuId(100L);
        prepareOwnedColumns(1L);

        assertErrorCode(
                () -> service.updateCodegen(table, List.of(column(1L, TABLE_ID))),
                ErrorCodeConstants.CODEGEN_PARENT_MENU_INVALID.getCode());
    }

    @Test
    void updateCodegen_clearsHiddenParentMenuForAppScene() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.ONE).setParentMenuId(100L);
        prepareOwnedColumns(1L);

        service.updateCodegen(table, List.of(column(1L, TABLE_ID)));

        verify(menuReferenceApi, never()).lockParentMenuIfAvailable(any());
        verify(tableMapper).updateById(table);
        org.assertj.core.api.Assertions.assertThat(table.getParentMenuId()).isNull();
    }

    @Test
    void updateCodegen_rejectsChangingReferencedMasterToNonMasterTemplate() {
        CodegenTableDO table = table(CodegenTemplateTypeEnum.ONE);
        prepareOwnedColumns(1L);
        when(tableMapper.selectCountByMasterTableId(TABLE_ID)).thenReturn(1L);

        assertErrorCode(
                () -> service.updateCodegen(table, List.of(column(1L, TABLE_ID))),
                ErrorCodeConstants.CODEGEN_TABLE_HAS_SUB_TABLES.getCode());
    }

    @Test
    void updateCodegen_rejectsDuplicateTreeReferenceColumns() {
        CodegenTableDO table =
                table(CodegenTemplateTypeEnum.TREE).setTreeParentColumnId(1L).setTreeNameColumnId(1L);
        prepareOwnedColumns(1L);

        assertErrorCode(
                () -> service.updateCodegen(table, List.of(column(1L, TABLE_ID))),
                ErrorCodeConstants.CODEGEN_TREE_COLUMNS_DUPLICATE.getCode());
    }

    @Test
    void deleteCodegen_rejectsTableReferencedBySubTable() {
        when(tableMapper.selectByIdForUpdate(TABLE_ID)).thenReturn(table(CodegenTemplateTypeEnum.MASTER_NORMAL));
        when(tableMapper.selectCountByMasterTableId(TABLE_ID)).thenReturn(1L);

        assertErrorCode(
                () -> service.deleteCodegen(TABLE_ID), ErrorCodeConstants.CODEGEN_TABLE_HAS_SUB_TABLES.getCode());

        verify(tableMapper, never()).deleteById(TABLE_ID);
    }

    @Test
    void validateColumnsNotInUse_rejectsReferencedColumn() {
        when(tableMapper.selectCountByReferenceColumnId(1L)).thenReturn(0L);
        when(tableMapper.selectCountByReferenceColumnId(2L)).thenReturn(1L);

        assertErrorCode(
                () -> service.validateColumnsNotInUse(List.of(1L, 2L)),
                ErrorCodeConstants.CODEGEN_COLUMN_IN_USE.getCode());
    }

    private void prepareOwnedColumns(Long... ids) {
        when(tableMapper.selectByIdForUpdate(TABLE_ID)).thenReturn(new CodegenTableDO().setId(TABLE_ID));
        when(columnMapper.selectListByTableId(TABLE_ID))
                .thenReturn(
                        List.of(ids).stream().map(id -> column(id, TABLE_ID)).toList());
    }

    private static CodegenTableDO table(CodegenTemplateTypeEnum type) {
        return new CodegenTableDO()
                .setId(TABLE_ID)
                .setScene(CodegenSceneEnum.APP.getScene())
                .setTemplateType(type.getType());
    }

    private static CodegenColumnDO column(Long id, Long tableId) {
        return new CodegenColumnDO().setId(id).setTableId(tableId);
    }

    private static void assertOwnershipError(Runnable action) {
        assertErrorCode(action, ErrorCodeConstants.CODEGEN_COLUMN_NOT_BELONG_TABLE.getCode());
    }

    private static void assertErrorCode(Runnable action, int code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(code);
    }
}

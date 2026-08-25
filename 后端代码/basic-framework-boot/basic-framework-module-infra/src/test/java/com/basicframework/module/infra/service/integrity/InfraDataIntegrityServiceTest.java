package com.basicframework.module.infra.service.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.dal.mysql.codegen.CodegenTableMapper;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.system.api.permission.MenuReferenceCommonApi;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InfraDataIntegrityServiceTest {

    @InjectMocks
    private InfraDataIntegrityService service;

    @Mock
    private CodegenTableMapper codegenTableMapper;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileContentMapper fileContentMapper;

    @Mock
    private MenuReferenceCommonApi menuReferenceApi;

    @Test
    void verifyLogicalReferences_returnsStableSummaryWhenClean() {
        assertThat(service.verifyLogicalReferences()).isEqualTo("infra 逻辑引用完整性检查通过");
    }

    @Test
    void verifyLogicalReferences_withOrphanFile_failsWithRelationAndCount() {
        when(fileMapper.selectOrphanFileConfigCount()).thenReturn(3);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_file.config_id 孤儿引用 3 条");
    }

    @Test
    void verifyLogicalReferences_withInvalidMasterTable_failsWithRelationAndCount() {
        when(codegenTableMapper.selectInvalidMasterTableReferenceCount()).thenReturn(2);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_codegen_table.master_table_id 无效引用 2 条");
    }

    @Test
    void verifyLogicalReferences_withInvalidColumn_failsWithRelationAndCount() {
        when(codegenTableMapper.selectInvalidColumnReferenceCount()).thenReturn(3);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_codegen_table.column_id 无效引用 3 条");
    }

    @Test
    void verifyLogicalReferences_withInvalidParentMenu_failsWithRelationAndCount() {
        List<Long> parentMenuIds = List.of(10L, 20L, 20L);
        when(codegenTableMapper.selectParentMenuIdsForIntegrityAudit()).thenReturn(parentMenuIds);
        when(menuReferenceApi.findUnavailableParentMenuIds(parentMenuIds)).thenReturn(Set.of(20L));

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_codegen_table.parent_menu_id 无效引用 2 条");
    }

    @Test
    void verifyLogicalReferences_withOrphanFileContent_failsWithRelationAndCount() {
        when(fileContentMapper.selectOrphanFileConfigCount()).thenReturn(4);

        assertThatThrownBy(service::verifyLogicalReferences)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("infra_file_content.config_id 孤儿引用 4 条");
    }
}

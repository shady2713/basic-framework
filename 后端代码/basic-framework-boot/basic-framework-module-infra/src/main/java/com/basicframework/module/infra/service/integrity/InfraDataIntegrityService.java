package com.basicframework.module.infra.service.integrity;

import com.basicframework.module.infra.dal.mysql.codegen.CodegenTableMapper;
import com.basicframework.module.infra.dal.mysql.file.FileContentMapper;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.system.api.permission.MenuReferenceCommonApi;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 检查 infra 模块无法由物理外键表达的逻辑引用完整性。 */
@Service
public class InfraDataIntegrityService {

    @Resource
    private CodegenTableMapper codegenTableMapper;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private FileContentMapper fileContentMapper;

    @Resource
    private MenuReferenceCommonApi menuReferenceApi;

    /**
     * 检查已纳管的逻辑引用；发现孤儿时失败，由 Quartz 记录异常并触发任务告警。
     *
     * @return 稳定的审计摘要
     */
    public String verifyLogicalReferences() {
        int invalidMasterTables = codegenTableMapper.selectInvalidMasterTableReferenceCount();
        if (invalidMasterTables > 0) {
            throw new IllegalStateException("infra_codegen_table.master_table_id 无效引用 " + invalidMasterTables + " 条");
        }
        int invalidColumns = codegenTableMapper.selectInvalidColumnReferenceCount();
        if (invalidColumns > 0) {
            throw new IllegalStateException("infra_codegen_table.column_id 无效引用 " + invalidColumns + " 条");
        }
        List<Long> parentMenuIds = codegenTableMapper.selectParentMenuIdsForIntegrityAudit();
        Set<Long> unavailableParentMenuIds = menuReferenceApi.findUnavailableParentMenuIds(parentMenuIds);
        long invalidParentMenus = parentMenuIds.stream()
                .filter(unavailableParentMenuIds::contains)
                .count();
        if (invalidParentMenus > 0) {
            throw new IllegalStateException("infra_codegen_table.parent_menu_id 无效引用 " + invalidParentMenus + " 条");
        }
        int orphanFiles = fileMapper.selectOrphanFileConfigCount();
        if (orphanFiles > 0) {
            throw new IllegalStateException("infra_file.config_id 孤儿引用 " + orphanFiles + " 条");
        }
        int orphanFileContents = fileContentMapper.selectOrphanFileConfigCount();
        if (orphanFileContents > 0) {
            throw new IllegalStateException("infra_file_content.config_id 孤儿引用 " + orphanFileContents + " 条");
        }
        return "infra 逻辑引用完整性检查通过";
    }
}

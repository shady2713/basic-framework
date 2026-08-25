package com.basicframework.module.infra.controller.admin.codegen.vo;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.infra.controller.admin.codegen.vo.table.CodegenTableSaveReqVO;
import com.basicframework.module.infra.enums.codegen.CodegenTemplateTypeEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodegenRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void subTemplate_requiresCompleteRelationFields() {
        CodegenTableSaveReqVO table = table(CodegenTemplateTypeEnum.SUB);

        assertThat(table.isSubValid()).isFalse();

        table.setMasterTableId(1L);
        table.setSubJoinColumnId(2L);
        table.setSubJoinMany(false);
        assertThat(table.isSubValid()).isTrue();
    }

    @Test
    void treeTemplate_requiresParentAndNameColumns() {
        CodegenTableSaveReqVO table = table(CodegenTemplateTypeEnum.TREE);

        assertThat(table.isTreeValid()).isFalse();

        table.setTreeParentColumnId(1L);
        table.setTreeNameColumnId(2L);
        assertThat(table.isTreeValid()).isTrue();
    }

    @Test
    void singleTable_doesNotRequireSubOrTreeFields() {
        CodegenTableSaveReqVO table = table(CodegenTemplateTypeEnum.ONE);

        assertThat(table.isSubValid()).isTrue();
        assertThat(table.isTreeValid()).isTrue();
    }

    @Test
    void updateRequest_rejectsEmptyColumnList() {
        CodegenUpdateReqVO request = new CodegenUpdateReqVO();
        request.setTable(table(CodegenTemplateTypeEnum.ONE));
        request.setColumns(List.of());

        assertThat(validator.validate(request))
                .anySatisfy(violation ->
                        assertThat(violation.getPropertyPath().toString()).isEqualTo("columns"));
    }

    private static CodegenTableSaveReqVO table(CodegenTemplateTypeEnum type) {
        CodegenTableSaveReqVO table = new CodegenTableSaveReqVO();
        table.setTemplateType(type.getType());
        return table;
    }
}

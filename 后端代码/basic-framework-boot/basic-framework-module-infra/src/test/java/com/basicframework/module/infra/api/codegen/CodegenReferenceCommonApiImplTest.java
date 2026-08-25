package com.basicframework.module.infra.api.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.dal.mysql.codegen.CodegenTableMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CodegenReferenceCommonApiImplTest {

    @InjectMocks
    private CodegenReferenceCommonApiImpl api;

    @Mock
    private CodegenTableMapper codegenTableMapper;

    @Test
    void isParentMenuReferenced_returnsMapperResult() {
        when(codegenTableMapper.selectCountByParentMenuId(10L)).thenReturn(1L);

        assertThat(api.isParentMenuReferenced(10L)).isTrue();
    }

    @Test
    void isParentMenuReferenced_ignoresNullAndRootWithoutDatabaseLookup() {
        assertThat(api.isParentMenuReferenced(null)).isFalse();
        assertThat(api.isParentMenuReferenced(0L)).isFalse();

        verify(codegenTableMapper, never()).selectCountByParentMenuId(org.mockito.ArgumentMatchers.any());
    }
}

package com.basicframework.module.infra.enums.codegen;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Supported frontend code generation types.
 */
@AllArgsConstructor
@Getter
public enum CodegenFrontTypeEnum {
    VUE3_VBEN5_EP_SCHEMA(50);

    /** Type code. */
    private final Integer type;
}

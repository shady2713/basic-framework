package com.basicframework.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 模块边界架构门禁
 *
 * 本测试放在 server 模块：它是唯一依赖全部业务模块的装配入口，
 * 才能在同一 classpath 下覆盖 framework（core）、module-system、module-infra 的全量主源码类。
 * importOptions 只导入 main classes，排除测试类自身。
 *
 * 规则 A/B/C/D 均为硬规则：当前零违例，任何新增违例直接让构建变红。
 * 规则 A/B/C 豁免业务模块的 api 包（公开的跨模块契约，调用方只许依赖 api
 * 契约，不得触碰实现）；convert 包引用 VO 属本职（VO 与 DO 互转），规则 D 对其豁免。
 */
@AnalyzeClasses(packages = "com.basicframework..", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchitectureTest {

    /**
     * 规则 A：module-system 不得依赖 module-infra 的内部实现，只允许使用公开 api 契约。
     */
    @ArchTest
    static final ArchRule system_must_not_depend_on_infra = noClasses()
            .that()
            .resideInAPackage("com.basicframework.module.system..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module.infra..")
                    .and(JavaClass.Predicates.resideOutsideOfPackage("com.basicframework.module.infra..api..")));

    /**
     * 规则 B：module-infra 不得依赖 module-system 的内部实现，只允许使用公开 api 契约。
     */
    @ArchTest
    static final ArchRule infra_must_not_depend_on_system = noClasses()
            .that()
            .resideInAPackage("com.basicframework.module.infra..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module.system..")
                    .and(JavaClass.Predicates.resideOutsideOfPackage("com.basicframework.module.system..api..")));

    /**
     * 规则 C：framework（core starters）不得依赖任何业务模块。
     * starter 是能力接缝，只允许被业务模块消费，反向依赖会破坏分层。
     * 豁免：业务模块的 api 包是公开的跨模块契约（CommonApi + DTO，由
     * basic-framework-module-xxx-api 薄模块承载），framework 只许依赖
     * api 契约，不得触碰业务模块的内部实现。
     */
    @ArchTest
    static final ArchRule framework_must_not_depend_on_modules = noClasses()
            .that()
            .resideInAPackage("com.basicframework.framework..")
            .should()
            .dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.basicframework.module..")
                    .and(JavaClass.Predicates.resideOutsideOfPackage("com.basicframework.module..api..")));

    /**
     * 规则 D：service/dal 层不得依赖 controller 包（含 vo）。
     * controller 是入站适配层，service/dal 反向引用 VO 属于越层。
     */
    @ArchTest
    static final ArchRule service_dal_must_not_depend_on_controller = noClasses()
            .that()
            .resideInAnyPackage("..service..", "..dal..")
            .and()
            .resideOutsideOfPackage("..convert..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..controller..");
}

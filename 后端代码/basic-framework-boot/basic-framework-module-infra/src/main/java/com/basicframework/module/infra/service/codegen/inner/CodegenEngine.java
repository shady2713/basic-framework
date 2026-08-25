package com.basicframework.module.infra.service.codegen.inner;

import static cn.hutool.core.map.MapUtil.getStr;
import static cn.hutool.core.text.CharSequenceUtil.equalsAnyIgnoreCase;
import static cn.hutool.core.text.CharSequenceUtil.lowerFirst;
import static cn.hutool.core.text.CharSequenceUtil.removePrefix;
import static cn.hutool.core.text.CharSequenceUtil.toSymbolCase;
import static cn.hutool.core.text.CharSequenceUtil.toUnderlineCase;
import static cn.hutool.core.text.CharSequenceUtil.upperFirst;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.template.TemplateConfig;
import cn.hutool.extra.template.TemplateEngine;
import cn.hutool.extra.template.engine.velocity.VelocityEngine;
import cn.hutool.system.SystemUtil;
import com.baomidou.mybatisplus.annotation.DbType;
import com.basicframework.framework.apilog.core.annotation.ApiAccessLog;
import com.basicframework.framework.apilog.core.enums.OperateTypeEnum;
import com.basicframework.framework.common.exception.util.ServiceExceptionUtil;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.framework.common.util.date.DateUtils;
import com.basicframework.framework.common.util.date.LocalDateTimeUtils;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.framework.common.util.object.ObjectUtils;
import com.basicframework.framework.excel.core.annotations.DictFormat;
import com.basicframework.framework.excel.core.convert.DictConvert;
import com.basicframework.framework.excel.core.util.ExcelUtils;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenColumnDO;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenTableDO;
import com.basicframework.module.infra.enums.codegen.CodegenFrontTypeEnum;
import com.basicframework.module.infra.enums.codegen.CodegenSceneEnum;
import com.basicframework.module.infra.enums.codegen.CodegenTemplateTypeEnum;
import com.basicframework.module.infra.enums.codegen.CodegenVOTypeEnum;
import com.basicframework.module.infra.framework.codegen.config.CodegenProperties;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
public class CodegenEngine {

    private static final Map<String, String> SERVER_TEMPLATES = MapUtil.<String, String>builder(new LinkedHashMap<>())
            .put(javaTemplatePath("controller/vo/pageReqVO"), javaModuleImplVOFilePath("PageReqVO"))
            .put(javaTemplatePath("controller/vo/listReqVO"), javaModuleImplVOFilePath("ListReqVO"))
            .put(javaTemplatePath("controller/vo/respVO"), javaModuleImplVOFilePath("RespVO"))
            .put(javaTemplatePath("controller/vo/saveReqVO"), javaModuleImplVOFilePath("SaveReqVO"))
            .put(javaTemplatePath("controller/controller"), javaModuleImplControllerFilePath())
            .put(
                    javaTemplatePath("dal/do"),
                    javaModuleImplMainFilePath("dal/dataobject/${table.businessName}/${table.className}DO"))
            .put(
                    javaTemplatePath("dal/do_sub"),
                    javaModuleImplMainFilePath("dal/dataobject/${table.businessName}/${subTable.className}DO"))
            .put(
                    javaTemplatePath("dal/mapper"),
                    javaModuleImplMainFilePath("dal/mysql/${table.businessName}/${table.className}Mapper"))
            .put(
                    javaTemplatePath("dal/mapper_sub"),
                    javaModuleImplMainFilePath("dal/mysql/${table.businessName}/${subTable.className}Mapper"))
            .put(javaTemplatePath("dal/mapper.xml"), mapperXmlFilePath())
            .put(
                    javaTemplatePath("service/serviceImpl"),
                    javaModuleImplMainFilePath("service/${table.businessName}/${table.className}ServiceImpl"))
            .put(
                    javaTemplatePath("service/service"),
                    javaModuleImplMainFilePath("service/${table.businessName}/${table.className}Service"))
            .put(
                    javaTemplatePath("test/serviceTest"),
                    javaModuleImplTestFilePath("service/${table.businessName}/${table.className}ServiceImplTest"))
            .put(javaTemplatePath("enums/errorcode"), javaModuleApiMainFilePath("enums/ErrorCodeConstants"))
            .put("codegen/sql/sql.vm", "sql/sql.sql")
            .put("codegen/sql/h2.vm", "sql/h2.sql")
            .build();

    private static final Table<Integer, String, String> FRONT_TEMPLATES =
            ImmutableTable.<Integer, String, String>builder()
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("views/data.ts"),
                            vue3VbenFilePath("views/${table.moduleName}/${table.businessName}/data.ts"))
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("views/index.vue"),
                            vue3VbenFilePath("views/${table.moduleName}/${table.businessName}/index.vue"))
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("views/form.vue"),
                            vue3VbenFilePath("views/${table.moduleName}/${table.businessName}/modules/form.vue"))
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("api/api.ts"),
                            vue3VbenFilePath("api/${table.moduleName}/${table.businessName}/index.ts"))
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("views/modules/form_sub_normal.vue"),
                            vue3VbenFilePath(
                                    "views/${table.moduleName}/${table.businessName}/modules/${subSimpleClassName_strikeCase}-form.vue"))
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("views/modules/form_sub_inner.vue"),
                            vue3VbenFilePath(
                                    "views/${table.moduleName}/${table.businessName}/modules/${subSimpleClassName_strikeCase}-form.vue"))
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("views/modules/form_sub_erp.vue"),
                            vue3VbenFilePath(
                                    "views/${table.moduleName}/${table.businessName}/modules/${subSimpleClassName_strikeCase}-form.vue"))
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("views/modules/list_sub_inner.vue"),
                            vue3VbenFilePath(
                                    "views/${table.moduleName}/${table.businessName}/modules/${subSimpleClassName_strikeCase}-list.vue"))
                    .put(
                            CodegenFrontTypeEnum.VUE3_VBEN5_EP_SCHEMA.getType(),
                            vue3Vben5EpSchemaTemplatePath("views/modules/list_sub_erp.vue"),
                            vue3VbenFilePath(
                                    "views/${table.moduleName}/${table.businessName}/modules/${subSimpleClassName_strikeCase}-list.vue"))
                    .build();

    @Resource
    private CodegenProperties codegenProperties;

    @Setter
    private Boolean jakartaEnable;

    @Setter
    private Boolean cloudEnable;

    private final TemplateEngine templateEngine;
    private final Map<String, Object> globalBindingMap = new HashMap<>();

    public CodegenEngine() {
        TemplateConfig config = new TemplateConfig();
        config.setResourceMode(TemplateConfig.ResourceMode.CLASSPATH);
        this.templateEngine = new VelocityEngine(config);
        this.jakartaEnable = SystemUtil.getJavaInfo().isJavaVersionAtLeast(1700)
                && ClassUtils.isPresent("jakarta.annotation.Resource", ClassUtils.getDefaultClassLoader());
        this.cloudEnable = ClassUtils.isPresent(
                "com.basicframework.module.infra.framework.rpc.config.RpcConfiguration",
                ClassUtils.getDefaultClassLoader());
    }

    @PostConstruct
    @VisibleForTesting
    void initGlobalBindingMap() {
        globalBindingMap.put("basePackage", codegenProperties.getBasePackage());
        globalBindingMap.put("baseFrameworkPackage", codegenProperties.getBasePackage() + ".framework");
        globalBindingMap.put("jakartaPackage", jakartaEnable ? "jakarta" : "javax");
        globalBindingMap.put("voType", codegenProperties.getVoType());
        globalBindingMap.put("deleteBatchEnable", codegenProperties.getDeleteBatchEnable());
        globalBindingMap.put("CommonResultClassName", CommonResult.class.getName());
        globalBindingMap.put("PageResultClassName", PageResult.class.getName());
        globalBindingMap.put("PageParamClassName", PageParam.class.getName());
        globalBindingMap.put("DictFormatClassName", DictFormat.class.getName());
        globalBindingMap.put("BaseDOClassName", BaseDO.class.getName());
        globalBindingMap.put("baseDOFields", CodegenBuilder.BASE_DO_FIELDS);
        globalBindingMap.put("QueryWrapperClassName", LambdaQueryWrapperX.class.getName());
        globalBindingMap.put("BaseMapperClassName", BaseMapperX.class.getName());
        globalBindingMap.put("ServiceExceptionUtilClassName", ServiceExceptionUtil.class.getName());
        globalBindingMap.put("DateUtilsClassName", DateUtils.class.getName());
        globalBindingMap.put("ExcelUtilsClassName", ExcelUtils.class.getName());
        globalBindingMap.put("LocalDateTimeUtilsClassName", LocalDateTimeUtils.class.getName());
        globalBindingMap.put("ObjectUtilsClassName", ObjectUtils.class.getName());
        globalBindingMap.put("DictConvertClassName", DictConvert.class.getName());
        globalBindingMap.put("ApiAccessLogClassName", ApiAccessLog.class.getName());
        globalBindingMap.put("OperateTypeEnumClassName", OperateTypeEnum.class.getName());
        globalBindingMap.put("BeanUtils", BeanUtils.class.getName());
        globalBindingMap.put("CollectionUtilsClassName", CollectionUtils.class.getName());
    }

    public Map<String, String> execute(
            DbType dbType,
            CodegenTableDO table,
            List<CodegenColumnDO> columns,
            List<CodegenTableDO> subTables,
            List<List<CodegenColumnDO>> subColumnsList) {
        Map<String, Object> bindingMap = initBindingMap(dbType, table, columns, subTables, subColumnsList);
        Map<String, String> templates = getTemplates(table.getFrontType());
        Map<String, String> result = Maps.newLinkedHashMapWithExpectedSize(templates.size());
        templates.forEach((vmPath, filePath) -> {
            if (isSubTemplate(vmPath)) {
                generateSubCode(table, subTables, result, vmPath, filePath, bindingMap);
                return;
            }
            if (isPageReqVOTemplate(vmPath) && CodegenTemplateTypeEnum.isTree(table.getTemplateType())) {
                return;
            }
            if (isListReqVOTemplate(vmPath) && !CodegenTemplateTypeEnum.isTree(table.getTemplateType())) {
                return;
            }
            generateCode(result, vmPath, filePath, bindingMap);
        });
        return result;
    }

    private void generateCode(
            Map<String, String> result, String vmPath, String filePath, Map<String, Object> bindingMap) {
        filePath = formatFilePath(filePath, bindingMap);
        String content = templateEngine.getTemplate(vmPath).render(bindingMap);
        result.put(filePath, content);
    }

    private void generateSubCode(
            CodegenTableDO table,
            List<CodegenTableDO> subTables,
            Map<String, String> result,
            String vmPath,
            String filePath,
            Map<String, Object> bindingMap) {
        if (CollUtil.isEmpty(subTables)) {
            return;
        }
        if (vmPath.contains("_normal")
                && ObjectUtil.notEqual(table.getTemplateType(), CodegenTemplateTypeEnum.MASTER_NORMAL.getType())) {
            return;
        }
        if (vmPath.contains("_erp")
                && ObjectUtil.notEqual(table.getTemplateType(), CodegenTemplateTypeEnum.MASTER_ERP.getType())) {
            return;
        }
        if (vmPath.contains("_inner")
                && ObjectUtil.notEqual(table.getTemplateType(), CodegenTemplateTypeEnum.MASTER_INNER.getType())) {
            return;
        }

        for (int i = 0; i < subTables.size(); i++) {
            bindingMap.put("subIndex", i);
            generateCode(result, vmPath, filePath, bindingMap);
        }
        bindingMap.remove("subIndex");
    }

    private Map<String, Object> initBindingMap(
            DbType dbType,
            CodegenTableDO table,
            List<CodegenColumnDO> columns,
            List<CodegenTableDO> subTables,
            List<List<CodegenColumnDO>> subColumnsList) {
        Map<String, Object> bindingMap = new HashMap<>(globalBindingMap);
        bindingMap.put("dbType", dbType);
        bindingMap.put("table", table);
        bindingMap.put("columns", columns);
        bindingMap.put("primaryColumn", CollectionUtils.findFirst(columns, CodegenColumnDO::getPrimaryKey));
        bindingMap.put("sceneEnum", CodegenSceneEnum.valueOf(table.getScene()));

        String className = table.getClassName();
        String simpleClassName = equalsAnyIgnoreCase(table.getClassName(), table.getModuleName())
                ? table.getClassName()
                : removePrefix(table.getClassName(), upperFirst(table.getModuleName()));
        String classNameVar = lowerFirst(simpleClassName);
        bindingMap.put("simpleClassName", simpleClassName);
        bindingMap.put("simpleClassName_underlineCase", toUnderlineCase(simpleClassName));
        bindingMap.put("classNameVar", classNameVar);
        String simpleClassNameStrikeCase = toSymbolCase(simpleClassName, '-');
        bindingMap.put("simpleClassName_strikeCase", simpleClassNameStrikeCase);
        bindingMap.put("permissionPrefix", table.getModuleName() + ":" + simpleClassNameStrikeCase);

        if (CodegenTemplateTypeEnum.isTree(table.getTemplateType())) {
            CodegenColumnDO treeParentColumn =
                    CollUtil.findOne(columns, column -> Objects.equals(column.getId(), table.getTreeParentColumnId()));
            bindingMap.put("treeParentColumn", treeParentColumn);
            bindingMap.put(
                    "treeParentColumn_javaField_underlineCase", toUnderlineCase(treeParentColumn.getJavaField()));
            CodegenColumnDO treeNameColumn =
                    CollUtil.findOne(columns, column -> Objects.equals(column.getId(), table.getTreeNameColumnId()));
            bindingMap.put("treeNameColumn", treeNameColumn);
            bindingMap.put("treeNameColumn_javaField_underlineCase", toUnderlineCase(treeNameColumn.getJavaField()));
        }

        if (CollUtil.isNotEmpty(subTables)) {
            bindingMap.put("subTables", subTables);
            bindingMap.put("subColumnsList", subColumnsList);
            List<CodegenColumnDO> subPrimaryColumns = new ArrayList<>();
            List<CodegenColumnDO> subJoinColumns = new ArrayList<>();
            List<String> subJoinColumnStrikeCases = new ArrayList<>();
            List<String> subSimpleClassNames = new ArrayList<>();
            List<String> subClassNameVars = new ArrayList<>();
            List<String> simpleClassNameUnderlineCases = new ArrayList<>();
            List<String> subSimpleClassNameStrikeCases = new ArrayList<>();
            for (int i = 0; i < subTables.size(); i++) {
                CodegenTableDO subTable = subTables.get(i);
                List<CodegenColumnDO> subColumns = subColumnsList.get(i);
                subPrimaryColumns.add(CollectionUtils.findFirst(subColumns, CodegenColumnDO::getPrimaryKey));
                CodegenColumnDO subColumn = CollectionUtils.findFirst(
                        subColumns, column -> Objects.equals(column.getId(), subTable.getSubJoinColumnId()));
                subJoinColumns.add(subColumn);
                subJoinColumnStrikeCases.add(toSymbolCase(subColumn.getJavaField(), '-'));
                String subSimpleClassName = removePrefix(subTable.getClassName(), upperFirst(subTable.getModuleName()));
                subSimpleClassNames.add(subSimpleClassName);
                simpleClassNameUnderlineCases.add(toUnderlineCase(subSimpleClassName));
                subClassNameVars.add(lowerFirst(subSimpleClassName));
                subSimpleClassNameStrikeCases.add(toSymbolCase(subSimpleClassName, '-'));
            }
            bindingMap.put("subPrimaryColumns", subPrimaryColumns);
            bindingMap.put("subJoinColumns", subJoinColumns);
            bindingMap.put("subJoinColumn_strikeCases", subJoinColumnStrikeCases);
            bindingMap.put("subSimpleClassNames", subSimpleClassNames);
            bindingMap.put("simpleClassNameUnderlineCases", simpleClassNameUnderlineCases);
            bindingMap.put("subClassNameVars", subClassNameVars);
            bindingMap.put("subSimpleClassName_strikeCases", subSimpleClassNameStrikeCases);
        }

        if (ObjectUtil.equal(codegenProperties.getVoType(), CodegenVOTypeEnum.VO.getType())) {
            String prefixClass = CodegenSceneEnum.valueOf(table.getScene()).getPrefixClass();
            bindingMap.put("saveReqVOClass", prefixClass + className + "SaveReqVO");
            bindingMap.put("updateReqVOClass", prefixClass + className + "SaveReqVO");
            bindingMap.put("respVOClass", prefixClass + className + "RespVO");
            bindingMap.put("saveReqVOVar", "createReqVO");
            bindingMap.put("updateReqVOVar", "updateReqVO");
        } else if (ObjectUtil.equal(codegenProperties.getVoType(), CodegenVOTypeEnum.DO.getType())) {
            bindingMap.put("saveReqVOClass", className + "DO");
            bindingMap.put("updateReqVOClass", className + "DO");
            bindingMap.put("respVOClass", className + "DO");
            bindingMap.put("saveReqVOVar", classNameVar);
            bindingMap.put("updateReqVOVar", classNameVar);
        }
        return bindingMap;
    }

    private Map<String, String> getTemplates(Integer frontType) {
        Map<String, String> templates = new LinkedHashMap<>();
        templates.putAll(SERVER_TEMPLATES);
        templates.putAll(FRONT_TEMPLATES.row(frontType));
        if (Boolean.FALSE.equals(cloudEnable)) {
            SERVER_TEMPLATES.forEach((templatePath, filePath) -> {
                filePath = StrUtil.replace(filePath, "/basic-framework-module-${table.moduleName}-api", "");
                filePath = StrUtil.replace(filePath, "/basic-framework-module-${table.moduleName}-server", "");
                templates.put(templatePath, filePath);
            });
        }
        if (Boolean.FALSE.equals(codegenProperties.getUnitTestEnable())) {
            templates.remove(javaTemplatePath("test/serviceTest"));
            templates.remove("codegen/sql/h2.vm");
        }
        if (ObjectUtil.notEqual(codegenProperties.getVoType(), CodegenVOTypeEnum.VO.getType())) {
            templates.remove(javaTemplatePath("controller/vo/respVO"));
            templates.remove(javaTemplatePath("controller/vo/saveReqVO"));
        }
        return templates;
    }

    @SuppressWarnings("unchecked")
    private String formatFilePath(String filePath, Map<String, Object> bindingMap) {
        filePath = StrUtil.replace(
                filePath, "${basePackage}", getStr(bindingMap, "basePackage").replaceAll("\\.", "/"));
        filePath = StrUtil.replace(filePath, "${classNameVar}", getStr(bindingMap, "classNameVar"));
        filePath = StrUtil.replace(filePath, "${simpleClassName}", getStr(bindingMap, "simpleClassName"));

        CodegenSceneEnum sceneEnum = (CodegenSceneEnum) bindingMap.get("sceneEnum");
        filePath = StrUtil.replace(filePath, "${sceneEnum.prefixClass}", sceneEnum.getPrefixClass());
        filePath = StrUtil.replace(filePath, "${sceneEnum.basePackage}", sceneEnum.getBasePackage());

        CodegenTableDO table = (CodegenTableDO) bindingMap.get("table");
        filePath = StrUtil.replace(filePath, "${table.moduleName}", table.getModuleName());
        filePath = StrUtil.replace(filePath, "${table.businessName}", table.getBusinessName());
        filePath = StrUtil.replace(filePath, "${table.className}", table.getClassName());

        Integer subIndex = (Integer) bindingMap.get("subIndex");
        if (subIndex != null) {
            CodegenTableDO subTable = ((List<CodegenTableDO>) bindingMap.get("subTables")).get(subIndex);
            filePath = StrUtil.replace(filePath, "${subTable.moduleName}", subTable.getModuleName());
            filePath = StrUtil.replace(filePath, "${subTable.businessName}", subTable.getBusinessName());
            filePath = StrUtil.replace(filePath, "${subTable.className}", subTable.getClassName());
            filePath = StrUtil.replace(
                    filePath,
                    "${subSimpleClassName}",
                    ((List<String>) bindingMap.get("subSimpleClassNames")).get(subIndex));
            filePath = StrUtil.replace(
                    filePath,
                    "${subSimpleClassName_strikeCase}",
                    ((List<String>) bindingMap.get("subSimpleClassName_strikeCases")).get(subIndex));
        }
        return filePath;
    }

    private static String javaTemplatePath(String path) {
        return "codegen/java/" + path + ".vm";
    }

    private static String javaModuleImplVOFilePath(String path) {
        return javaModuleFilePath(
                "controller/${sceneEnum.basePackage}/${table.businessName}/"
                        + "vo/${sceneEnum.prefixClass}${table.className}" + path,
                "server",
                "main");
    }

    private static String javaModuleImplControllerFilePath() {
        return javaModuleFilePath(
                "controller/${sceneEnum.basePackage}/${table.businessName}/"
                        + "${sceneEnum.prefixClass}${table.className}Controller",
                "server",
                "main");
    }

    private static String javaModuleImplMainFilePath(String path) {
        return javaModuleFilePath(path, "server", "main");
    }

    private static String javaModuleApiMainFilePath(String path) {
        return javaModuleFilePath(path, "api", "main");
    }

    private static String javaModuleImplTestFilePath(String path) {
        return javaModuleFilePath(path, "server", "test");
    }

    private static String javaModuleFilePath(String path, String module, String src) {
        return "basic-framework-module-${table.moduleName}/"
                + "basic-framework-module-${table.moduleName}-" + module + "/"
                + "src/" + src + "/java/${basePackage}/module/${table.moduleName}/" + path + ".java";
    }

    private static String mapperXmlFilePath() {
        return "basic-framework-module-${table.moduleName}/"
                + "basic-framework-module-${table.moduleName}-server/"
                + "src/main/resources/mapper/${table.businessName}/${table.className}Mapper.xml";
    }

    private static String vue3VbenFilePath(String path) {
        return "basic_framework-ui-${sceneEnum.basePackage}-vben/" + "src/" + path;
    }

    private static String vue3Vben5EpSchemaTemplatePath(String path) {
        return "codegen/vue3_vben5_ele/schema/" + path + ".vm";
    }

    private static boolean isSubTemplate(String path) {
        return path.contains("_sub");
    }

    private static boolean isPageReqVOTemplate(String path) {
        return path.contains("pageReqVO");
    }

    private static boolean isListReqVOTemplate(String path) {
        return path.contains("listReqVO");
    }
}

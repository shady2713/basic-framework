package com.basicframework.module.infra.convert.codegen;

import com.baomidou.mybatisplus.generator.config.po.TableField;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.framework.common.util.object.BeanUtils;
import com.basicframework.module.infra.controller.admin.codegen.vo.CodegenDetailRespVO;
import com.basicframework.module.infra.controller.admin.codegen.vo.CodegenPreviewRespVO;
import com.basicframework.module.infra.controller.admin.codegen.vo.column.CodegenColumnRespVO;
import com.basicframework.module.infra.controller.admin.codegen.vo.column.CodegenColumnSaveReqVO;
import com.basicframework.module.infra.controller.admin.codegen.vo.table.CodegenTableRespVO;
import com.basicframework.module.infra.controller.admin.codegen.vo.table.CodegenTableSaveReqVO;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenColumnDO;
import com.basicframework.module.infra.dal.dataobject.codegen.CodegenTableDO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.type.JdbcType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CodegenConvert {

    CodegenConvert INSTANCE = Mappers.getMapper(CodegenConvert.class);

    // ========== TableInfo 相关 ==========

    @Mappings({
        @Mapping(source = "name", target = "tableName"),
        @Mapping(source = "comment", target = "tableComment"),
        @Mapping(target = "createTime", ignore = true),
        @Mapping(target = "updateTime", ignore = true),
        @Mapping(target = "creator", ignore = true),
        @Mapping(target = "updater", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "scene", ignore = true),
        @Mapping(target = "remark", ignore = true),
        @Mapping(target = "moduleName", ignore = true),
        @Mapping(target = "businessName", ignore = true),
        @Mapping(target = "className", ignore = true),
        @Mapping(target = "classComment", ignore = true),
        @Mapping(target = "author", ignore = true),
        @Mapping(target = "templateType", ignore = true),
        @Mapping(target = "frontType", ignore = true),
        @Mapping(target = "parentMenuId", ignore = true),
        @Mapping(target = "masterTableId", ignore = true),
        @Mapping(target = "subJoinColumnId", ignore = true),
        @Mapping(target = "subJoinMany", ignore = true),
        @Mapping(target = "treeParentColumnId", ignore = true),
        @Mapping(target = "treeNameColumnId", ignore = true),
        @Mapping(target = "transMap", ignore = true),
    })
    CodegenTableDO convert(TableInfo bean);

    List<CodegenColumnDO> convertList(List<TableField> list);

    @Mappings({
        @Mapping(source = "name", target = "columnName"),
        @Mapping(source = "metaInfo.jdbcType", target = "dataType", qualifiedByName = "getDataType"),
        @Mapping(source = "comment", target = "columnComment"),
        @Mapping(source = "metaInfo.nullable", target = "nullable"),
        @Mapping(source = "keyFlag", target = "primaryKey"),
        @Mapping(source = "columnType.type", target = "javaType"),
        @Mapping(source = "propertyName", target = "javaField"),
        @Mapping(target = "createTime", ignore = true),
        @Mapping(target = "updateTime", ignore = true),
        @Mapping(target = "creator", ignore = true),
        @Mapping(target = "updater", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "id", ignore = true),
        @Mapping(target = "tableId", ignore = true),
        @Mapping(target = "ordinalPosition", ignore = true),
        @Mapping(target = "dictType", ignore = true),
        @Mapping(target = "example", ignore = true),
        @Mapping(target = "createOperation", ignore = true),
        @Mapping(target = "updateOperation", ignore = true),
        @Mapping(target = "listOperation", ignore = true),
        @Mapping(target = "listOperationCondition", ignore = true),
        @Mapping(target = "listOperationResult", ignore = true),
        @Mapping(target = "htmlType", ignore = true),
        @Mapping(target = "transMap", ignore = true),
    })
    CodegenColumnDO convert(TableField bean);

    @Named("getDataType")
    default String getDataType(JdbcType jdbcType) {
        return jdbcType.name();
    }

    // ========== SaveReqVO 相关 ==========

    @Mappings({
        @Mapping(target = "createTime", ignore = true),
        @Mapping(target = "updateTime", ignore = true),
        @Mapping(target = "creator", ignore = true),
        @Mapping(target = "updater", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "transMap", ignore = true),
    })
    CodegenTableDO convert(CodegenTableSaveReqVO bean);

    List<CodegenColumnDO> convertSaveList(List<CodegenColumnSaveReqVO> list);

    @Mappings({
        @Mapping(target = "createTime", ignore = true),
        @Mapping(target = "updateTime", ignore = true),
        @Mapping(target = "creator", ignore = true),
        @Mapping(target = "updater", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "transMap", ignore = true),
    })
    CodegenColumnDO convert(CodegenColumnSaveReqVO bean);

    // ========== 其它 ==========

    default CodegenDetailRespVO convert(CodegenTableDO table, List<CodegenColumnDO> columns) {
        CodegenDetailRespVO respVO = new CodegenDetailRespVO();
        respVO.setTable(BeanUtils.toBean(table, CodegenTableRespVO.class));
        respVO.setColumns(BeanUtils.toBean(columns, CodegenColumnRespVO.class));
        return respVO;
    }

    default List<CodegenPreviewRespVO> convert(Map<String, String> codes) {
        return CollectionUtils.convertList(
                codes.entrySet(),
                entry -> new CodegenPreviewRespVO().setFilePath(entry.getKey()).setCode(entry.getValue()));
    }
}

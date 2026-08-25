package com.basicframework.module.infra.convert.config;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.infra.controller.admin.config.vo.ConfigRespVO;
import com.basicframework.module.infra.controller.admin.config.vo.ConfigSaveReqVO;
import com.basicframework.module.infra.dal.dataobject.config.ConfigDO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ConfigConvert {

    ConfigConvert INSTANCE = Mappers.getMapper(ConfigConvert.class);

    PageResult<ConfigRespVO> convertPage(PageResult<ConfigDO> page);

    List<ConfigRespVO> convertList(List<ConfigDO> list);

    @Mapping(source = "configKey", target = "key")
    ConfigRespVO convert(ConfigDO bean);

    @Mappings({
        @Mapping(source = "key", target = "configKey"),
        @Mapping(target = "createTime", ignore = true),
        @Mapping(target = "updateTime", ignore = true),
        @Mapping(target = "creator", ignore = true),
        @Mapping(target = "updater", ignore = true),
        @Mapping(target = "deleted", ignore = true),
        @Mapping(target = "type", ignore = true),
        @Mapping(target = "transMap", ignore = true),
    })
    ConfigDO convert(ConfigSaveReqVO bean);
}

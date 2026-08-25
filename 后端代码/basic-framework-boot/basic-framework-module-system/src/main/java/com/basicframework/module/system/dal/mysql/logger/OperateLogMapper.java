package com.basicframework.module.system.dal.mysql.logger;

import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.api.logger.dto.OperateLogPageReqDTO;
import com.basicframework.module.system.dal.dataobject.logger.OperateLogDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OperateLogMapper extends BaseMapperX<OperateLogDO> {

    @Delete("DELETE FROM system_operate_log WHERE create_time < #{createTime} ORDER BY id LIMIT #{limit}")
    int deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") int limit);

    default PageResult<OperateLogDO> selectPage(PageParam pageParam, OperateLogQuery query) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<OperateLogDO>()
                        .eqIfPresent(OperateLogDO::getUserId, query.getUserId())
                        .eqIfPresent(OperateLogDO::getBizId, query.getBizId())
                        .likeIfPresent(OperateLogDO::getType, query.getType())
                        .likeIfPresent(OperateLogDO::getSubType, query.getSubType())
                        .likeIfPresent(OperateLogDO::getAction, query.getAction())
                        .betweenIfPresent(OperateLogDO::getCreateTime, query.getCreateTime())
                        .orderByDesc(OperateLogDO::getId));
    }

    default PageResult<OperateLogDO> selectPage(OperateLogPageReqDTO pageReqDTO) {
        return selectPage(
                pageReqDTO,
                new LambdaQueryWrapperX<OperateLogDO>()
                        .eqIfPresent(OperateLogDO::getType, pageReqDTO.getType())
                        .eqIfPresent(OperateLogDO::getBizId, pageReqDTO.getBizId())
                        .eqIfPresent(OperateLogDO::getUserId, pageReqDTO.getUserId())
                        .orderByDesc(OperateLogDO::getId));
    }
}

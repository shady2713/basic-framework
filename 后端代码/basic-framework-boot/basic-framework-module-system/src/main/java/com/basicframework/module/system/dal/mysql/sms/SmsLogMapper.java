package com.basicframework.module.system.dal.mysql.sms;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.system.dal.dataobject.sms.SmsLogDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 短信日志 Mapper（仅保留基础写入能力）
 */
@Mapper
public interface SmsLogMapper extends BaseMapperX<SmsLogDO> {

    @Delete("DELETE FROM system_sms_log WHERE create_time < #{createTime} ORDER BY id LIMIT #{limit}")
    int deleteByCreateTimeLt(@Param("createTime") LocalDateTime createTime, @Param("limit") int limit);
}

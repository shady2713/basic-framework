package com.basicframework.module.system.dal.mysql.auth;

import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.system.dal.dataobject.auth.MfaRecoveryCodeDO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MfaRecoveryCodeMapper extends BaseMapperX<MfaRecoveryCodeDO> {

    @Delete("DELETE FROM system_user_mfa_recovery_code WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Update(
            """
            UPDATE system_user_mfa_recovery_code
            SET used_time = #{usedTime}
            WHERE user_id = #{userId} AND code_hash = #{codeHash} AND used_time IS NULL
            """)
    int consume(
            @Param("userId") Long userId,
            @Param("codeHash") String codeHash,
            @Param("usedTime") LocalDateTime usedTime);
}

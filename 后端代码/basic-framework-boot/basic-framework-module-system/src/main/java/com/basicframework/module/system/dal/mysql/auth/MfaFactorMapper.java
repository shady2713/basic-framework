package com.basicframework.module.system.dal.mysql.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MfaFactorMapper extends BaseMapperX<MfaFactorDO> {

    default List<MfaFactorDO> selectEnabledByUserId(Long userId) {
        return selectList(new LambdaQueryWrapper<MfaFactorDO>()
                .eq(MfaFactorDO::getUserId, userId)
                .eq(MfaFactorDO::getEnabled, true));
    }

    default List<MfaFactorDO> selectEnabledByUserIdForUpdate(Long userId) {
        return selectList(new LambdaQueryWrapper<MfaFactorDO>()
                .eq(MfaFactorDO::getUserId, userId)
                .eq(MfaFactorDO::getEnabled, true)
                .last("FOR UPDATE"));
    }

    default MfaFactorDO selectEnabledByUserIdAndType(Long userId, Integer factorType) {
        return selectOne(new LambdaQueryWrapper<MfaFactorDO>()
                .eq(MfaFactorDO::getUserId, userId)
                .eq(MfaFactorDO::getFactorType, factorType)
                .eq(MfaFactorDO::getEnabled, true));
    }

    default MfaFactorDO selectEnabledByIdAndUserId(Long id, Long userId) {
        return selectOne(new LambdaQueryWrapper<MfaFactorDO>()
                .eq(MfaFactorDO::getId, id)
                .eq(MfaFactorDO::getUserId, userId)
                .eq(MfaFactorDO::getEnabled, true));
    }

    @Update(
            """
            UPDATE system_user_mfa_factor
            SET last_used_step = #{step}, update_time = #{updateTime}
            WHERE id = #{id} AND enabled = b'1'
              AND (last_used_step IS NULL OR last_used_step < #{step})
            """)
    int advanceTotpStep(@Param("id") Long id, @Param("step") long step, @Param("updateTime") LocalDateTime updateTime);

    @Update(
            """
            UPDATE system_user_mfa_factor
            SET secret_ciphertext = #{secretCiphertext}, last_used_step = #{lastUsedStep}, update_time = #{updateTime}
            WHERE id = #{id} AND user_id = #{userId} AND factor_type = #{factorType} AND enabled = b'1'
            """)
    int rotateTotpSecret(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("factorType") Integer factorType,
            @Param("secretCiphertext") String secretCiphertext,
            @Param("lastUsedStep") long lastUsedStep,
            @Param("updateTime") LocalDateTime updateTime);

    default int deleteEnabledByIdAndUserId(Long id, Long userId) {
        return delete(new LambdaQueryWrapper<MfaFactorDO>()
                .eq(MfaFactorDO::getId, id)
                .eq(MfaFactorDO::getUserId, userId)
                .eq(MfaFactorDO::getEnabled, true));
    }
}

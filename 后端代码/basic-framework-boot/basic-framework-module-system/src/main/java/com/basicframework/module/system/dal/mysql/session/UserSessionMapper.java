package com.basicframework.module.system.dal.mysql.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.basicframework.framework.common.pojo.PageParam;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.mybatis.core.mapper.BaseMapperX;
import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserSessionMapper extends BaseMapperX<UserSessionDO> {

    @Delete("DELETE FROM system_user_session WHERE refresh_expires_time < #{expiresTime} ORDER BY id LIMIT #{limit}")
    int deleteExpired(@Param("expiresTime") LocalDateTime expiresTime, @Param("limit") int limit);

    default UserSessionDO selectByAccessTokenHash(String accessTokenHash) {
        return selectOne(UserSessionDO::getAccessTokenHash, accessTokenHash);
    }

    default UserSessionDO selectByRefreshTokenHash(String refreshTokenHash) {
        return selectOne(UserSessionDO::getRefreshTokenHash, refreshTokenHash);
    }

    default int rotate(UserSessionDO session, String expectedRefreshTokenHash) {
        return update(
                session,
                new LambdaUpdateWrapper<UserSessionDO>()
                        .eq(UserSessionDO::getId, session.getId())
                        .eq(UserSessionDO::getRefreshTokenHash, expectedRefreshTokenHash));
    }

    default int deleteByIdAndRefreshTokenHash(Long id, String expectedRefreshTokenHash) {
        return delete(new LambdaQueryWrapper<UserSessionDO>()
                .eq(UserSessionDO::getId, id)
                .eq(UserSessionDO::getRefreshTokenHash, expectedRefreshTokenHash));
    }

    default PageResult<UserSessionDO> selectPage(PageParam pageParam, Long userId, Integer userType) {
        return selectPage(
                pageParam,
                new LambdaQueryWrapperX<UserSessionDO>()
                        .eqIfPresent(UserSessionDO::getUserId, userId)
                        .eqIfPresent(UserSessionDO::getUserType, userType)
                        .gt(UserSessionDO::getRefreshExpiresTime, LocalDateTime.now())
                        .orderByDesc(UserSessionDO::getId));
    }

    default List<UserSessionDO> selectListByUser(Long userId, Integer userType) {
        return selectList(UserSessionDO::getUserId, userId, UserSessionDO::getUserType, userType);
    }
}

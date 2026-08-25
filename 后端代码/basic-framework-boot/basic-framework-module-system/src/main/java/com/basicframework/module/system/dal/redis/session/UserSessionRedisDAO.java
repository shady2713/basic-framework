package com.basicframework.module.system.dal.redis.session;

import static com.basicframework.module.system.dal.redis.RedisKeyConstants.USER_SESSION;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.basicframework.framework.common.util.collection.CollectionUtils;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.system.dal.dataobject.session.UserSessionDO;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** 用户会话缓存。缓存键只使用访问令牌摘要，缓存值不包含刷新令牌摘要。 */
@Repository
public class UserSessionRedisDAO {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public UserSessionDO getByAccessTokenHash(String accessTokenHash) {
        return JsonUtils.parseObject(
                stringRedisTemplate.opsForValue().get(formatKey(accessTokenHash)), UserSessionDO.class);
    }

    public void set(UserSessionDO session) {
        long ttl = LocalDateTimeUtil.between(LocalDateTime.now(), session.getAccessExpiresTime(), ChronoUnit.SECONDS);
        if (ttl > 0) {
            stringRedisTemplate
                    .opsForValue()
                    .set(
                            formatKey(session.getAccessTokenHash()),
                            JsonUtils.toJsonString(toCacheValue(session)),
                            ttl,
                            TimeUnit.SECONDS);
        }
    }

    public void deleteByAccessTokenHash(String accessTokenHash) {
        stringRedisTemplate.delete(formatKey(accessTokenHash));
    }

    public void deleteByAccessTokenHashes(Collection<String> accessTokenHashes) {
        List<String> redisKeys = CollectionUtils.convertList(accessTokenHashes, UserSessionRedisDAO::formatKey);
        stringRedisTemplate.delete(redisKeys);
    }

    private static String formatKey(String accessTokenHash) {
        return String.format(USER_SESSION, accessTokenHash);
    }

    private static UserSessionDO toCacheValue(UserSessionDO source) {
        return new UserSessionDO()
                .setId(source.getId())
                .setAccessTokenHash(source.getAccessTokenHash())
                .setUserId(source.getUserId())
                .setUserType(source.getUserType())
                .setUserInfo(source.getUserInfo())
                .setAccessExpiresTime(source.getAccessExpiresTime());
    }
}

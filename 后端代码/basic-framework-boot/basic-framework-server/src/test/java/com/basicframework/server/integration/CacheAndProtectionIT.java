package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.framework.idempotent.core.annotation.Idempotent;
import com.basicframework.framework.idempotent.core.keyresolver.impl.ExpressionIdempotentKeyResolver;
import com.basicframework.framework.ratelimiter.core.annotation.RateLimiter;
import com.basicframework.framework.ratelimiter.core.keyresolver.impl.ExpressionRateLimiterKeyResolver;
import com.basicframework.module.system.dal.dataobject.dict.DictDataDO;
import com.basicframework.module.system.dal.dataobject.permission.RoleDO;
import com.basicframework.module.system.dal.mysql.dict.DictDataMapper;
import com.basicframework.module.system.dal.mysql.permission.RoleMapper;
import com.basicframework.module.system.dal.redis.RedisKeyConstants;
import com.basicframework.module.system.service.permission.RoleService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheManager;

/** 使用真实 Redis 与 Spring AOP 验证缓存、幂等和限流切面。 */
@Import(CacheAndProtectionIT.ProtectionTestConfiguration.class)
class CacheAndProtectionIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private DictDataMapper dictDataMapper;

    @Autowired
    private ProtectionProbe protectionProbe;

    @Test
    void cachesAndProtectionAspects_succeedAgainstRealRedis() {
        verifyRoleRedisCache();
        verifyDictLocalCache();
        verifyProtectionAspects();
    }

    private void verifyRoleRedisCache() {
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
        Cache roleCache = cacheManager.getCache(RedisKeyConstants.ROLE);
        assertThat(roleCache).isNotNull();
        roleCache.clear();

        RoleDO initial = roleService.getRoleListFromCache(List.of(1L)).get(0);
        assertThat(initial.getName()).isEqualTo("超级管理员");
        assertThat(roleService.hasAnySuperAdmin(List.of(1L))).isTrue();

        RoleDO roleUpdate = new RoleDO();
        roleUpdate.setId(1L);
        roleUpdate.setName("集成测试管理员");
        roleMapper.updateById(roleUpdate);
        assertThat(roleService.getRoleListFromCache(List.of(1L)).get(0).getName())
                .isEqualTo("超级管理员");

        roleCache.evict(1L);
        assertThat(roleService.getRoleListFromCache(List.of(1L)).get(0).getName())
                .isEqualTo("集成测试管理员");
    }

    private void verifyDictLocalCache() {
        DictFrameworkUtils.clearCache();
        try {
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男");

            DictDataDO dictUpdate = new DictDataDO();
            dictUpdate.setId(1L);
            dictUpdate.setLabel("男性");
            dictDataMapper.updateById(dictUpdate);
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男");

            DictFrameworkUtils.clearCache();
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男性");
        } finally {
            DictFrameworkUtils.clearCache();
        }
    }

    private void verifyProtectionAspects() {
        assertThat(AopUtils.isAopProxy(protectionProbe)).isTrue();

        assertThat(protectionProbe.idempotent("idempotent-integration")).isEqualTo(1);
        assertServiceException(
                GlobalErrorCodeConstants.REPEATED_REQUESTS.getCode(),
                () -> protectionProbe.idempotent("idempotent-integration"));
        assertThat(protectionProbe.getIdempotentInvocations()).isEqualTo(1);

        assertThat(protectionProbe.rateLimited("rate-limit-integration")).isEqualTo(1);
        assertServiceException(
                GlobalErrorCodeConstants.TOO_MANY_REQUESTS.getCode(),
                () -> protectionProbe.rateLimited("rate-limit-integration"));
        assertThat(protectionProbe.getRateLimitedInvocations()).isEqualTo(1);
    }

    static class ProtectionProbe {

        private int idempotentInvocations;
        private int rateLimitedInvocations;

        @Idempotent(timeout = 30, keyResolver = ExpressionIdempotentKeyResolver.class, keyArg = "#key")
        public int idempotent(String key) {
            return ++idempotentInvocations;
        }

        @RateLimiter(count = 1, time = 30, keyResolver = ExpressionRateLimiterKeyResolver.class, keyArg = "#key")
        public int rateLimited(String key) {
            return ++rateLimitedInvocations;
        }

        int getIdempotentInvocations() {
            return idempotentInvocations;
        }

        int getRateLimitedInvocations() {
            return rateLimitedInvocations;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtectionTestConfiguration {

        @Bean
        ProtectionProbe protectionProbe() {
            return new ProtectionProbe();
        }
    }
}

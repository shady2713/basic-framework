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
import com.basicframework.module.system.service.dept.DeptService;
import com.basicframework.module.system.service.dict.DictDataService;
import com.basicframework.module.system.service.permission.RoleService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

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
    private DictDataService dictDataService;

    @Autowired
    private DeptService deptService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ProtectionProbe protectionProbe;

    @Test
    void cachesAndProtectionAspects_succeedAgainstRealRedis() {
        verifyRoleRedisCache();
        verifyDictLocalCache();
        verifyDictDataRedisCache();
        verifyCacheTtlFallbacks();
        verifyProtectionAspects();
    }

    private void verifyRoleRedisCache() {
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
        Cache roleCache = cacheManager.getCache(RedisKeyConstants.ROLE);
        assertThat(roleCache).isNotNull();
        roleCache.clear();
        try {
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
        } finally {
            // 测试事务只回滚数据库；显式清理 Redis，避免把回滚前角色名泄漏给后续测试。
            roleCache.clear();
        }
    }

    private void verifyDictLocalCache() {
        // 字典标签解析经过两层缓存：DictFrameworkUtils 本地缓存 + Service 层 Redis 缓存
        Cache dictDataCache = cacheManager.getCache(RedisKeyConstants.DICT_DATA_LIST_BY_TYPE);
        assertThat(dictDataCache).isNotNull();
        dictDataCache.clear();
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

            // 绕过 Service 的直改不失效任何缓存：清本地缓存后仍命中 Redis 旧值
            DictFrameworkUtils.clearCache();
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男");

            dictDataCache.clear();
            DictFrameworkUtils.clearCache();
            assertThat(DictFrameworkUtils.parseDictDataLabel("system_user_sex", "1"))
                    .isEqualTo("男性");
        } finally {
            DictFrameworkUtils.clearCache();
            dictDataCache.clear();
        }
    }

    private void verifyDictDataRedisCache() {
        Cache dictDataCache = cacheManager.getCache(RedisKeyConstants.DICT_DATA_LIST_BY_TYPE);
        assertThat(dictDataCache).isNotNull();
        dictDataCache.clear();
        try {
            // 首次查询走数据库并写入 Redis，键带 #30m TTL 兜底
            List<DictDataDO> first = dictDataService.getDictDataListByDictType("system_user_sex");
            String initialLabel = first.stream()
                    .filter(d -> "1".equals(d.getValue()))
                    .findFirst()
                    .orElseThrow()
                    .getLabel();
            assertThat(stringRedisTemplate.getExpire("dict_data_list_by_type:system_user_sex", TimeUnit.SECONDS))
                    .isPositive();

            // 直改库不经过 Service，第二次查询命中缓存仍返回旧值
            DictDataDO dictUpdate = new DictDataDO();
            dictUpdate.setId(1L);
            dictUpdate.setLabel("缓存钉住");
            dictDataMapper.updateById(dictUpdate);
            List<DictDataDO> cached = dictDataService.getDictDataListByDictType("system_user_sex");
            assertThat(cached.stream()
                            .filter(d -> "1".equals(d.getValue()))
                            .findFirst()
                            .orElseThrow()
                            .getLabel())
                    .isEqualTo(initialLabel);

            // 经过 Service 写操作触发缓存失效，第三次查询读到新值
            DictDataDO serviceUpdate = new DictDataDO();
            serviceUpdate.setId(1L);
            serviceUpdate.setDictType("system_user_sex");
            serviceUpdate.setValue("1");
            serviceUpdate.setLabel("缓存钉住");
            dictDataService.updateDictData(serviceUpdate);
            List<DictDataDO> reloaded = dictDataService.getDictDataListByDictType("system_user_sex");
            assertThat(reloaded.stream()
                            .filter(d -> "1".equals(d.getValue()))
                            .findFirst()
                            .orElseThrow()
                            .getLabel())
                    .isEqualTo("缓存钉住");
        } finally {
            // 测试事务回滚只还数据库，Redis 缓存需显式清理，避免脏值污染其他测试
            dictDataCache.clear();
        }
    }

    private void verifyCacheTtlFallbacks() {
        Cache deptChildrenCache = cacheManager.getCache(RedisKeyConstants.DEPT_CHILDREN_ID_LIST);
        assertThat(deptChildrenCache).isNotNull();
        deptChildrenCache.clear();

        deptService.getChildDeptIdListFromCache(100L);
        // 键带 #30m TTL 兜底：即使业务忘记清理，缓存也会自动过期
        assertThat(stringRedisTemplate.getExpire("dept_children_ids:100", TimeUnit.SECONDS))
                .isPositive();
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

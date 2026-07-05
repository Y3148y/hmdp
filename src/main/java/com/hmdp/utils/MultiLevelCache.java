package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 多级缓存组件: Caffeine (L1 本地缓存) + Redis (L2 分布式缓存)
 * Cache-Aside 模式: 应用负责缓存管理, 缓存未命中时回源数据库
 */
@Slf4j
@Component
public class MultiLevelCache {

    private final Cache<String, String> localCache;
    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /** L1 Caffeine 最大容量 */
    private static final int L1_MAX_SIZE = 10_000;
    /** L1 Caffeine 写入后过期时间 (分钟), 短于 L2 以保证数据新鲜度 */
    private static final int L1_TTL_MINUTES = 5;

    public MultiLevelCache(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(L1_MAX_SIZE)
                .expireAfterWrite(L1_TTL_MINUTES, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /**
     * 写入多级缓存: 同时更新 L1 (Caffeine) 和 L2 (Redis 逻辑过期)
     */
    public void set(String key, Object value, Long redisTTL, TimeUnit unit) {
        String json = JSONUtil.toJsonStr(value);
        localCache.put(key, json);
        setWithLogicalExpire(key, value, redisTTL, unit);
    }

    /**
     * 多级缓存读取: L1 → L2 → DB, 逐级回填 (Cache-Aside)
     */
    public <R, ID> R query(String keyPrefix, ID id, Class<R> type,
                           Function<ID, R> dbFallback, Long redisTTL, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1. L1 Caffeine 本地缓存
        String l1Json = localCache.getIfPresent(key);
        if (StrUtil.isNotBlank(l1Json)) {
            return JSONUtil.toBean(l1Json, type);
        }
        if (l1Json != null) {
            return null;
        }

        // 2. L2 Redis 分布式缓存 (逻辑过期)
        R result = queryFromRedis(key, type, dbFallback, id, redisTTL, unit);

        // 3. 回填 L1
        if (result != null) {
            localCache.put(key, JSONUtil.toJsonStr(result));
        } else {
            localCache.put(key, "");
        }

        return result;
    }

    /**
     * 失效缓存: 同时删除 L1 和 L2
     */
    public void invalidate(String key) {
        localCache.invalidate(key);
        stringRedisTemplate.delete(key);
    }

    /**
     * 获取 Caffeine 统计信息 (命中率等)
     */
    public CacheStats getStats() {
        return localCache.stats();
    }

    // ==================== private helpers ====================

    private void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    private <R, ID> R queryFromRedis(String key, Class<R> type,
                                     Function<ID, R> dbFallback, ID id,
                                     Long redisTTL, TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            R r = dbFallback.apply(id);
            if (r != null) {
                setWithLogicalExpire(key, r, redisTTL, unit);
            }
            return r;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        if (expireTime == null || expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        // 逻辑过期, 异步重建
        boolean isLock = tryLock(key);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R fresh = dbFallback.apply(id);
                    setWithLogicalExpire(key, fresh, redisTTL, unit);
                } catch (Exception e) {
                    log.error("缓存重建失败 key={}", key, e);
                } finally {
                    unLock(key);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent("lock:" + key, "1", 10L, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete("lock:" + key);
    }
}

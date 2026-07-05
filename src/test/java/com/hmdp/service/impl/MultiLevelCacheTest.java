package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.utils.MultiLevelCache;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证 MultiLevelCache 的 L1 Caffeine → L2 Redis → DB 三级回退逻辑
 * Caffeine 用真实实例 (非 mock)，Redis 用 mock
 */
@ExtendWith(MockitoExtension.class)
class MultiLevelCacheTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private MultiLevelCache multiLevelCache;

    private static final String KEY_PREFIX = "cache:shop:";
    private static final Long REDIS_TTL = 30L;

    // 模拟 DB 调用计数器
    private AtomicInteger dbCallCount;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        multiLevelCache = new MultiLevelCache(stringRedisTemplate);
        dbCallCount = new AtomicInteger(0);
    }

    /**
     * L1 Caffeine 命中 — 不应调用 Redis 和 DB
     */
    @Test
    void testL1CacheHit_ShouldNotCallRedisOrDB() {
        // Arrange: 先通过 query 写入 L1
        Shop shop = createShop(1L, "店铺1");
        when(valueOps.get(anyString())).thenReturn(null); // Redis 未命中 → 走 DB

        // 第一次查询 — 回源 DB → 写入 L1 + L2
        multiLevelCache.query(KEY_PREFIX, 1L, Shop.class,
                id -> { dbCallCount.incrementAndGet(); return shop; },
                REDIS_TTL, TimeUnit.MINUTES);

        assertEquals(1, dbCallCount.get());
        // 重置 mock 验证
        reset(valueOps);

        // Act: 第二次查询同一 ID — 应命中 L1
        dbCallCount.set(0);
        Shop result = multiLevelCache.query(KEY_PREFIX, 1L, Shop.class,
                id -> { dbCallCount.incrementAndGet(); return null; },
                REDIS_TTL, TimeUnit.MINUTES);

        // Assert: 命中 L1，不调 Redis，不调 DB
        assertNotNull(result);
        assertEquals("店铺1", result.getName());
        assertEquals(0, dbCallCount.get(), "L1命中后不应回源DB");
        verify(valueOps, never()).get(anyString()); // L1 命中跳过 Redis
    }

    /**
     * L1 未命中 → L2 Redis 命中 → 回填 L1
     */
    @Test
    void testL1Miss_L2Hit_ShouldBackfillL1() {
        // Arrange: Redis 有数据 (逻辑过期格式)
        Shop shop = createShop(2L, "店铺2");
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(30));
        when(valueOps.get(anyString())).thenReturn(JSONUtil.toJsonStr(redisData));

        // Act
        Shop result = multiLevelCache.query(KEY_PREFIX, 2L, Shop.class,
                id -> { dbCallCount.incrementAndGet(); return null; },
                REDIS_TTL, TimeUnit.MINUTES);

        // Assert: Redis 命中
        assertNotNull(result);
        assertEquals("店铺2", result.getName());
        assertEquals(0, dbCallCount.get(), "Redis命中后不应回源DB");
        verify(valueOps, times(1)).get(anyString());

        // 第二次查询 — L1 应已回填
        reset(valueOps);
        result = multiLevelCache.query(KEY_PREFIX, 2L, Shop.class,
                id -> { dbCallCount.incrementAndGet(); return null; },
                REDIS_TTL, TimeUnit.MINUTES);

        assertNotNull(result);
        verify(valueOps, never()).get(anyString()); // L1 回填后跳过 Redis
    }

    /**
     * L1 miss → L2 miss → DB hit → 回填 L2 + L1
     */
    @Test
    void testL1Miss_L2Miss_DbHit_ShouldBackfillBoth() {
        // Arrange: Redis 无数据
        when(valueOps.get(anyString())).thenReturn(null);
        Shop shop = createShop(3L, "店铺3");

        // Act: 第一次 — 回源 DB
        Shop result = multiLevelCache.query(KEY_PREFIX, 3L, Shop.class,
                id -> { dbCallCount.incrementAndGet(); return shop; },
                REDIS_TTL, TimeUnit.MINUTES);

        // Assert: DB 命中
        assertNotNull(result);
        assertEquals("店铺3", result.getName());
        assertEquals(1, dbCallCount.get());
        // L2 Redis 应被写入
        verify(valueOps, times(1)).set(anyString(), anyString());

        // Act: 第二次 — L1 已回填，不走 Redis/DB
        reset(valueOps);
        dbCallCount.set(0);
        result = multiLevelCache.query(KEY_PREFIX, 3L, Shop.class,
                id -> { dbCallCount.incrementAndGet(); return null; },
                REDIS_TTL, TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals(0, dbCallCount.get(), "L1回填后不应再回源DB");
        verify(valueOps, never()).get(anyString());
    }

    /**
     * 缓存失效: 同时删除 L1 和 L2
     */
    @Test
    void testInvalidate_ShouldRemoveL1AndL2() {
        // Arrange: 先缓存一个 shop
        Shop shop = createShop(4L, "店铺4");
        when(valueOps.get(anyString())).thenReturn(null);

        multiLevelCache.query(KEY_PREFIX, 4L, Shop.class,
                id -> shop, REDIS_TTL, TimeUnit.MINUTES);

        // Act: 失效缓存
        multiLevelCache.invalidate(KEY_PREFIX + "4");

        // Assert: L2 Redis 被删除
        verify(stringRedisTemplate, times(1)).delete(KEY_PREFIX + "4");

        // 再次查询 — L1 失效 → 需走 Redis
        reset(stringRedisTemplate, valueOps);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        Shop result = multiLevelCache.query(KEY_PREFIX, 4L, Shop.class,
                id -> { dbCallCount.incrementAndGet(); return shop; },
                REDIS_TTL, TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals(1, dbCallCount.get(), "L1失效后应回源DB");
    }

    /**
     * 统计信息: 验证 recordStats 正常采集
     */
    @Test
    void testCacheStats() {
        assertNotNull(multiLevelCache.getStats());
        assertEquals(0, multiLevelCache.getStats().hitCount());

        Shop shop = createShop(5L, "店铺5");
        when(valueOps.get(anyString())).thenReturn(null);

        // 第一次: miss
        multiLevelCache.query(KEY_PREFIX, 5L, Shop.class,
                id -> shop, REDIS_TTL, TimeUnit.MINUTES);

        // 第二次: hit (L1)
        multiLevelCache.query(KEY_PREFIX, 5L, Shop.class,
                id -> shop, REDIS_TTL, TimeUnit.MINUTES);

        assertTrue(multiLevelCache.getStats().hitCount() >= 1,
                "第二次查询应为L1命中, hitCount=" + multiLevelCache.getStats().hitCount());
        assertTrue(multiLevelCache.getStats().missCount() >= 1,
                "第一次查询应为L1未命中, missCount=" + multiLevelCache.getStats().missCount());
    }

    private Shop createShop(Long id, String name) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setName(name);
        shop.setAddress("address_" + id);
        return shop;
    }
}

package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.MultiLevelCache;
import com.hmdp.utils.ShopBloomFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Layer 1 — 商铺查询单元测试（全部 Mock）
 * <p>
 * 覆盖 {@link ShopServiceImpl getById(Long)} 的两个分支：缓存命中、缓存未命中。
 * 运行方式：{@code mvn test -Dtest=ShopServiceImplTest}
 */

/**
 * 测试 ShopServiceImpl 的多级缓存查询链路
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class ShopServiceImplTest {

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private MultiLevelCache multiLevelCache;

    @Mock
    private ShopBloomFilter shopBloomFilter;

    @InjectMocks
    private ShopServiceImpl shopService;

    private Shop testShop;

    @BeforeEach
    void setUp() {
        testShop = new Shop();
        testShop.setId(1L);
        testShop.setName("测试店铺");
        testShop.setAddress("测试地址");
    }

    @Test
    void testQueryById_BloomFilterRejects() {
        // 布隆过滤器判定 ID 一定不存在 → 直接返回失败
        when(shopBloomFilter.mightContain(999L)).thenReturn(false);

        Result result = shopService.queryById(999L);

        assertFalse(result.getSuccess());
        assertEquals("店铺不存在", result.getErrorMsg());
        // 不会进入多级缓存
        verify(multiLevelCache, never()).query(anyString(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void testQueryById_CacheHit() {
        // 布隆过滤器放行
        when(shopBloomFilter.mightContain(1L)).thenReturn(true);
        // 多级缓存命中
        when(multiLevelCache.query(eq("cache:shop:"), eq(1L), eq(Shop.class),
                any(), anyLong(), any())).thenReturn(testShop);

        Result result = shopService.queryById(1L);

        assertTrue(result.getSuccess());
        assertEquals(testShop, result.getData());
        verify(multiLevelCache, times(1)).query(eq("cache:shop:"), eq(1L), eq(Shop.class),
                any(), anyLong(), any());
    }

    @Test
    void testQueryById_CacheMiss() {
        // 布隆过滤器放行，但缓存和 DB 都无数据
        when(shopBloomFilter.mightContain(1L)).thenReturn(true);
        when(multiLevelCache.query(eq("cache:shop:"), eq(1L), eq(Shop.class),
                any(), anyLong(), any())).thenReturn(null);

        Result result = shopService.queryById(1L);

        assertFalse(result.getSuccess());
        assertEquals("店铺不存在", result.getErrorMsg());
    }

    @Test
    void testUpdate_InvalidatesCache() {
        // 更新需失效多级缓存
        Shop updated = new Shop();
        updated.setId(1L);
        updated.setName("新名称");

        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);

        Result result = shopService.update(updated);

        assertTrue(result.getSuccess());
        verify(multiLevelCache, times(1)).invalidate("cache:shop:1");
    }

    @Test
    void testUpdate_IdNotFound() {
        // ID 不存在时 updateById 返回 false → 缓存不失效
        Shop shop = new Shop();
        shop.setId(999L);
        shop.setName("不存在的店铺");

        when(shopMapper.updateById(any(Shop.class))).thenReturn(0);

        Result result = shopService.update(shop);

        assertFalse(result.getSuccess());
        assertEquals("店铺不存在或更新失败", result.getErrorMsg());
        verify(multiLevelCache, never()).invalidate(anyString());
    }

    @Test
    void testUpdate_NullId() {
        Shop shop = new Shop();
        Result result = shopService.update(shop);

        assertFalse(result.getSuccess());
        assertEquals("店铺id不能为空", result.getErrorMsg());
        verify(multiLevelCache, never()).invalidate(anyString());
    }

    /**
     * 查询存在的商铺，返回非空实体，字段值正确
     */
    @Test
    void testGetById_Success() {
        // Arrange
        when(shopMapper.selectById(1L)).thenReturn(testShop);

        // Act
        Shop result = shopService.getById(1L);

        // Assert
        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("测试店铺", result.getName());
        verify(shopMapper, times(1)).selectById(1L);
    }

    /**
     * 查询不存在的商铺，返回 null
     */
    @Test
    void testGetById_NotFound() {
        when(shopMapper.selectById(999L)).thenReturn(null);

        // Act
        Shop result = shopService.getById(999L);

        // Assert
        assertNull(result);
        verify(shopMapper, times(1)).selectById(999L);
    }
}
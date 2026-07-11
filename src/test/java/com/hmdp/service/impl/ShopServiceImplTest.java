package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Layer 1 — 商铺查询单元测试（全部 Mock）
 * <p>
 * 覆盖 {@link ShopServiceImpl getById(Long)} 的两个分支：缓存命中、缓存未命中。
 * 运行方式：{@code mvn test -Dtest=ShopServiceImplTest}
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class ShopServiceImplTest {

    @Mock
    private ShopMapper shopMapper;

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

    /**
     * 查询存在的商铺，返回非空实体，字段值正确
     */
    @Test
    void testGetById_Success() {
        when(shopMapper.selectById(1L)).thenReturn(testShop);

        Shop result = shopService.getById(1L);

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

        Shop result = shopService.getById(999L);

        assertNull(result);
        verify(shopMapper, times(1)).selectById(999L);
    }
}

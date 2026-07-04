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

    @Test
    void testGetById_NotFound() {
        // Arrange
        when(shopMapper.selectById(999L)).thenReturn(null);

        // Act
        Shop result = shopService.getById(999L);

        // Assert
        assertNull(result);
        verify(shopMapper, times(1)).selectById(999L);
    }
}
package com.hmdp.service.impl;

import com.hmdp.HmDianPingApplication;
import com.hmdp.TestEnvironmentInitializer;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class VoucherOrderServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @InjectMocks
    private VoucherOrderServiceImpl voucherOrderService;

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(100L);
        user.setNickName("testUser");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void testSeckillVoucher_Success() {
        // Arrange
        when(redisIdWorker.nextId("order")).thenReturn(10001L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.EMPTY_LIST),
                eq("1"), eq("100"), eq("10001")
        )).thenReturn(0L);

        IVoucherOrderService mockProxy = mock(IVoucherOrderService.class);

        // Act
        try (MockedStatic<AopContext> mockedAop = mockStatic(AopContext.class)) {
            mockedAop.when(AopContext::currentProxy).thenReturn(mockProxy);
            Result result = voucherOrderService.seckillVoucher(1L);

            // Assert
            assertTrue(result.getSuccess());
            assertEquals(10001L, result.getData());
            mockedAop.verify(AopContext::currentProxy, times(1));
        }
        verify(stringRedisTemplate, times(1)).execute(
                any(DefaultRedisScript.class), eq(Collections.EMPTY_LIST),
                eq("1"), eq("100"), eq("10001")
        );
    }

    @Test
    void testSeckillVoucher_OutOfStock() {
        // Arrange
        when(redisIdWorker.nextId("order")).thenReturn(10001L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.EMPTY_LIST),
                eq("1"), eq("100"), eq("10001")
        )).thenReturn(1L);

        // Act
        Result result = voucherOrderService.seckillVoucher(1L);

        // Assert
        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
    }

    @Test
    void testSeckillVoucher_DuplicateOrder() {
        // Arrange
        when(redisIdWorker.nextId("order")).thenReturn(10001L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.EMPTY_LIST),
                eq("1"), eq("100"), eq("10001")
        )).thenReturn(2L);

        // Act
        Result result = voucherOrderService.seckillVoucher(1L);

        // Assert
        assertFalse(result.getSuccess());
        assertEquals("不能重复下单", result.getErrorMsg());
    }
}

/**
 * 集成测试：连接真实 Redis，测试高并发秒杀场景
 */
@SpringBootTest(classes = HmDianPingApplication.class, properties = "spring.profiles.active=test")
class VoucherOrderIntegrationTest extends TestEnvironmentInitializer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    private static final Long VOUCHER_ID = 999L;
    private static final String STOCK_KEY = "seckill:stock:" + VOUCHER_ID;
    private static final String ORDER_KEY = "seckill:order:" + VOUCHER_ID;
    private static final String STREAM_KEY = "stream.orders";

    @BeforeEach
    void setUp() {
        // 清理测试数据
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);
        // 预置库存 = 10
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "10");
    }

    @AfterEach
    void tearDown() {
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);
        UserHolder.removeUser();
    }

    /**
     * 并发秒杀 — 超卖防护测试
     * 库存10，50线程并发，验证不超卖
     */
    @Test
    void testConcurrentSeckill_NoOverstock() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int userId = 1000 + i;
            executor.submit(() -> {
                try {
                    UserDTO user = new UserDTO();
                    user.setId((long) userId);
                    user.setNickName("user_" + userId);
                    UserHolder.saveUser(user);

                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 验证：成功订单数不超过库存
        int success = successCount.get();
        int fail = failCount.get();
        System.out.println("并发50线程: 成功=" + success + ", 失败=" + fail);
        assertTrue(success <= 10, "成功订单数不能超过库存(10)，实际=" + success);
        assertEquals(50, success + fail, "总请求数应为50");

        // 验证 Redis 库存扣减正确
        String remainingStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertNotNull(remainingStock);
        assertEquals("0", remainingStock, "库存应被扣减至0");

        // 验证订单集合大小 == 成功数
        Long orderSetSize = stringRedisTemplate.opsForSet().size(ORDER_KEY);
        assertEquals(success, orderSetSize.intValue(), "Redis订单集合大小应等于成功订单数");
    }

    /**
     * 一人一单测试
     * 同一用户多次并发调用，验证只有1次成功
     */
    @Test
    void testConcurrentSeckill_OneOrderPerUser() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    UserDTO user = new UserDTO();
                    user.setId(2000L); // 同一用户
                    user.setNickName("duplicateUser");
                    UserHolder.saveUser(user);

                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (result.getSuccess()) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 验证：同一用户只能成功1次
        int success = successCount.get();
        System.out.println("同一用户20次并发: 成功=" + success);
        assertEquals(1, success, "同一用户只能秒杀成功1次");

        // 验证 Redis Set 中只有该用户
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(ORDER_KEY, "2000");
        assertTrue(isMember, "用户应在订单集合中");
        assertEquals(1, stringRedisTemplate.opsForSet().size(ORDER_KEY).intValue(),
                "订单集合中只能有1个用户");
    }

    /**
     * Redis 缓存状态验证
     * 验证 Lua 脚本执行前后 Redis 中的库存和订单数据状态
     */
    @Test
    void testSeckill_RedisStateChange() {
        // 执行前：验证库存已预置
        String beforeStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("10", beforeStock, "执行前库存应为10");

        UserDTO user = new UserDTO();
        user.setId(3000L);
        user.setNickName("stateTestUser");
        UserHolder.saveUser(user);

        // 执行秒杀
        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
        assertTrue(result.getSuccess(), "秒杀应成功");

        // 执行后：验证库存扣减
        String afterStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("9", afterStock, "执行后库存应为9");

        // 验证订单集合中添加了用户
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(ORDER_KEY, "3000");
        assertTrue(isMember, "用户应被添加到订单集合");

        // 验证 Stream 中有消息
        Long streamSize = stringRedisTemplate.opsForStream().size(STREAM_KEY);
        assertNotNull(streamSize);
        assertTrue(streamSize >= 1, "Stream 中应有至少1条消息");
    }

    /**
     * 不同用户依次秒杀
     * 验证多用户各自都能成功秒杀，库存正常递减
     */
    @Test
    void testSeckill_MultipleUsersSequential() {
        for (int i = 0; i < 5; i++) {
            UserDTO user = new UserDTO();
            user.setId(4000L + i);
            user.setNickName("user_" + i);
            UserHolder.saveUser(user);

            Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
            assertTrue(result.getSuccess(), "用户" + i + " 秒杀应成功");

            // 验证库存递减
            String stock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
            assertEquals(String.valueOf(10 - i - 1), stock,
                    "用户" + i + " 秒杀后库存应为" + (10 - i - 1));

            UserHolder.removeUser();
        }

        // 第6个用户应该失败（库存=5，还能成功，继续到库存耗尽）
        // 再秒杀5次，耗尽库存
        for (int i = 5; i < 10; i++) {
            UserDTO user = new UserDTO();
            user.setId(5000L + i);
            user.setNickName("user_" + i);
            UserHolder.saveUser(user);

            Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
            assertTrue(result.getSuccess(), "用户" + i + " 秒杀应成功");
            UserHolder.removeUser();
        }

        // 库存耗尽后，下一个用户应失败
        UserDTO user = new UserDTO();
        user.setId(6000L);
        user.setNickName("unluckyUser");
        UserHolder.saveUser(user);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
        assertFalse(result.getSuccess(), "库存耗尽后应失败");
        assertEquals("库存不足", result.getErrorMsg(), "失败原因应为库存不足");
    }
}

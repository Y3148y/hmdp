package com.hmdp.service.impl;

import com.hmdp.HmDianPingApplication;
import com.hmdp.TestEnvironmentInitializer;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
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
 * 集成测试：连接真实 Redis (db1) + MySQL (hmdp_test)，验证秒杀完整链路。
 * <p>
 * 测试链路：seckillVoucher() → Lua脚本(Redis原子操作) → XADD写入Stream
 * → VoucherOderHandler后台线程消费 → createVoucherOrder()写入MySQL
 */
@SpringBootTest(classes = HmDianPingApplication.class, properties = "spring.profiles.active=test")
class VoucherOrderIntegrationTest extends TestEnvironmentInitializer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long VOUCHER_ID = 999L;
    private static final String STOCK_KEY = "seckill:stock:" + VOUCHER_ID;
    private static final String ORDER_KEY = "seckill:order:" + VOUCHER_ID;
    private static final String STREAM_KEY = "stream.orders";
    private static final String GROUP_NAME = "g1";

    // ======================== 环境准备 ========================

    @BeforeEach
    void setUp() {
        // 1. 清理 Redis 测试残留
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);

        // 2. 重建消费者组（必须先于任何 XREADGROUP 调用存在）
        ensureConsumerGroup();

        stringRedisTemplate.opsForValue().set(STOCK_KEY, "10");

        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher (voucher_id, stock) VALUES (?, ?)",
                VOUCHER_ID, 10
        );

    }

    @AfterEach
    void tearDown() {
        // Redis 清理
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);
        UserHolder.removeUser();

        // DB 清理：删掉本次测试写入的数据，不留残留
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
    }

    /**
     * 确保 Redis Stream 消费者组 g1 存在。
     * <p>
     * 使用 XTRIM MAXLEN 0 清空 Stream 消息（而非 DELETE 再重建），
     * 以保证 Stream 和消费者组持续存在，后台线程 VoucherOderHandler 不会遇到 NOGROUP。
     * <p>
     * 使用原生 Redis 命令是因为 Spring Data Redis 2.6 的 createGroup() 不支持 MKSTREAM
     * 参数（该重载在后续版本才加入）。
     */
    private void ensureConsumerGroup() {
        // 清空 Stream 中的旧消息，但不删除 Stream 本身（避免 NOGROUP）
        try {
            stringRedisTemplate.opsForStream().trim(STREAM_KEY, 0);
        } catch (Exception e) {
            // Stream 不存在时 trim 会失败，此时用 MKSTREAM 创建
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.execute("XGROUP", "CREATE".getBytes(),
                        STREAM_KEY.getBytes(), GROUP_NAME.getBytes(),
                        "0".getBytes(), "MKSTREAM".getBytes());
                return null;
            });
        }
    }


    /**
     * 等待后台线程消费完 Stream 消息，然后断言 tb_voucher_order 写入行数。
     * <p>
     * seckillVoucher() 返回 Result.ok 时仅代表 Lua 脚本（Redis 侧）执行成功；
     * 真正的 MySQL 写入发生在后台线程 VoucherOderHandler 中，是异步的。
     * 此方法轮询 DB 等待异步写入完成，超时 10s。
     */
    /**
     * 轮询等待后台线程异步消费 Stream → 写入 MySQL，然后断言订单数。
     */
    private void assertDbOrderCount(int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        int count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                    Integer.class, VOUCHER_ID
            );
            if (count >= expectedCount) break;
            Thread.sleep(300);
        }
        assertEquals(expectedCount, count,
                "DB 写入订单数应与 Redis 成功数一致（后台线程异步消费 Stream 后写入 MySQL）");
    }

    // ======================== 测试用例 ========================

    /**
     * 并发秒杀 — 超卖防护 + DB 写入验证
     * 库存10，50线程并发。验证：
     * 1. Redis 层不超卖
     * 2. 后台线程正确消费 Stream 并写入 MySQL
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

        int success = successCount.get();
        int fail = failCount.get();
        System.out.println("并发50线程: 成功=" + success + ", 失败=" + fail);

        // -------- Redis 层断言 --------
        assertTrue(success <= 10, "成功订单数不能超过库存(10)，实际=" + success);
        assertEquals(50, success + fail, "总请求数应为50");

        String remainingStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertNotNull(remainingStock);
        assertEquals("0", remainingStock, "Redis库存应被扣减至0");

        Long orderSetSize = stringRedisTemplate.opsForSet().size(ORDER_KEY);
        assertEquals(success, orderSetSize.intValue(), "Redis订单集合大小应等于成功订单数");

        // -------- MySQL 层断言（异步消费后验证 DB 写入）--------
        assertDbOrderCount(success);
    }

    /**
     * 一人一单测试 —— 同一用户并发20次，仅允许1次成功。
     * 验证 Redis Set 去重 + DB 层面仅写入1条。
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
                    // 所有线程使用同一个用户 ID，测试 Lua 脚本的 sismember 去重
                    UserDTO user = new UserDTO();
                    user.setId(2000L);
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

        int success = successCount.get();
        System.out.println("同一用户20次并发: 成功=" + success);
        assertEquals(1, success, "同一用户只能秒杀成功1次");

        // Redis Set 中仅该用户一人
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(ORDER_KEY, "2000");
        assertTrue(isMember, "用户应在订单集合中");
        assertEquals(1, stringRedisTemplate.opsForSet().size(ORDER_KEY).intValue(),
                "订单集合中应只有1个用户");

        // DB 中仅该用户一条记录
        int dbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE user_id = ? AND voucher_id = ?",
                Integer.class, 2000L, VOUCHER_ID
        );
        assertEquals(1, dbCount, "DB 中同一用户只能有1条订单");
    }

    /**
     * Redis 状态变化 + Stream 消息 + DB 写入完整链路验证。
     * 单次秒杀，验证 Redis Lua 前后状态变化 + Stream 落盘 + MySQL 写入。
     */
    @Test
    void testSeckill_RedisStateChange() throws InterruptedException {
        // 执行前：库存 = 10
        String beforeStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("10", beforeStock, "执行前库存应为10");

        UserDTO user = new UserDTO();
        user.setId(3000L);
        user.setNickName("stateTestUser");
        UserHolder.saveUser(user);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
        assertTrue(result.getSuccess(), "秒杀应成功");

        // 执行后：Redis 库存 -1
        String afterStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("9", afterStock, "执行后库存应为9");

        // Redis Set 记录已购买用户
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(ORDER_KEY, "3000");
        assertTrue(isMember, "用户应被添加到订单集合");

        // Stream 中有 1 条待消费消息
        Long streamSize = stringRedisTemplate.opsForStream().size(STREAM_KEY);
        assertNotNull(streamSize);
        assertTrue(streamSize >= 1, "Stream 中应有至少1条消息");

        // 等待异步消费 → 验证 DB 写入
        assertDbOrderCount(1);

        // 消费后 Stream 消息应被 ACK，size 归零
        // （注意：消息被 ACK 后仍统计在 Stream 长度中，需用 XPENDING 确认已消费。
        //  这里仅验证 size 不再增长，实际 ACK 由后台线程完成）
    }

    /**
     * 多用户依次秒杀 —— 库存 10 → 0 的完整递减过程。
     * 验证每个用户秒杀后 Redis 库存递减 + 库存耗尽后拒绝新请求 + DB 最终有 10 条订单。
     */
    @Test
    void testSeckill_MultipleUsersSequential() throws InterruptedException {
        // 前 10 个用户各成功 1 次
        for (int i = 0; i < 10; i++) {
            UserDTO user = new UserDTO();
            user.setId(4000L + i);
            user.setNickName("user_" + i);
            UserHolder.saveUser(user);

            Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
            assertTrue(result.getSuccess(), "用户" + i + " 秒杀应成功");

            String stock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
            assertEquals(String.valueOf(10 - i - 1), stock,
                    "用户" + i + " 秒杀后库存应为" + (10 - i - 1));

            UserHolder.removeUser();
        }

        // 库存耗尽，第 11 个用户应失败
        UserDTO user = new UserDTO();
        user.setId(5000L);
        user.setNickName("unluckyUser");
        UserHolder.saveUser(user);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
        assertFalse(result.getSuccess(), "库存耗尽后应失败");
        assertEquals("库存不足", result.getErrorMsg());

        // Redis 库存归零
        String stock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("0", stock, "最终库存应为0");

        // 等待异步消费 → 验证 DB 恰好写入 10 条
        assertDbOrderCount(10);
    }
}

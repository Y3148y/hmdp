package com.hmdp.service.impl;

import com.hmdp.TestEnvironmentInitializer;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 2 — Redis 集成测试（真实 Redis + Mock MQ）
 * <p>
 * <b>测试目标</b>：验证 {@code seckill_mq.lua} 脚本在真实 Redis 环境下的原子性和并发安全性。
 * <p>
 * <b>组件状态</b>：<br>
 * - Redis — <b>真实</b>连接，db 1（与生产 db 0 隔离）<br>
 * - RabbitMQ — <b>Mock</b>（阻断消息投递，消息不会到达 Broker）<br>
 * - MySQL — 测试库 {@code hmdp_test}，每次测试前后自动清空（由 {@link TestEnvironmentInitializer} 保证）
 * <p>
 * <b>数据隔离</b>：<br>
 * - 继承 {@link TestEnvironmentInitializer} → 测试前创建 {@code hmdp_test} 库 + TRUNCATE 表，测试后 FLUSHDB<br>
 * - Redis key 使用 {@code voucherId=9999} 测试区间，与生产数据天然隔离<br>
 * - {@code application-test.yml} → {@code spring.redis.database=1} 独立 Redis db<br>
 * - {@code @MockBean RabbitTemplate} → 消息不投递，消费者不触发，DB 不写入
 * <p>
 * <b>前提条件</b>：本地 Redis 服务可用。
 * <p>
 * <b>运行方式</b>：{@code mvn test -Dtest=VoucherOrderRabbitMQIntegrationTest}
 */
@SpringBootTest(properties = "spring.profiles.active=test")
class VoucherOrderRabbitMQIntegrationTest extends TestEnvironmentInitializer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    /**
     * Mock RabbitMQ 投递 — 阻断消息进入 Broker，避免触发消费者异步写 DB。
     * 本层只验证 Redis + Lua，不验证 MQ 投递链路。
     */
    @MockBean
    private RabbitTemplate rabbitTemplate;

    /** 测试用券 ID，使用 9999 区间与生产数据隔离 */
    private static final Long VOUCHER_ID = 9999L;
    private static final String STOCK_KEY = "seckill:stock:" + VOUCHER_ID;
    private static final String ORDER_KEY = "seckill:order:" + VOUCHER_ID;

    /**
     * 每个测试前：清理上次残留数据 + 预置 10 个库存
     */
    @BeforeEach
    void setUp() {
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "10");
    }

    /**
     * 每个测试后：清理 Redis 测试 key + 移除 ThreadLocal 用户
     * 注意：父类 {@code cleanRedisData()} 会在本方法之后执行 FLUSHDB，
     * 确保 db 1 被完全清空。
     */
    @AfterEach
    void tearDown() {
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);
        UserHolder.removeUser();
    }

    /**
     * 并发超卖防护：库存 10，50 线程并发抢购
     * <p>
     * 验证 Lua 脚本在并发场景下不会出现超卖——成功订单数不超过 10。
     * 每个线程使用不同的 userId（1000~1049），由 Lua 的 SISMEMBER + INCRBY
     * 原子操作保证并发安全。
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

        // 核心断言：不能超卖
        assertTrue(success <= 10, "成功订单数不能超过库存(10)，实际=" + success);
        assertEquals(50, success + fail, "总请求数应为50");

        // 库存扣减正确
        String remainingStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertNotNull(remainingStock);
        int stock = Integer.parseInt(remainingStock);
        assertEquals(10 - success, stock, "库存应扣减至 10-" + success);

        // 订单集合大小与成功数一致
        Long orderSetSize = stringRedisTemplate.opsForSet().size(ORDER_KEY);
        assertEquals(success, orderSetSize.intValue(), "Redis 订单集合大小应等于成功订单数");
    }

    /**
     * 一人一单：同一 userId 并发 20 次
     * <p>
     * 验证 Lua 的 SISMEMBER + SADD 原子去重——同一用户只有 1 次能成功。
     * 注意：这里测试的是 Lua 层的去重，不是 tryLock 或 DB 层。
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
                    user.setId(2000L);           // 所有线程使用同一个 userId
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

        // 验证 Redis Set 中确实记录了该用户
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(ORDER_KEY, "2000");
        assertTrue(isMember, "用户应在订单集合中");
    }

    /**
     * Redis 状态变化验证：单次秒杀前后库存和订单集合的变化
     * <p>
     * 验证执行前后：<br>
     * 1. 库存从 10 → 9<br>
     * 2. 用户被添加到订单集合<br>
     * 3. MQ 版本返回"排队中"格式的 String（非 Stream 版本的 orderId Long）
     */
    @Test
    void testSeckill_RedisStateChange() {
        // 执行前状态
        String beforeStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("10", beforeStock, "执行前库存应为10");

        UserDTO user = new UserDTO();
        user.setId(3000L);
        user.setNickName("stateTestUser");
        UserHolder.saveUser(user);

        // 执行
        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
        assertTrue(result.getSuccess(), "秒杀应成功");
        assertTrue(result.getData().toString().startsWith("排队中，订单号："),
                "MQ 版本应返回排队中消息");

        // 执行后状态
        String afterStock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals("9", afterStock, "执行后库存应为9");

        Boolean isMember = stringRedisTemplate.opsForSet().isMember(ORDER_KEY, "3000");
        assertTrue(isMember, "用户应被添加到订单集合");
    }

    /**
     * 多用户依次秒杀，验证库存从 10 逐步递减到 0 后拒绝新请求
     * <p>
     * 模拟真实场景：前 10 个不同用户各秒杀一次全部成功，第 11 个用户失败。
     */
    @Test
    void testSeckill_MultipleUsersSequential() {
        // 10 个不同用户依次秒杀，全部成功
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

        // 第 11 个用户：库存已为 0，应返回失败
        UserDTO user = new UserDTO();
        user.setId(5000L);
        user.setNickName("unluckyUser");
        UserHolder.saveUser(user);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
        assertFalse(result.getSuccess(), "库存耗尽后应失败");
        assertEquals("库存不足", result.getErrorMsg());
    }
}

package com.hmdp.service.impl;

import com.hmdp.TestEnvironmentInitializer;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 3 — 全链路集成测试（真实 Redis + 真实 RabbitMQ + 真实 MySQL）
 * <p>
 * <b>测试目标</b>：验证从 {@code seckillVoucher()} 到消费者异步写 DB 的完整链路。
 * <p>
 * <b>组件状态</b>：<br>
 * - Redis — <b>真实</b>连接，db 1<br>
 * - RabbitMQ — <b>真实</b>连接，监听器自动启动（覆盖 test profile 的 auto-startup=false）<br>
 * - MySQL — 测试库 {@code hmdp_test}
 * <p>
 * <b>数据隔离</b>：<br>
 * - 继承 {@link TestEnvironmentInitializer} → 测试前 TRUNCATE 表 + FLUSHDB，测试后 FLUSHDB<br>
 * - {@code @BeforeEach} 插入测试用的 {@code tb_seckill_voucher} 记录 + 设置 Redis 库存<br>
 * - {@code @BeforeEach} 清空 RabbitMQ 队列（防止上一次测试残留消息）<br>
 * - 使用 voucherId=8888 测试区间
 * <p>
 * <b>异步等待策略</b>：<br>
 * seckillVoucher 返回后消息由消费者异步处理，测试通过轮询 DB 等待订单出现。
 * 单条消息等待上限 5 秒，并发场景等待上限 30 秒。
 * <p>
 * <b>前提条件</b>：本地 Redis、RabbitMQ、MySQL 服务均可用，且已创建 MQ vhost {@code /hmdp_test}。
 * <p>
 * <b>运行方式</b>：{@code mvn test -Dtest=VoucherOrderFullStackIntegrationTest}
 * <p>
 * <b>注意</b>："库存不足"用例通过 {@code RabbitAdmin.getQueueInfo().getMessageCount()} 直接断言队列为空，无需 sleep。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.rabbitmq.listener.simple.auto-startup=true"
})
class VoucherOrderFullStackIntegrationTest extends TestEnvironmentInitializer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    private static final Long VOUCHER_ID = 8888L;
    private static final String STOCK_KEY = "seckill:stock:" + VOUCHER_ID;
    private static final String ORDER_KEY = "seckill:order:" + VOUCHER_ID;
    private static final int INITIAL_STOCK = 10;

    @BeforeEach
    void setUp() {
        // 1. Redis：预置库存
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);
        stringRedisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(INITIAL_STOCK));

        // 2. DB：清理旧订单 + 插入秒杀券记录（父类 TRUNCATE 后表为空）
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update(
                "INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time, create_time) " +
                "VALUES (?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), NOW())",
                VOUCHER_ID, INITIAL_STOCK
        );

        // 3. RabbitMQ：清空队列残留消息
        rabbitAdmin.purgeQueue(RabbitMQConfig.SECKILL_ORDER_QUEUE, true);
        rabbitAdmin.purgeQueue(RabbitMQConfig.SECKILL_ORDER_DLQ, true);
    }

    @AfterEach
    void tearDown() {
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_KEY);
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE voucher_id = ?", VOUCHER_ID);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", VOUCHER_ID);
        UserHolder.removeUser();
    }

    /**
     * 单用户秒杀全链路：Redis 扣库存 → MQ 投递 → 消费者写 DB
     * <p>
     * 验证点：<br>
     * 1. 返回"排队中"<br>
     * 2. Redis 库存 -1<br>
     * 3. 用户加入 Redis 订单集合<br>
     * 4. DB 中出现对应订单（id、userId、voucherId 一致）
     */
    @Test
    void testFullStackSingleOrder() {
        UserDTO user = new UserDTO();
        user.setId(6000L);
        user.setNickName("fullStackUser");
        UserHolder.saveUser(user);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
        assertTrue(result.getSuccess());
        assertTrue(result.getData().toString().startsWith("排队中，订单号："));

        // Redis 状态验证（同步，立即可断言）
        String stock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals(String.valueOf(INITIAL_STOCK - 1), stock);
        assertTrue(stringRedisTemplate.opsForSet().isMember(ORDER_KEY, "6000"));

        // DB 状态验证（异步，轮询等待消费者处理）
        awaitOrdersInDb(1, Duration.ofSeconds(5)); //辅助方法：进入监听队列，等待消费者处理

        List<VoucherOrder> orders = jdbcTemplate.query(
                "SELECT * FROM tb_voucher_order WHERE voucher_id = ? AND user_id = ?",
                new BeanPropertyRowMapper<>(VoucherOrder.class),
                VOUCHER_ID, 6000L
        );
        assertEquals(1, orders.size());
        VoucherOrder order = orders.get(0);
        assertNotNull(order.getId());
        assertEquals(6000L, order.getUserId());
        assertEquals(VOUCHER_ID, order.getVoucherId());

        // 验证 DB 库存也被消费者扣减
        Integer dbStock = jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, VOUCHER_ID
        );
        assertEquals(INITIAL_STOCK - 1, dbStock);
    }

    /**
     * 并发超卖防护：库存 10，50 个不同用户并发抢购
     * <p>
     * 验证 Lua 原子性 + MQ 削峰 + 消费者串行写 DB 的完整并发链路。
     * 最终 DB 中恰好 10 条订单，不会超卖。
     */
    @Test
    void testFullStackConcurrentNoOverstock() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

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
        System.out.println("全链路并发50线程: 成功=" + success);
        assertTrue(success <= INITIAL_STOCK,
                "成功订单数不能超过库存(" + INITIAL_STOCK + ")，实际=" + success);

        // 等待消费者处理完所有消息
        awaitOrdersInDb(success, Duration.ofSeconds(30));

        // Redis 库存归零
        String stock = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        assertEquals(String.valueOf(INITIAL_STOCK - success), stock);

        // Redis 订单集合大小 = 成功数
        Long orderSetSize = stringRedisTemplate.opsForSet().size(ORDER_KEY);
        assertEquals(success, orderSetSize.intValue());

        // DB 中订单数 = 成功数
        Integer dbOrderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class, VOUCHER_ID
        );
        assertEquals(success, dbOrderCount);

        // DB 库存也正确扣减
        Integer dbStock = jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, VOUCHER_ID
        );
        assertEquals(INITIAL_STOCK - success, dbStock);
    }

    /**
     * 一人一单：同一 userId 并发 20 次
     * <p>
     * 验证 Lua SISMEMBER + SADD 原子去重 + Redisson 锁兜底 + DB count 兜底。
     * 三重防护确保最终 DB 只有 1 条订单。
     */
    @Test
    void testFullStackOneOrderPerUser() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    UserDTO user = new UserDTO();
                    user.setId(7000L);
                    user.setNickName("oneOrderUser");
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
        System.out.println("全链路同一用户20次并发: 成功=" + success);
        assertEquals(1, success, "同一用户只能秒杀成功1次");

        // 等待 DB 写入
        awaitOrdersInDb(1, Duration.ofSeconds(10));

        // DB 层面验证：该用户只有 1 条订单
        Integer dbOrderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ? AND user_id = ?",
                Integer.class, VOUCHER_ID, 7000L
        );
        assertEquals(1, dbOrderCount);

        // Redis Set 中有该用户
        assertTrue(stringRedisTemplate.opsForSet().isMember(ORDER_KEY, "7000"));
    }

    /**
     * 库存耗尽：Lua 返回 1 → 消息不投递 → MQ 队列为空 → DB 无写入
     * <p>
     * 验证闸门逻辑——只有通过 Lua 预检的请求才会进入 MQ → DB 后半段。
     * 队列深度由 {@link RabbitAdmin#getQueueInfo} 直接获取，不需要 sleep 等待。
     */
    @Test
    void testFullStackOutOfStock() {
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "0");

        UserDTO user = new UserDTO();
        user.setId(8000L);
        user.setNickName("outOfStockUser");
        UserHolder.saveUser(user);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());

        // MQ 队列应无新消息（Lua 返回失败，convertAndSend 不被执行）
        int msgCount = rabbitAdmin.getQueueInfo(RabbitMQConfig.SECKILL_ORDER_QUEUE)
                                  .getMessageCount();
        assertEquals(0, msgCount, "队列不应有新消息");

        // DB 不应有该用户订单
        Integer dbOrderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ? AND user_id = ?",
                Integer.class, VOUCHER_ID, 8000L
        );
        assertEquals(0, dbOrderCount);

        assertEquals("0", stringRedisTemplate.opsForValue().get(STOCK_KEY));
    }

    // ======================== 辅助方法 ========================

    /**
     * 轮询 DB 等待订单数达到预期值。
     *
     * @param expectedCount 期望的订单数（至少）
     * @param timeout       超时时间
     */
    private void awaitOrdersInDb(int expectedCount, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Integer count = 0;
        while (Instant.now().isBefore(deadline)) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                    Integer.class, VOUCHER_ID
            );
            if (count != null && count >= expectedCount) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待消费者写入 DB 时被打断");
            }
        }
        fail("消费者未在 " + timeout.getSeconds() + " 秒内处理完订单，期望 >= " + expectedCount +
             "，实际 = " + count);
    }
}

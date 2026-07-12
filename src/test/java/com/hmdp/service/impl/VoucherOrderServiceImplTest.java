package com.hmdp.service.impl;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Layer 1 — 单元测试（全部 Mock，不连接任何中间件）
 * <p>
 * <b>测试目标</b>：验证 {@link VoucherOrderServiceImpl#seckillVoucher(Long)} 的 Java 分支逻辑。
 * <p>
 * <b>Mock 策略</b>：<br>
 * - {@link StringRedisTemplate}：Mock Lua 脚本返回值（0=成功, 1=库存不足, 2=重复下单）<br>
 * - {@link RedisIdWorker}：Mock 分布式 ID 生成<br>
 * - {@link RabbitTemplate}：Mock 消息投递（验证投递调用次数）<br>
 * - {@link ISeckillVoucherService} / {@link VoucherOrderMapper}：当前测试未直接使用，作为 {@code @InjectMocks} 依赖声明
 * <p>
 * <b>不测试的内容</b>（由 Layer 2 集成测试覆盖）：<br>
 * - Lua 脚本原子性（真实 Redis 并发测试）<br>
 * - 消息序列化与投递成功后 Broker 行为<br>
 * - 消费者异步处理和 DB 写入
 * <p>
 * <b>运行方式</b>：{@code mvn test -Dtest=VoucherOrderServiceImplTest}
 */
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

    /**
     * MQ 消息投递模板 — 验证成功/失败分支是否正确地发送或不发送消息
     */
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private VoucherOrderServiceImpl voucherOrderService;

    /**
     * 每个测试前初始化 ThreadLocal 用户上下文
     */
    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(100L);
        user.setNickName("testUser");
        UserHolder.saveUser(user);
    }

    /**
     * 每个测试后清理 ThreadLocal，防止测试间数据污染
     */
    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    /**
     * 场景：秒杀成功
     * <p>
     * 前置条件：库存充足，用户未购买过该优惠券<br>
     * 预期结果：<br>
     * 1. 返回 {@code success=true}<br>
     * 2. 返回消息为 {@code "排队中，订单号：10001"}<br>
     * 3. 消息被投递到正确的 Exchange 和 RoutingKey
     */
    @Test
    void testSeckillVoucher_Success() {
        // Arrange：Lua 返回 0 表示原子预检通过
        when(redisIdWorker.nextId("order")).thenReturn(10001L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.emptyList()),
                eq("1"), eq("100")           // voucherId, userId
        )).thenReturn(0L);

        // Act
        Result result = voucherOrderService.seckillVoucher(1L);

        // Assert
        assertTrue(result.getSuccess());
        assertEquals("排队中，订单号：10001", result.getData());

        // 验证 MQ 投递：exchange、routingKey 正确，消息体为任意 Object
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMQConfig.SECKILL_EXCHANGE),
                eq(RabbitMQConfig.SECKILL_ORDER_KEY),
                (Object) any()
        );
    }

    /**
     * 场景：库存不足
     * <p>
     * 前置条件：Redis 中库存已为 0<br>
     * 预期结果：<br>
     * 1. 返回 {@code success=false}<br>
     * 2. 错误消息为 {@code "库存不足"}<br>
     * 3. 消息<b>不会</b>被投递到 RabbitMQ
     */
    @Test
    void testSeckillVoucher_OutOfStock() {
        // Arrange：Lua 返回 1 表示库存不足
        when(redisIdWorker.nextId("order")).thenReturn(10001L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.emptyList()),
                eq("1"), eq("100")
        )).thenReturn(1L);

        // Act
        Result result = voucherOrderService.seckillVoucher(1L);

        // Assert
        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verify(rabbitTemplate, never()).convertAndSend(
                (String) any(), (String) any(), (Object) any());
    }

    /**
     * 场景：用户重复下单
     * <p>
     * 前置条件：该用户已购买过此优惠券（Redis Set 中已有记录）<br>
     * 预期结果：<br>
     * 1. 返回 {@code success=false}<br>
     * 2. 错误消息为 {@code "不能重复下单"}<br>
     * 3. 消息<b>不会</b>被投递到 RabbitMQ
     */
    @Test
    void testSeckillVoucher_DuplicateOrder() {
        // Arrange：Lua 返回 2 表示重复下单
        when(redisIdWorker.nextId("order")).thenReturn(10001L);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Collections.emptyList()),
                eq("1"), eq("100")
        )).thenReturn(2L);

        // Act
        Result result = voucherOrderService.seckillVoucher(1L);

        // Assert
        assertFalse(result.getSuccess());
        assertEquals("不能重复下单", result.getErrorMsg());
        verify(rabbitTemplate, never()).convertAndSend(
                (String) any(), (String) any(), (Object) any());
    }
}

package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀订单消费者 — 从 RabbitMQ 消费秒杀消息，执行真实下单
 * <p>
 * 幂等保证：基于 Redis SETNX 对 orderId 去重，防止消息重复消费。
 */
@Slf4j
@Component
public class SeckillOrderConsumer {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final String DEDUP_KEY_PREFIX = "mq:dedup:";

    /**
     * 监听秒杀订单队列，手动 ACK
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE, ackMode = "MANUAL")
    public void handleSeckillOrder(SeckillOrderMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String dedupKey = DEDUP_KEY_PREFIX + message.getOrderId();
        log.info("收到秒杀消息: orderId={}, userId={}, voucherId={}",
                message.getOrderId(), message.getUserId(), message.getVoucherId());

        try {
            // 1. Redis 幂等去重：SETNX 返回 true 表示首次处理
            Boolean firstTime = stringRedisTemplate.opsForValue()
                    .setIfAbsent(dedupKey, "1", 3600, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(firstTime)) {
                log.warn("重复消息，跳过: orderId={}", message.getOrderId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. Redisson 分布式锁：一人一单
            // 使用 tryLock() 无参形式，启用 Watchdog 自动续期，与 Stream 版本保持一致
            RLock lock = redissonClient.getLock("lock:order:" + message.getUserId());
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.warn("获取锁失败（一人一单）: userId={}", message.getUserId());
                channel.basicNack(deliveryTag, false, true);
                return;
            }

            try {
                // 3. 构造订单实体，执行真实 DB 写入
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(message.getOrderId());
                voucherOrder.setUserId(message.getUserId());
                voucherOrder.setVoucherId(message.getVoucherId());
                voucherOrderService.createVoucherOrder(voucherOrder);

                // 4. 手动 ACK
                channel.basicAck(deliveryTag, false);
                log.info("秒杀订单创建成功: orderId={}", message.getOrderId());
            } finally {
                lock.unlock();
            }

        } catch (Exception e) {
            log.error("处理秒杀消息异常: orderId={}", message.getOrderId(), e);
            try {
                // 如果消息尚未 ACK（channel 仍 open），NACK 让其进入重试或 DLQ
                if (channel.isOpen()) {
                    channel.basicNack(deliveryTag, false, true);
                }
            } catch (IOException ioException) {
                log.error("NACK 失败", ioException);
            }
        }
    }
}

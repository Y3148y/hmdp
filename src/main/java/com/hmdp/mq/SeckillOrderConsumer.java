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
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 秒杀订单消费者 — 从 RabbitMQ 消费秒杀消息，执行真实下单
 */
@Slf4j
@Component
public class SeckillOrderConsumer {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 监听秒杀订单队列，手动 ACK
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE, ackMode = "MANUAL")
    public void handleSeckillOrder(SeckillOrderMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("收到秒杀消息: orderId={}, userId={}, voucherId={}",
                message.getOrderId(), message.getUserId(), message.getVoucherId());

        try {
            // 1. Redisson 分布式锁：一人一单（兜底）
            RLock lock = redissonClient.getLock("lock:order:" + message.getUserId());
            boolean isLock = lock.tryLock();
            if (!isLock) {
                log.warn("获取锁失败（一人一单）: userId={}", message.getUserId());
                channel.basicNack(deliveryTag, false, true);
                return;
            }

            try {
                // 2. 构造订单实体，执行真实 DB 写入
                VoucherOrder voucherOrder = new VoucherOrder();
                voucherOrder.setId(message.getOrderId());
                voucherOrder.setUserId(message.getUserId());
                voucherOrder.setVoucherId(message.getVoucherId());
                voucherOrderService.createVoucherOrder(voucherOrder);

                // 3. 手动 ACK
                channel.basicAck(deliveryTag, false);
                log.info("秒杀订单创建成功: orderId={}", message.getOrderId());
            } finally {
                lock.unlock();
            }

        } catch (Exception e) {
            log.error("处理秒杀消息异常: orderId={}", message.getOrderId(), e);
            try {
                if (channel.isOpen()) {
                    channel.basicNack(deliveryTag, false, true);
                }
            } catch (IOException ioException) {
                log.error("NACK 失败", ioException);
            }
        }
    }
}

package com.hmdp.mq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.SeckillOrderMessage;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 死信队列消费者 — 处理超时未支付的秒杀订单（自动关单 + 回滚库存）
 * <p>
 * 触发条件：消息在 seckill.order.queue 中存活超过 15 分钟（TTL 过期），
 * RabbitMQ 自动将其路由到 seckill.order.dlq。
 */
@Slf4j
@Component
public class SeckillOrderDlxConsumer {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 监听死信队列，回滚 Redis 库存与去重标记
     */
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_DLQ, ackMode = "MANUAL")
    public void handleDlxOrder(SeckillOrderMessage message, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        log.info("收到死信消息（超时未支付关单）: orderId={}, userId={}, voucherId={}",
                message.getOrderId(), message.getUserId(), message.getVoucherId());

        try {
            // 1. 回滚库存：stock +1
            String stockKey = "seckill:stock:" + message.getVoucherId();
            stringRedisTemplate.opsForValue().increment(stockKey, 1);

            // 2. 从订单集合中移除用户（允许用户重新抢购）
            String orderKey = "seckill:order:" + message.getVoucherId();
            stringRedisTemplate.opsForSet().remove(orderKey, message.getUserId().toString());

            // 3. 删除幂等标记（允许同一 orderId 重新入队）
            String dedupKey = "mq:dedup:" + message.getOrderId();
            stringRedisTemplate.delete(dedupKey);

            channel.basicAck(deliveryTag, false);
            log.info("超时关单成功，库存已回滚: orderId={}, voucherId={}, userId={}",
                    message.getOrderId(), message.getVoucherId(), message.getUserId());

        } catch (Exception e) {
            log.error("死信处理异常: orderId={}", message.getOrderId(), e);
            try {
                if (channel.isOpen()) {
                    channel.basicNack(deliveryTag, false, false);
                }
            } catch (Exception ex) {
                log.error("死信 NACK 失败", ex);
            }
        }
    }
}

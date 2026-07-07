package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // ========== 交换机 ==========
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    public static final String SECKILL_DLX_EXCHANGE = "seckill.dlx.exchange";

    // ========== 队列 ==========
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ORDER_DLQ = "seckill.order.dlq";

    // ========== 路由键 ==========
    public static final String SECKILL_ORDER_KEY = "seckill.order";
    public static final String SECKILL_ORDER_DLX_KEY = "seckill.order.dlx";

    /**
     * 秒杀订单 Topic 交换机
     */
    @Bean
    public TopicExchange seckillExchange() {
        return new TopicExchange(SECKILL_EXCHANGE, true, false);
    }

    /**
     * 死信 Topic 交换机
     */
    @Bean
    public TopicExchange seckillDlxExchange() {
        return new TopicExchange(SECKILL_DLX_EXCHANGE, true, false);
    }

    /**
     * 秒杀订单普通队列 — 绑定 TTL + DLX
     * 消息在队列中存活 15 分钟，超时未消费则转入死信队列（模拟超时未支付关单）
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .ttl(900_000) // 15 分钟 TTL
                .deadLetterExchange(SECKILL_DLX_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_ORDER_DLX_KEY)
                .build();
    }

    /**
     * 死信队列 — 接收超时/被拒绝的消息
     */
    @Bean
    public Queue seckillOrderDlq() {
        return QueueBuilder.durable(SECKILL_ORDER_DLQ).build();
    }

    /**
     * 绑定：seckill.exchange → seckill.order.queue (routing: seckill.order)
     */
    @Bean
    public Binding seckillOrderBinding() {
        return BindingBuilder
                .bind(seckillOrderQueue())
                .to(seckillExchange())
                .with(SECKILL_ORDER_KEY);
    }

    /**
     * 绑定：seckill.dlx.exchange → seckill.order.dlq (routing: seckill.order.dlx)
     */
    @Bean
    public Binding seckillOrderDlxBinding() {
        return BindingBuilder
                .bind(seckillOrderDlq())
                .to(seckillDlxExchange())
                .with(SECKILL_ORDER_DLX_KEY);
    }

    /**
     * JSON 消息转换器（替代默认的 SimpleMessageConverter）
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitAdmin rabbitAdmin(org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }
}

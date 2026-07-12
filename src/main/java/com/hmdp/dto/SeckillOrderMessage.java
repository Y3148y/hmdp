package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀订单消息体（投递到 RabbitMQ）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 全局唯一订单ID（由 RedisIdWorker 生成） */
    private Long orderId;

    /** 用户ID */
    private Long userId;

    /** 优惠券ID */
    private Long voucherId;

    /** 订单创建时间（毫秒时间戳，避免 LocalDateTime JSON 序列化问题） */
    private long createTime;
}

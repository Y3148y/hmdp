package com.hmdp.service.impl;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

/**
 * <p>
 *  【当前】V3 — RabbitMQ 异步模式
 * </p>
 * <p>
 *  Lua 脚本负责 Redis 原子预检（库存 + 去重），
 *  RabbitMQ 负责削峰填谷 + 超时关单（TTL + DLX），
 *  消费者 {@link com.hmdp.mq.SeckillOrderConsumer} 负责 DB 持久化 + 幂等。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** Lua 脚本：原子预检（扣库存 + 去重），不含消息队列操作 */
    private static final DefaultRedisScript<Long> SECKILL_MQ_SCRIPT;
    static {
        SECKILL_MQ_SCRIPT = new DefaultRedisScript<>();
        SECKILL_MQ_SCRIPT.setLocation(new ClassPathResource("seckill_mq.lua"));
        SECKILL_MQ_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // 1. 执行 Lua 脚本：原子性校验库存 + 去重 + 扣库存
        Long result = stringRedisTemplate.execute(
                SECKILL_MQ_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 2. 投递消息到 RabbitMQ（异步削峰）
        SeckillOrderMessage message = new SeckillOrderMessage(
                orderId, userId, voucherId, System.currentTimeMillis()
        );
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SECKILL_EXCHANGE,
                RabbitMQConfig.SECKILL_ORDER_KEY,
                message
        );

        // 3. 立即返回"排队中"
        return Result.ok("排队中，订单号：" + orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        // 1. 一人一单：数据库层面兜底校验
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.error("用户已购买过该优惠券");
            return;
        }

        // 2. 扣减秒杀库存（乐观锁：stock > 0）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        // 3. 保存订单
        save(voucherOrder);
    }
}

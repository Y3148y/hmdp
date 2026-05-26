package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
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


    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询秒杀优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        //2.判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }

        //3.判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }

        //4.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        //5.扣减库存
        // 乐观锁
        /*boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .eq("stock", seckillVoucher.getStock())  // 关键：等于旧库存
                .update();*/
        /*
        在数据库中，UPDATE 语句是一个原子操作。这意味着：
        检查条件 (stock > 0)
        执行计算 (stock = stock - 1)
        写入磁盘 这三个步骤是一气呵成的。中间不会被其他线程打断。
        ✅ 关键点二：行锁（Row Lock）
        InnoDB 存储引擎在执行 UPDATE 语句时，会自动给满足 WHERE 条件的那一行数据加上排他锁（X锁）。
        并发场景演示： 假设有 100 个用户同时秒杀只有 1 件库存的商品：
        线程 A 到达数据库，抢到这一行的锁。
        检查 stock > 0 (此时 stock=1)，条件成立。
        执行 stock = 1 - 1 = 0。
        提交事务，释放锁。
        */
        // 原子条件更新
        boolean success = seckillVoucherService.update() //调用 MyBatis-Plus 的 IService 接口中的 update() 方法。 返回一个 UpdateChainWrapper<SeckillVoucher> 对象。
                .setSql("stock = stock-1") // set stock = stock-1
                .eq("voucher_id", voucherId).gt("stock", 0) // where
                .update();// 触发执行
        if(!success){
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        /*
        synchronized(userId.toString().intern()){
            // 事务需要使用spring aop代理对象，不能使用this (确保事务生效，添加aspectj依赖，启动类打开暴露注解)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }//确保事务提交后释放锁
        */
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        boolean isLock = simpleRedisLock.tryLock(10L);
        if(!isLock){
            return Result.fail("请勿重复下单");
        }
        try {
            // 事务需要使用spring aop代理对象，不能使用this (确保事务生效，添加aspectj依赖，启动类打开暴露注解)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            simpleRedisLock.unLock();
        }
    }


    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //5.1一人一单
        Long userId = UserHolder.getUser().getId();
        // toString()底层是new String() intern(): 返回字符串常量池中的对象
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0){
            // 用户已经购买过
            return Result.fail("用户已经购买过了！");
        }

        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 生成订单号
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2 用户id
        voucherOrder.setUserId(userId);
        //6.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //7.返回订单号
        return Result.ok(orderId);

    }
}

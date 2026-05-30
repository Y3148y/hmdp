package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> voucherTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //单线程的线程池，用于异步处理秒杀订单
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOderHandler());
    }
    private class VoucherOderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    //1. 获取队列中的订单信息
                    VoucherOrder voucherOrder = voucherTasks.take();
                    //2.创建订单
                    handlerVoucherOrder(voucherOrder);

                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        // 多线程，不能从UserHolder中获取，当前线程是一个子线程，threadLocal线程变量无法获取
        Long userId = voucherOrder.getUserId();
        //redisson 锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            // 获取锁失败，返回错误或者重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);

        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.EMPTY_LIST,
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        //2.判断结果是否为0
        if(r != 0){
            //2.1 不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为0，有购买资格，保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //  保存到阻塞队列
        voucherTasks.add(voucherOrder);
        // 事务需要使用spring aop代理对象，不能使用this (确保事务生效，添加aspectj依赖，启动类打开暴露注解)
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderId);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询秒杀优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//
//        //2.判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//
//        //3.判断秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已结束");
//        }
//
//        //4.判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//
//
//        //一人一单
//        Long userId = UserHolder.getUser().getId();
//        /*
//        synchronized(userId.toString().intern()){
//            // 事务需要使用spring aop代理对象，不能使用this (确保事务生效，添加aspectj依赖，启动类打开暴露注解)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }//确保事务提交后释放锁
//        */
//
//        //redis分布式锁 set nx
//        /*SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        boolean isLock = simpleRedisLock.tryLock(10L);
//        if(!isLock){
//            return Result.fail("请勿重复下单");
//        }
//        try {
//            // 事务需要使用spring aop代理对象，不能使用this (确保事务生效，添加aspectj依赖，启动类打开暴露注解)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            simpleRedisLock.unLock();
//        }*/
//
//        //redisson 锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("请勿重复下单");
//        }
//        try {
//            // 事务需要使用spring aop代理对象，不能使用this (确保事务生效，添加aspectj依赖，启动类打开暴露注解)
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//    }



    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.1一人一单
        Long userId = voucherOrder.getUserId();
        // toString()底层是new String() intern(): 返回字符串常量池中的对象
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0){
            // 用户已经购买过
            log.error("用户已经购买过");
            return;
        }

        //5.2扣减库存
        boolean success = seckillVoucherService.update() //调用 MyBatis-Plus 的 IService 接口中的 update() 方法。 返回一个 UpdateChainWrapper<SeckillVoucher> 对象。
                .setSql("stock = stock-1") // set stock = stock-1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where
                .update();// 触发执行
        if(!success){
            log.error("库存不足");
            return;
        }
        save(voucherOrder);

    }
}

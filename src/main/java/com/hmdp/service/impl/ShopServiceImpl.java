package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long  id) {
        //1. 缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //2. 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        Shop shop = queryWithLogicalExpire(id);

//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期
    public Shop queryWithLogicalExpire(Long id) {
        //1. 从Redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);

        //2. 判断
        if (StrUtil.isBlank(shopJson)){
            //3. 不存在，返回
            return null;
        }

        //4. 存在，
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期直接返回
            return shop;
        }
        //5.2 已过期，缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //5.2.1 获取互斥锁
        boolean isLock = tryLock(lockKey);
        //5.2.2 判断是否获取成功
        if (isLock){
            //5.2.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //5.2.3.1 重建缓存
                try{
                    this.saveShop2Redis(id, 20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //5.2.3.2 释放锁
                    unLock(lockKey);
                }

            });
        }

        return shop;
    }

    //互斥锁 - 缓存击穿
    public Shop queryWithMutex(Long  id) {
        //1. 从Redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);

        //2. 判断
        if (StrUtil.isNotBlank(shopJson)){
            //3. 存在，返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中是否是空值
        if (shopJson != null){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //4. 不存在，查询数据库
        //4. 实现缓存重建
        //4.1 获取互斥锁
        Shop shop = null;
        String lock = RedisConstants.LOCK_SHOP_KEY+id;
        try{
            boolean isLock = tryLock(lock);
            //4.2 判断锁是否获取成功
            if (!isLock){
                //4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id); // 递归调用
            }
            //4.4 成功，根据id查询数据库

            shop = getById(id);
            //5.不存在，返回
            if (shop == null){
                // 缓存击穿，将空值写入Redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            //6. 存在，保存Redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }
        finally {
            //7. 释放锁
            unLock(lock);
        }

        return shop;
    }

    //缓存穿透
    public Shop queryWithPassThrough(Long  id) {
        //1. 从Redis中查询
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);

        //2. 判断
        if (StrUtil.isNotBlank(shopJson)){
            //3. 存在，返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断命中是否是空值
        if (shopJson != null){
            return null;
        }
        //4. 不存在，查询数据库
        Shop shop = getById(id);
        //5.不存在，返回
        if (shop == null){
            // 缓存穿透，将空值写入Redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //6. 存在，保存Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10L, TimeUnit.SECONDS);// 对应redis的SETNX
        return Boolean.TRUE.equals(flag); // 转为Boolean, 防止空指针
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    // 逻辑过期解决缓存击穿
    private void saveShop2Redis(Long id,Long expireSeconds){
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }



    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId() == null){
            return Result.fail("店铺id不能为空");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除Redis
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}

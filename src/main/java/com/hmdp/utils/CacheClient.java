package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 缓存穿透
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 缓存击穿逻辑过期
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit unit) {
        //加上过期字段
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    // 缓存穿透
    public <R, ID> R queryWithPassThrough(
            String ketPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = ketPrefix + id;
        //1. 从Redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);

        //2. 判断
        if (StrUtil.isNotBlank(json)) {
            //3. 存在，返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中是否是空值
        if (json != null) {
            return null;
        }
        //4. 不存在，查询数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回
        if (r == null) {
            // 缓存穿透，将空值写入Redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //6. 存在，保存Redis
        this.set(key, r, time, unit);
        return r;
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(
            String ketPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {

        //1. 从Redis中查询
        String key = ketPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //2. 判断
        if (StrUtil.isBlank(json)) {
            //3. 不存在，返回
            return null;
        }

        //4. 存在，
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if(expireTime == null){
            System.out.println("expireTime == null");
            return r;
        }
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期直接返回
            return r;
        }
        //5.2 已过期，缓存重建
        String lockKey = key;
        //5.2.1 获取互斥锁
        boolean isLock = tryLock(lockKey);
        //5.2.2 判断是否获取成功
        if (isLock) {
            //5.2.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //5.2.3.1 重建缓存
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //5.2.3.2 释放锁
                    unLock(lockKey);
                }

            });
        }

        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag); // 转为Boolean, 防止空指针
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}



















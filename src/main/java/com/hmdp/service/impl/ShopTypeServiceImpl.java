package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // TODO 使用Redis实现缓存
    @Override
    public Result queryTypeList() {
        /*
        "data": [
        {
            "id": 1,
            "name": "美食",
            "icon": "/types/ms.png",
            "sort": 1
        },
        * */
        // 1. 在Redis中查询缓存
        List<String> typeListCache = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        // 2. 判断缓存是否存在
        // 3. 存在，直接返回
        if (typeListCache != null && !typeListCache.isEmpty()){
            List<ShopType> typeList = typeListCache.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(typeList);
        }
        // 4. 不存在，查询数据库
        List<ShopType> list = query().orderByAsc("sort").list();
        // 5. 数据库不存在，返回错误
        if (list == null || list.isEmpty()){
            return Result.fail("店铺列表不存在");
        }
        // 6. 存在，写入Redis
        List<String> shopList = list.stream().sorted(Comparator.comparingInt(ShopType::getSort)).map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().leftPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, shopList);
        // 7. 设置缓存有效期
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_KEY, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        // 8. 返回
        return Result.ok(list);
    }
}

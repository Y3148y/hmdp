package com.hmdp.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 商铺 ID 布隆过滤器 — 防止缓存穿透
 * 基于 Guava 内存布隆过滤器, 启动时加载全量商铺 ID
 */
@Slf4j
@Component
public class ShopBloomFilter {

    @Resource
    private com.hmdp.mapper.ShopMapper shopMapper;

    /** 预期插入量 (根据业务估算) */
    private static final int EXPECTED_INSERTIONS = 100_000;
    /** 误判率 0.01% */
    private static final double FPP = 0.0001;

    private BloomFilter<String> bloomFilter;

    @PostConstruct
    private void init() {
        bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                EXPECTED_INSERTIONS,
                FPP
        );

        long start = System.currentTimeMillis();
        List<Long> ids = shopMapper.selectList(
                new QueryWrapper<com.hmdp.entity.Shop>().select("id").lambda()
        ).stream().map(com.hmdp.entity.Shop::getId).collect(java.util.stream.Collectors.toList());

        for (Long id : ids) {
            bloomFilter.put(String.valueOf(id));
        }
        log.info("布隆过滤器初始化完成, 加载 {} 个商铺ID, 耗时 {}ms", ids.size(),
                System.currentTimeMillis() - start);
    }

    /**
     * 判断商铺 ID 是否可能存在
     * @return false = 一定不存在 (直接拒绝, 防穿透), true = 可能存在 (继续查询)
     */
    public boolean mightContain(Long shopId) {
        return bloomFilter.mightContain(String.valueOf(shopId));
    }

    /**
     * 新增商铺时同步添加至布隆过滤器
     */
    public void add(Long shopId) {
        bloomFilter.put(String.valueOf(shopId));
    }

    /**
     * 获取布隆过滤器预期插入量
     */
    public long expectedInsertions() {
        return EXPECTED_INSERTIONS;
    }
}

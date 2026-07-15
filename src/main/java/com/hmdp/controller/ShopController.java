package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;

import java.util.Collections;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop")
@Api(tags = "商铺相关接口")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    @ApiOperation("根据ID查询商铺信息")
    public Result queryShopById(@ApiParam("商铺ID") @PathVariable("id") Long id) {
//        return Result.ok(shopService.getById(id));

        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    @ApiOperation("新增商铺信息")
    public Result saveShop(@ApiParam("商铺数据") @RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    @ApiOperation("更新商铺信息")
    public Result updateShop(@ApiParam("商铺数据") @RequestBody Shop shop) {
        // 写入数据库
//        shopService.updateById(shop);
//        return Result.ok();
        return shopService.update(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    @ApiOperation("根据商铺类型分页查询商铺信息")
    public Result queryShopByType(
            @ApiParam("商铺类型ID") @RequestParam("typeId") Integer typeId,
            @ApiParam("页码") @RequestParam(value = "current", defaultValue = "1") Integer current,
            @ApiParam("经度") @RequestParam(value = "x",required = false) Double x,
            @ApiParam("纬度") @RequestParam(value = "y", required = false) Double y
    ) {
        return shopService.queryShopByType(typeId, current, x, y);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    @ApiOperation("ES 分词检索商铺（名称+商圈+地址），不可用时自动降级 MySQL LIKE")
    public Result queryShopByName(
            @ApiParam("搜索关键词") @RequestParam(value = "name", required = false) String name,
            @ApiParam("页码") @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        if (StrUtil.isBlank(name)) {
            return Result.ok(Collections.emptyList());
        }
        return shopService.searchByName(name, current);
    }
}

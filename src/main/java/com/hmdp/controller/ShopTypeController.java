package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IUserInfoService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
@Api(tags = "商铺类型相关接口")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private IUserInfoService userInfoService;

    @GetMapping("list")
    @ApiOperation("查询商铺类型列表")
    public Result queryTypeList() {
        return typeService.queryTypeList();
    }
}

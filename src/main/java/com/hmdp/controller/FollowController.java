package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
@Api(tags = "关注相关接口")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    @ApiOperation("关注/取消关注用户")
    public Result follow(@ApiParam("被关注用户ID") @PathVariable("id") Long followUserId, @ApiParam("是否关注：true为关注，false为取消关注") @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    @ApiOperation("查询是否关注用户")
    public Result isFollow(@ApiParam("用户ID") @PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    @ApiOperation("查询共同关注用户")
    public Result followCommons(@ApiParam("用户ID") @PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
}

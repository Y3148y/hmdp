package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.*;

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
@RequestMapping("/blog")
@Api(tags = "博客相关接口")
public class BlogController {

    @Resource
    private IBlogService blogService;


    ThreadLocal threadLocal = new ThreadLocal();

    @PostMapping
    @ApiOperation("发布新博客")
    public Result saveBlog(@ApiParam("博客内容") @RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    @ApiOperation("点赞博客")
    public Result likeBlog(@ApiParam("博客ID") @PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    @ApiOperation("查询我的博客")
    public Result queryMyBlog(@ApiParam("页码") @RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    @ApiOperation("查询热门博客")
    public Result queryHotBlog(@ApiParam("页码") @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }
    @GetMapping("/{id}")
    @ApiOperation("根据ID查询博客详情")
    public Result queryBlogById(@ApiParam("博客ID") @PathVariable("id") Long id) {
        return blogService.qureryById(id);
    }

    @GetMapping("/likes/{id}")
    @ApiOperation("查询博客点赞用户列表")
    public Result queryBlogLikes(@ApiParam("博客ID") @PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    @GetMapping("/of/user")
    @ApiOperation("根据用户ID查询博客")
    public Result queryBlogByUserId(
            @ApiParam("页码") @RequestParam(value = "current", defaultValue = "1") Integer current,
            @ApiParam("用户ID") @RequestParam("id") Long id) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    @ApiOperation("查询关注用户博客（分页）")
    public Result queryBlogOfFollow(
            @ApiParam("上一页最后一条博客ID") @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }

}

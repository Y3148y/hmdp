package com.hmdp.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.admin.entity.AdminUser;
import com.hmdp.admin.mapper.AdminUserMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class UserManageController {

    @Resource
    private AdminUserMapper adminUserMapper;

    @Resource
    private PasswordEncoder passwordEncoder;

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('user:list')")
    public Object listUsers() {
        List<AdminUser> users = adminUserMapper.selectList(
                new QueryWrapper<AdminUser>().select("id", "username", "nickname", "email", "enabled", "create_time"));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", users);
        return result;
    }

    @PostMapping("/users")
    @PreAuthorize("hasAuthority('user:create')")
    public Object createUser(@RequestBody AdminUser user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        adminUserMapper.insert(user);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "用户创建成功");
        return result;
    }

    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('user:update')")
    public Object updateUser(@PathVariable Long id, @RequestBody AdminUser user) {
        user.setId(id);
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null); // 不修改密码
        }
        adminUserMapper.updateById(user);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "用户更新成功");
        return result;
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasAuthority('user:delete')")
    public Object deleteUser(@PathVariable Long id) {
        adminUserMapper.deleteById(id);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "用户已删除");
        return result;
    }
}

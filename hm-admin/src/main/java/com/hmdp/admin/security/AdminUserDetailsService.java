package com.hmdp.admin.security;

import com.hmdp.admin.entity.AdminUser;
import com.hmdp.admin.mapper.AdminUserMapper;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 用户查询服务。
 *
 * <h3>一句话</h3>
 * HR 系统——输入用户名，去数据库查出这个人 + 他的角色 + 他的权限，包装成标准员工档案（AdminUserDetails）返回。
 *
 * <h3>谁调它</h3>
 * Spring Security 的 AuthenticationManager（打卡机）在登录时自动调用它。
 * 流程：用户输入 admin / admin123 → AuthController 调 AuthenticationManager.authenticate()
 * → AuthenticationManager 内部调这个类的 loadUserByUsername("admin") 去查 DB。
 *
 * <h3>它实现了什么接口</h3>
 * UserDetailsService 是 Spring Security 定义的标准接口，全 Spring 生态通用。
 * 你只需要实现 loadUserByUsername() 一个方法，告诉 Security "怎么根据用户名查用户"。
 *
 * <h3>查出来的 AdminUser 包含什么</h3>
 * 通过 MyBatis 的嵌套查询，一次 SQL 查出来：
 * - admin_user 表的用户基本信息（用户名、密码）
 * - 关联的角色（通过 admin_user_role → admin_role）
 * - 关联的权限（通过 admin_role_permission → admin_permission）
 */
@Service  // 标为 Service，Spring Security 启动时自动发现并注入
public class AdminUserDetailsService implements UserDetailsService {

    /** 用户表 Mapper，调 selectByUsernameWithRoles() 一次查出用户+角色+权限 */
    @Resource
    private AdminUserMapper adminUserMapper;

    /**
     * 根据用户名查用户。
     *
     * <h3>Spring Security 怎么用它</h3>
     * <pre>
     * 1. 用户 POST /auth/login { username: "admin", password: "admin123" }
     * 2. Security 的 AuthenticationManager 收到请求
     * 3. AuthenticationManager 内部调用 loadUserByUsername("admin")
     * 4. 拿到 AdminUserDetails 后，用 BCryptPasswordEncoder 比对你输入的密码和 DB 存的密码
     * 5. 匹配成功 → 登录成功 → 返回 Authentication
     * 6. 匹配失败 → 抛 BadCredentialsException → 登录失败
     * </pre>
     *
     * @param username 登录时输入的用户名
     * @return 装好用户+角色+权限的标准档案
     * @throws UsernameNotFoundException 数据库里没这个用户名时抛出
     */
    @Override
    public AdminUserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {
        // 调 MyBatis Mapper，一次 SQL 查出用户 + 角色 + 权限
        AdminUser user = adminUserMapper.selectByUsernameWithRoles(username);

        // 查不到：用户名不存在
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }

        // 查到了：包装成 Security 的标准格式返回
        // AdminUserDetails 构造函数里会把 user 的 roles 和 permissions 全转成 GrantedAuthority
        return new AdminUserDetails(user);
    }
}

package com.hmdp.admin.config;

import com.hmdp.admin.entity.AdminPermission;
import com.hmdp.admin.mapper.AdminPermissionMapper;
import com.hmdp.admin.security.JwtAuthenticationEntryPoint;
import com.hmdp.admin.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Security 总配置。
 *
 * <h3>职责</h3>
 * <pre>
 * 1. 定义密码加密方式（BCrypt）
 * 2. 定义登录认证数据源（AdminUserDetailsService → 查 DB）
 * 3. 定义 URL 访问规则（哪些路径放行、哪些需要什么权限）
 * 4. 插入 JWT 过滤器到 Security 过滤链中
 * </pre>
 *
 * <h3>请求处理流程</h3>
 * <pre>
 * HTTP 请求 →
 *   [JwtAuthenticationFilter] 从 Header 提取 JWT，验签，设 Authentication →
 *   [FilterSecurityInterceptor] 匹配 URL 权限规则，有权限则放行 →
 *   [Controller] 执行业务逻辑
 * </pre>
 *
 * <h3>动态 URL 鉴权</h3>
 * <pre>
 * 启动时从 admin_permission 表加载所有 URL→权限映射。
 * 同一 URL 的多个权限合并为 hasAnyAuthority，任一权限匹配即放行。
 * 新增受保护接口只需在 DB 插入一条权限记录，无需改代码重启。
 * </pre>
 */
@Configuration
@EnableWebSecurity // 开启 Spring Security，用自己的规则替换默认行为
@EnableGlobalMethodSecurity(prePostEnabled = true) // 开启 @PreAuthorize 注解支持
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    // ==================== 依赖注入 ====================

    /** 你在 AdminUserDetailsService 中实现的查用户逻辑。
     *  Spring Security 启动时自动扫描所有 UserDetailsService 实现类并注入。 */
    @Resource
    private UserDetailsService userDetailsService;

    /** JWT 过滤器：从 Header 取 Bearer Token → 验签 → 提取用户名/角色/权限 → 设 Authentication */
    @Resource
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    /** 未登录时的处理：返回 {"code":401,"message":"未登录或 Token 已过期"} JSON */
    @Resource
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    /** 权限表 Mapper，用于从 DB 加载 URL→权限 映射 */
    @Resource
    private AdminPermissionMapper permissionMapper;

    // ==================== Bean 定义 ====================

    /**
     * 密码加密器：BCrypt。
     * <p>
     * BCrypt 是单向哈希算法，自带随机盐。
     * "admin123" → "$2a$10$MzZ1VTBN..."（60位，每次加密结果不同）。
     * 比对时不比对原文，而是调用 matches(rawPassword, hashedPassword)。
     * 用途：登录时校验密码 / 创建用户时加密存储密码。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证管理器：登录时执行用户名密码校验的执行器。
     * <p>
     * AuthController.login() 调用此 Bean 的 authenticate() 方法完成登录。
     * Spring Security 默认不把 AuthenticationManager 暴露为 Bean，
     * 这里手动暴露，方便在 Controller 中注入使用。
     */
    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    // ==================== 认证配置：怎么验证一个人 ====================

    /**
     * 配置认证数据源和密码比对方式。
     * <p>
     * 登录流程：
     * <ol>
     *   <li>AuthController 收到 {username, password}</li>
     *   <li>调 authenticationManager.authenticate()</li>
     *   <li>底层调 AdminUserDetailsService.loadUserByUsername(username) 查 DB</li>
     *   <li>BCryptPasswordEncoder.matches(输入的密码, DB 中的哈希)</li>
     *   <li>匹配成功 → 登录成功 → 返回 Authentication 对象</li>
     *   <li>匹配失败 → 抛出 BadCredentialsException</li>
     * </ol>
     */
    @Override
    protected void configure(AuthenticationManagerBuilder auth) throws Exception {
        auth.userDetailsService(userDetailsService) // 数据源：你的 AdminUserDetailsService
                .passwordEncoder(passwordEncoder()); // 密码比对器：BCrypt
    }

    // ==================== 安全配置：什么人能访问什么 ====================

    /**
     * 配置 HTTP 安全规则。
     *
     * <h3>规则匹配顺序（重要）</h3>
     * Security 按代码书写顺序逐条匹配，命中即停。
     * 所以 permitAll 在最前 → 动态规则居中 → anyRequest.authenticated() 兜底。
     *
     * <h3>过滤链执行顺序</h3>
     * <pre>
     * 请求进来 →
     *   1. JwtAuthenticationFilter（提取 JWT → 验签 → 设 SecurityContext）
     *   2. Security 内置的 FilterSecurityInterceptor（匹配 URL 权限规则）
     *   3. Controller
     * </pre>
     *
     * @param http Spring Security 的 HTTP 安全构建器
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // ===== Step 1：从数据库加载 URL→权限 映射 =====
        // 例如：/admin/users → user:list  /admin/roles → role:manage
        List<AdminPermission> perms = permissionMapper.selectList(null);

        // ===== Step 2：基础配置 =====
        http
                // 关闭 CSRF：JWT 自带防跨站伪造，不需要 Security 的 CSRF Token
                .csrf().disable()
                // 不创建 Session：JWT 无状态，每次请求独立验证，不需要服务器保存会话
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                // 未登录 → 返回 401 JSON（默认是重定向到 /login 页面，不适合前后端分离）
                .exceptionHandling()
                .authenticationEntryPoint(jwtAuthenticationEntryPoint);

        // ===== Step 3：URL 权限规则（按顺序匹配）=====
        http.authorizeRequests()
                // 登录接口 → 放行，任何人都能调
                .antMatchers("/auth/login").permitAll()
                // 浏览器预检请求（OPTIONS）→ 放行，否则跨域请求会先被拦
                .antMatchers(HttpMethod.OPTIONS).permitAll();

        // 动态注册：同一个 URL 可能对应多个权限（如 /admin/users 有 user:list, user:create...）
        // 必须先按 URL 分组合并，否则后注册的会覆盖前面的（Spring Security 同 URL 规则覆盖）
        // 例如：DB 中 /admin/users 对应 user:list, user:create, user:update, user:delete
        //      → antMatchers("/admin/users").hasAnyAuthority("user:list","user:create","user:update","user:delete")
        if (perms != null && !perms.isEmpty()) {
            // 按 URL 分组：/admin/users → [user:list, user:create, user:update, user:delete]
            Map<String, List<String>> urlToPerms = new LinkedHashMap<>();
            for (AdminPermission perm : perms) {
                if (perm.getUrl() != null && !perm.getUrl().isEmpty()) {
                    urlToPerms.computeIfAbsent(perm.getUrl(), k -> new ArrayList<>())
                              .add(perm.getCode());
                }
            }
            // 每个 URL 注册一条规则：拥有该 URL 任一权限即可访问
            for (Map.Entry<String, List<String>> entry : urlToPerms.entrySet()) {
                String[] codes = entry.getValue().toArray(new String[0]);
                http.authorizeRequests()
                        .antMatchers(entry.getKey())               // 受保护的 URL
                        .hasAnyAuthority(codes);                   // 拥有任一权限即可
            }
        }

        // 兜底规则：上面都没匹配到的，至少需要登录
        http.authorizeRequests()
                .anyRequest().authenticated();

        // ===== Step 4：插入 JWT 过滤器 =====
        // 把你的 JwtAuthenticationFilter 插在 Security 内置的
        // UsernamePasswordAuthenticationFilter（处理表单登录的）之前。
        // 这样 JWT 先解析，Security 发现已经有认证信息就跳过后面的登录流程。
        http.addFilterBefore(jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class);
    }
}

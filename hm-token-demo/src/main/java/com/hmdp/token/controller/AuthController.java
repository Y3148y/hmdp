package com.hmdp.token.controller;

import com.hmdp.token.dto.LoginRequest;
import com.hmdp.token.dto.LoginResponse;
import com.hmdp.token.dto.RefreshRequest;
import com.hmdp.token.security.JwtTokenProvider;
import com.hmdp.token.security.RefreshTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证接口——登录 / 刷新 / 登出。
 *
 * <h3>三个接口</h3>
 * POST /auth/login   → 用户名+密码 → { accessToken, refreshToken }
 * POST /auth/refresh → refreshToken → { accessToken(新的) }
 * POST /auth/logout  → refreshToken → 吊销
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @Resource
    private RefreshTokenService refreshTokenService;

    @Resource
    private PasswordEncoder passwordEncoder;

    /** 模拟用户列表（从 yml 注入） */
    @Resource
    private DemoUsers demoUsers;

    /**
     * 登录。
     *
     * 场景：第三方开发者输入 username + password。
     * 返回短期 accessToken（JWT 15min）和长期 refreshToken（UUID 7天 Redis）。
     * 客户端应该把 refreshToken 安全存储（如 HttpOnly Cookie 或 Keychain），
     * accessToken 放内存即可。
     */
    @PostMapping("/login")
    public Object login(@Valid @RequestBody LoginRequest request) {
        // 查内存用户，比密码（演示用，生产改查 DB）
        DemoUser user = demoUsers.findByUsername(request.getUsername());
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getEncodedPassword())) {
            Map<String, Object> err = new HashMap<>();
            err.put("code", 401);
            err.put("message", "用户名或密码错误");
            return err;
        }

        // 签发两个 Token
        String accessToken = jwtTokenProvider.generateAccessToken(user.getUsername());
        String refreshToken = refreshTokenService.createRefreshToken(user.getUsername());

        long expiresIn = 900; // 15 分钟 = 900 秒

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "登录成功");
        result.put("data", new LoginResponse(accessToken, refreshToken, "Bearer", expiresIn));
        return result;
    }

    /**
     * 刷新 Access Token。
     *
     * 场景：客户端发现 accessToken 过期（收到 401），拿 refreshToken 来换新的。
     * 不传用户名密码——用户已经登录过了。
     */
    @PostMapping("/refresh")
    public Object refresh(@Valid @RequestBody RefreshRequest request) {
        // 查 Redis 验证 refreshToken 是否有效
        String username = refreshTokenService.validateAndGetUsername(request.getRefreshToken());
        if (username == null) {
            // refreshToken 也过期或已被吊销 → 需要重新登录
            Map<String, Object> err = new HashMap<>();
            err.put("code", 401);
            err.put("message", "refreshToken 无效或已过期，请重新登录");
            return err;
        }

        // 签发新 accessToken（refreshToken 不变，不重新发）
        String newAccessToken = jwtTokenProvider.generateAccessToken(username);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "Access Token 已刷新");
        Map<String, Object> data = new HashMap<>();
        data.put("accessToken", newAccessToken);
        data.put("tokenType", "Bearer");
        data.put("expiresIn", 900);
        result.put("data", data);
        return result;
    }

    /**
     * 登出/吊销。
     *
     * 场景：管理员吊销某个开发者的 API 权限（或用户主动登出）。
     * 删 Redis 里的 refreshToken → 等 accessToken 15min 过期后自然下线。
     * 如果需要即时失效 accessToken，需要加黑名单——本演示未实现。
     */
    @PostMapping("/logout")
    public Object logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokenService.revokeRefreshToken(request.getRefreshToken());

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "已登出，Refresh Token 已吊销");
        return result;
    }

    // ==================== 模拟用户 ====================

    /**
     * 从 yml 读配置的模拟用户列表。
     * 不用数据库——这个模块聚焦双 Token 流转，不引入 DB。
     * 启动时把明文密码转成 BCrypt 哈希存内存。
     */
    @Component
    @ConfigurationProperties(prefix = "demo")
    public static class DemoUsers {

        @Resource
        private PasswordEncoder passwordEncoder;

        private List<DemoUser> users;

        public List<DemoUser> getUsers() { return users; }
        public void setUsers(List<DemoUser> users) { this.users = users; }

        @PostConstruct
        public void encodePasswords() {
            // 启动时把 yml 里的明文密码转成 BCrypt 哈希
            if (users != null) {
                for (DemoUser user : users) {
                    user.encodedPassword = passwordEncoder.encode(user.password);
                }
            }
        }

        public DemoUser findByUsername(String username) {
            if (users == null) return null;
            return users.stream()
                    .filter(u -> u.getUsername().equals(username))
                    .findFirst().orElse(null);
        }
    }

    /**
     * 模拟用户结构：username + 明文密码（yml 读入） + BCrypt 加密密码（启动时算）。
     */
    public static class DemoUser {
        private String username;
        private String password;          // yml 里的明文
        private String encodedPassword;   // 启动时 BCrypt 加密后的

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getEncodedPassword() { return encodedPassword; }
    }
}

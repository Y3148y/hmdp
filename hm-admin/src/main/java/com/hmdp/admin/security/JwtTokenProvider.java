package com.hmdp.admin.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * JWT 工具类。
 *
 * <h3>一句话</h3>
 * 工牌制作机——登录时给你做一个新工牌（生成 JWT），进门时验你的工牌是不是假的（验签）。
 *
 * <h3>三个方法干什么</h3>
 * <pre>
 * generateToken()   → 登录成功，给你做一张工牌（JWT），上面写了你是谁、什么角色、什么权限、几点过期
 * validateToken()   → 进门时验工牌：签名对不对？过期没？
 * getAuthentication() → 验过之后，把工牌上的信息拆出来，装进 Security 的标准格式
 * </pre>
 */
@Component
public class JwtTokenProvider {

    // ==================== 配置变量 ====================

    /** JWT 签名密钥（从 application.yml 的 jwt.secret 读）。
     *  相当于工牌上的防伪水印——只有持有这个密钥的人能签发和验证。
     *  面试：HS256 = HMAC-SHA256，对称加密，签发和验签用同一把密钥。 */
    @Value("${jwt.secret}")
    private String secret;

    /** JWT 过期时间，单位毫秒（从 application.yml 的 jwt.expiration 读）。
     *  例如 7200000 = 2 小时。过了这个时间工牌作废，需要重新登录。 */
    @Value("${jwt.expiration}")
    private long expiration;

    // ==================== JWT payload 里的 key 名 ====================

    /** JWT 里存放角色列表的字段名，值如 "ROLE_SUPER_ADMIN,ROLE_ADMIN" */
    private static final String ROLES_KEY = "roles";

    /** JWT 里存放权限列表的字段名，值如 "user:list,user:create" */
    private static final String PERMS_KEY = "perms";

    // ==================== 方法一：生成 JWT ====================

    /**
     * 登录成功后调用：根据用户信息生成一个 JWT 字符串。
     *
     * <h3>做了什么</h3>
     * <ol>
     *   <li>从 Authentication 里拿出用户信息（AdminUserDetails）</li>
     *   <li>把角色（ROLE_开头）和权限（user:list这种）分开收集</li>
     *   <li>拼成 JWT：用户名 + 角色 + 权限 + 签发时间 + 过期时间 + HS256 签名</li>
     * </ol>
     *
     * <h3>生成的 JWT 长什么样</h3>
     * eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiIsInJvbGVzIjoiUk9MRV9TVVBFUl9BRE1JTiIsInBlcm1zIjoidXNlcjpsaXN0LHVzZXI6Y3JlYXRlIiwiZXhwIjoxNzIxMDM2MDAwfQ.xxx
     *  ↑ 头部(Base64)          ↑ 载荷(Base64): 用户名+角色+权限+过期            ↑ 签名
     *
     * @param authentication 登录成功后 Spring Security 返回的认证对象，里面装着用户信息
     * @return JWT 字符串，三段 Base64 用 . 拼接
     */
    public String generateToken(Authentication authentication) {
        // 从 Authentication 里取出你的 AdminUserDetails（里面有用户的所有信息）
        AdminUserDetails userDetails = (AdminUserDetails) authentication.getPrincipal();

        // 收集角色：只取 ROLE_ 开头的（如 ROLE_SUPER_ADMIN），用逗号拼起来
        String roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority) // 拿到每个权限的字符串名
                .filter(a -> a.startsWith("ROLE_"))  // 只保留角色
                .collect(Collectors.joining(","));   // 拼成 "ROLE_A,ROLE_B"

        // 收集权限：只取不是 ROLE_ 开头的（如 user:list, user:create），用逗号拼起来
        String perms = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> !a.startsWith("ROLE_")) // 只保留具体权限
                .collect(Collectors.joining(","));

        Date now = new Date();

        // 拼装 JWT：设用户名、角色、权限、签发时间、过期时间，最后 HS256 签名
        return Jwts.builder()
                .setSubject(userDetails.getUsername())                        // sub: 用户名
                .claim(ROLES_KEY, roles)                                     // 自定义字段: 角色
                .claim(PERMS_KEY, perms)                                     // 自定义字段: 权限
                .setIssuedAt(now)                                            // iat: 签发时间
                .setExpiration(new Date(now.getTime() + expiration))         // exp: 过期时间
                .signWith(SignatureAlgorithm.HS256, secret)                  // 用密钥签名
                .compact();                                                  // 打成字符串
    }

    // ==================== 方法二：验证 JWT ====================

    /**
     * 验工牌：这个 JWT 的签名对不对？过期没？
     *
     * <h3>安全性</h3>
     * 验签通过 = JWT 确实是我们服务器签发的，没有被篡改过。
     * 如果有人改了 JWT 里的角色（比如把自己改成 ROLE_SUPER_ADMIN），签名就对不上了，这里会返回 false。
     *
     * @param token JWT 字符串
     * @return true = 合法工牌，false = 假的或过期了
     */
    public boolean validateToken(String token) {
        try {
            // 用密钥解析 JWT。如果签名不对、过期了、格式不对，都会抛异常
            Jwts.parser().setSigningKey(secret).parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            // 任何异常都算验证失败：签名不对、过期、格式错误……
            return false;
        }
    }

    // ==================== 方法三：从 JWT 中提取用户信息 ====================

    /**
     * 验证通过后，把 JWT 里存的信息拆出来，组装成 Security 认识的标准格式。
     *
     * <h3>为什么不查数据库？</h3>
     * JWT 签发时已经把用户名/角色/权限写进去了，验签通过就能信。
     * 这样每次请求不用去 DB 查用户权限，省一次查询。
     *
     * <h3>返回的 UsernamePasswordAuthenticationToken 是什么</h3>
     * 这是 Spring Security 规定的"已认证用户"标准包装格式。
     * 三个参数：用户名、密码（这里传 null，因为 JWT 里不存密码）、权限列表。
     *
     * @param token 已验证过的 JWT 字符串
     * @return 装好用户信息的 Authentication，可以放进 SecurityContextHolder
     */
    public Authentication getAuthentication(String token) {
        // 解析 JWT，拿到 payload 里的所有字段
        Claims claims = Jwts.parser().setSigningKey(secret)
                .parseClaimsJws(token).getBody();

        // 读出角色和权限的字符串（生成时用逗号拼的，这里按逗号拆开）
        String rolesStr = claims.get(ROLES_KEY, String.class);
        String permsStr = claims.get(PERMS_KEY, String.class);

        // 把角色和权限的字符串转成 Security 的标准权限对象列表
        Collection<GrantedAuthority> authorities = new java.util.ArrayList<>();

        if (rolesStr != null && !rolesStr.isEmpty()) {
            // "ROLE_SUPER_ADMIN,ROLE_ADMIN" → [SimpleGrantedAuthority("ROLE_SUPER_ADMIN"), ...]
            Arrays.stream(rolesStr.split(","))
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }
        if (permsStr != null && !permsStr.isEmpty()) {
            // "user:list,user:create" → [SimpleGrantedAuthority("user:list"), ...]
            Arrays.stream(permsStr.split(","))
                    .map(SimpleGrantedAuthority::new)
                    .forEach(authorities::add);
        }

        // 组装 Spring Security 的标准认证对象
        // 参数1: 用户名  参数2: 密码(null,不用存)  参数3: 权限列表
        return new UsernamePasswordAuthenticationToken(
                claims.getSubject(), null, authorities);
    }
}

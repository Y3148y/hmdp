package com.hmdp.token.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT 工具——只管 Access Token。
 *
 * <h3>和 hm-admin 的区别</h3>
 * Access Token 只管 15 分钟，不写角色权限——双 Token 场景下 Access Token 只证明"我是谁"，
 * 权限控制走 Refresh Token + Redis。Payload 极简：只放 username + 过期时间。
 */
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secret;

    /** Access Token 有效期（毫秒），默认 15 分钟 */
    @Value("${jwt.access-expiration}")
    private long accessExpiration;

    /**
     * 生成短期 Access Token（JWT 格式）。
     * Payload: { sub: "developer1", exp: now+15min }
     * 不存角色/权限——这是演示模块，只有"已登录/未登录"的区别。
     */
    public String generateAccessToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(username)                                        // 只有用户名
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + accessExpiration))  // 15min 后过期
                .signWith(SignatureAlgorithm.HS256, secret)
                .compact();
    }

    /**
     * 验签 Access Token。只检查签名和过期，不管权限。
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().setSigningKey(secret).parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 Access Token 中取用户名。验签通过后才能调。
     */
    public String getUsername(String token) {
        return Jwts.parser().setSigningKey(secret)
                .parseClaimsJws(token).getBody().getSubject();
    }
}

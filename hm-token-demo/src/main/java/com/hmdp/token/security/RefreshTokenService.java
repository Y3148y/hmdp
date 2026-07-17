package com.hmdp.token.security;

import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Refresh Token 管理——核心在于"存 Redis，可随时吊销"。
 *
 * <h3>为什么 Refresh Token 不用 JWT 而用随机 UUID？</h3>
 * Refresh Token 是"换新 Access Token 的凭证"，存 Redis 有两个好处：
 * 1. 可以主动删除 = 吊销 = 踢人下线
 * 2. 客户端发来 refreshToken，查一下 Redis 有没有，有就有效
 *
 * 如果用 JWT 做 Refresh Token，除非引入黑名单，否则签发了就收不回来。
 *
 * <h3>Redis Key 设计</h3>
 * refresh:token:{uuid} → username
 * 过期时间 = 7 天（和 refresh token 有效期一致）
 */
@Service
public class RefreshTokenService {

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    private final StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "refresh:token:";

    public RefreshTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登录时：生成一个随机 UUID 作为 Refresh Token，存 Redis，返回给客户端。
     *
     * @param username 谁登录了
     * @return 随机 UUID 字符串（这就是 refreshToken）
     */
    public String createRefreshToken(String username) {
        // 生成随机 UUID（无规律，不可伪造）
        String refreshToken = IdUtil.simpleUUID();

        // 存 Redis：key = refresh:token:uuid, value = username, 7天过期
        redisTemplate.opsForValue().set(
                KEY_PREFIX + refreshToken,
                username,
                refreshExpiration,
                TimeUnit.MILLISECONDS);

        return refreshToken;
    }

    /**
     * 刷新时：客户端发来 refreshToken，查 Redis 是否有效。
     * 有效 → 返回用户名（然后签发新的 accessToken）
     * 无效 → 返回 null（客户端需要重新登录）
     */
    public String validateAndGetUsername(String refreshToken) {
        String username = redisTemplate.opsForValue()
                .get(KEY_PREFIX + refreshToken);
        return username;  // null = 过期/不存在/被吊销
    }

    /**
     * 登出/吊销：删掉 Redis 里的 Refresh Token。
     * 之后客户端即使用 refreshToken 也换不到新 accessToken。
     * Access Token 本身还有效（15min 内），但过期后无法刷新 → 自然下线。
     */
    public void revokeRefreshToken(String refreshToken) {
        redisTemplate.delete(KEY_PREFIX + refreshToken);
    }
}

package com.hmdp.token.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    /** 短期 JWT，每次业务请求带这个 */
    private String accessToken;
    /** 长期 UUID 存 Redis，只用来换新的 accessToken */
    private String refreshToken;
    /** Token 类型固定 Bearer */
    private String tokenType;
    /** Access Token 有效期（秒），告诉客户端什么时候该刷新 */
    private long expiresIn;
}

package com.hmdp.admin.security;

import cn.hutool.core.util.StrUtil;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * JWT 认证过滤器。
 *
 * <h3>一句话</h3>
 * 门禁闸机——每个 HTTP 请求进来时，先看有没有带工牌（JWT），有就验真伪，真的就给你贴个临时胸牌（设 SecurityContext）。
 *
 * <h3>位置</h3>
 * 这个过滤器插在 Spring Security 过滤链的最前面。
 * 请求来了 → 先过这个闸机 → 再过后面的权限检查。
 *
 * <h3>继承 OncePerRequestFilter 的意思</h3>
 * 保证每个请求只过一次这个过滤器。不会因为内部转发重复执行。
 *
 * <h3>处理逻辑（大白话）</h3>
 * <ol>
 *   <li>看请求 Header 有没有 Authorization</li>
 *   <li>没有 → 不拦，放行（后面 Security 会因为没有认证信息返回 401）</li>
 *   <li>有，但不以 "Bearer " 开头 → 不拦，放行</li>
 *   <li>有，以 "Bearer " 开头 → 去掉前缀拿到纯 JWT → 验签</li>
 *   <li>验签失败 → 不拦，放行（后面 Security 返回 401）</li>
 *   <li>验签成功 → 解析出用户信息 → 存入 SecurityContextHolder → 放行</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // ==================== 常量 ====================

    /** HTTP 请求头里放 Token 的字段名。
     *  前端发请求时：Authorization: Bearer eyJhbG... */
    private static final String HEADER = "Authorization";

    /** JWT 的前缀，"Bearer " 后面才是真正的 JWT。
     *  Bearer 是"持票人"的意思，HTTP 标准约定。 */
    private static final String PREFIX = "Bearer ";

    // ==================== 依赖 ====================

    /** 工牌制作机/验证机——验签和解析 JWT 都靠它 */
    @Resource
    private JwtTokenProvider jwtTokenProvider;

    // ==================== 核心方法 ====================

    /**
     * 每个请求都会进这个方法。
     *
     * @param request  HTTP 请求（可以从中取 Header、URL 等）
     * @param response HTTP 响应（目前不修改它）
     * @param chain    过滤链——调 chain.doFilter() = "我检查完了，交给下一个"
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // 第1步：从请求头取 Authorization 字段
        String header = request.getHeader(HEADER);

        // 第2步：没有 Authorization 头，或者不是 Bearer 开头 → 不管，直接放行
        // （后面 Security 会因为没认证信息而返回 401）
        if (StrUtil.isBlank(header) || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // 第3步：去掉 "Bearer " 前缀，拿到纯 JWT 字符串
        // 例如 "Bearer eyJhbG..." → "eyJhbG..."
        String token = header.substring(PREFIX.length());

        // 第4步：验签 + 解析用户信息
        if (jwtTokenProvider.validateToken(token)) {
            // 验签通过 → 从 JWT 里拆出用户信息
            Authentication auth = jwtTokenProvider.getAuthentication(token);
            // 把用户信息存进 SecurityContextHolder（一个 ThreadLocal）
            // 相当于给你贴了个临时胸牌，后续的 Security 过滤器从胸牌上读你的权限
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        // 验签失败的话，不去设 Authentication，后面 Security 自然返回 401

        // 第5步：不管验签成功还是失败，都放行交给下一个过滤器
        // 如果没有认证信息，后面的 FilterSecurityInterceptor 会拦
        chain.doFilter(request, response);
    }
}

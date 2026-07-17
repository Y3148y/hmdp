package com.hmdp.token.security;

import cn.hutool.core.util.StrUtil;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
import java.util.Collections;

/**
 * Access Token 过滤器——和 hm-admin 的几乎一样。
 *
 * <h3>区别</h3>
 * 这里 JWT payload 里没有角色/权限，只有一个 username。
 * 鉴权时只检查"有没有带合法 Access Token"，不检查具体权限。
 * 因为这是演示模块，所有已登录用户权限一样——能调 /api/**。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    @Resource
    private JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // 取 Header 里的 Authorization
        String header = request.getHeader(HEADER);
        if (StrUtil.isBlank(header) || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // 取纯 JWT
        String token = header.substring(PREFIX.length());

        // 验签通过 → 设 Authentication
        if (jwtTokenProvider.validateToken(token)) {
            String username = jwtTokenProvider.getUsername(token);

            // 不设任何角色/权限——已登录就是唯一的"权限"
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    username, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}

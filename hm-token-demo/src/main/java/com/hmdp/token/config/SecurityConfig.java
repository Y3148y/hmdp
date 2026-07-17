package com.hmdp.token.config;

import com.hmdp.token.security.JwtAuthenticationEntryPoint;
import com.hmdp.token.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.annotation.Resource;

/**
 * Security 配置——比 hm-admin 简单得多。
 *
 * <h3>和 hm-admin 的区别</h3>
 * 没有 RBAC、没有动态 URL 鉴权、没有 @PreAuthorize。
 * 这里只需判断两件事：登录了没？Access Token 有效没？
 *
 * <h3>URL 规则</h3>
 * /auth/login    → 放行（登录才能拿到 Token）
 * /auth/refresh  → 放行（换新 Access Token，用 refreshToken 验证）
 * /auth/logout   → 放行（吊销 refreshToken，用 refreshToken 验证）
 * /api/**        → 需要登录（带有效 Access Token）
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Resource
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Resource
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 不暴露 AuthenticationManager——这个模块不手动调 authenticate()，
     * 登录时自己在 AuthController 里查内存用户、比密码、签 Token。
     */
    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(jwtAuthenticationEntryPoint);

        http.authorizeRequests()
                // 登录、刷新、登出 → 放行（都不需要 accessToken）
                .antMatchers("/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                // 预检请求 → 放行
                .antMatchers(HttpMethod.OPTIONS).permitAll()
                // API 接口 → 需要登录（带有效 Access Token）
                .antMatchers("/api/**").authenticated()
                // 兜底
                .anyRequest().authenticated();

        // JWT 过滤器插在最前面
        http.addFilterBefore(jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class);
    }
}

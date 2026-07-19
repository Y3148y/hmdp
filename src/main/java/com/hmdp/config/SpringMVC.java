package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RateLimitInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class SpringMVC implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 登录拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
        .addPathPatterns("/**")
        .excludePathPatterns(
            "/user/code",
            "/user/login",
            "/blog/hot",
            "/shop-type/**",
            "/voucher/**",
            "/shop/**",
            // 添加Swagger文档路径
            "/doc.html",
            "/webjars/**",
            "/v2/api-docs",
            "/swagger-resources/**"
        ).order(1);
        // 刷新token
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);

        registry.addInterceptor(new RateLimitInterceptor(stringRedisTemplate))
                .addPathPatterns("/voucher-order/seckill/**")
                .order(2);
    }
}

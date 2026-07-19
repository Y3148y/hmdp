package com.hmdp.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;

public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private static final DefaultRedisScript<Long> SCRIPT;
    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("seckill_rate_limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        Long userId = UserHolder.getUser().getId();
        long now = System.currentTimeMillis();

        Long result = stringRedisTemplate.execute(
                SCRIPT,
                Collections.emptyList(),
                RedisConstants.RATE_LIMIT_SECKILL_KEY,
                userId.toString(),
                String.valueOf(now),
                RedisConstants.RATE_LIMIT_SECKILL_WINDOW.toString(),
                RedisConstants.RATE_LIMIT_SECKILL_MAX_REQ.toString()
        );

        // log.debug("rate_limit userId={} result={}", userId, result);

        if (result != null && result == 1) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(MAPPER.writeValueAsString(
                    Result.fail("请求过于频繁，请稍后再试"))); //Jackson 的 ObjectMapper，专门把 Java 对象转 JSON 字符串
            return false;
        }

        return true;
    }
}

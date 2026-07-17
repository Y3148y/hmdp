package com.hmdp.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 未登录处理。
 *
 * <h3>一句话</h3>
 * 门禁报警器——当没带工牌（没登录）的人想进门（访问受保护接口），触发这个，返回 401 JSON。
 *
 * <h3>什么时候触发</h3>
 * 请求没有 Authorization Header（或 Token 无效），且访问的 URL 不是 permitAll 的路径。
 * Security 发现 SecurityContextHolder 里没有认证信息 → 触发这个 EntryPoint。
 *
 * <h3>默认行为 vs 自定义</h3>
 * Spring Security 默认的 EntryPoint 返回 302 重定向到 /login 页面。
 * 但我们是前后端分离项目，后端只返回 JSON，不做页面跳转。
 * 所以自定义这个类，返回 {"code": 401, "message": "未登录或 Token 已过期"}。
 *
 * <h3>AuthenticationEntryPoint 接口</h3>
 * 只有一个方法 commence()："认证失败时，怎么回应客户端？"
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /**
     * 认证失败时的处理：返回 401 JSON。
     *
     * <h3>参数</h3>
     * request  - HTTP 请求（可以读 URL、参数等，这里没用到）
     * response - HTTP 响应（我们要往里面写 401 状态码和 JSON 内容）
     * e        - 认证失败的异常（可以拿异常信息，这里统一返回固定文案）
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException e) throws IOException {

        // 设响应格式为 JSON
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // 设 HTTP 状态码为 401（Unauthorized = 未授权/未登录）
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        // 构造返回的 JSON 内容
        Map<String, Object> body = new HashMap<>();
        body.put("code", 401);
        body.put("message", "未登录或 Token 已过期");

        // 把 Map 序列化成 JSON 字符串，写入响应体
        // ObjectMapper 是 Jackson 的 JSON 工具，把 Java 对象 → JSON 字符串
        new ObjectMapper().writeValue(response.getWriter(), body);
    }
}

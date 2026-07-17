package com.hmdp.token.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟开放的 API 接口——第三方开发者拿 Access Token 调这些。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    /**
     * 模拟：获取数据接口。
     * 只有带有效 Access Token 的请求才能到达这里（SecurityConfig 已拦）。
     */
    @GetMapping("/data")
    public Object getData() {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "数据获取成功");
        List<Map<String, Object>> data = Arrays.asList(
                mapOf("id", 1, "name", "商品A", "price", 99.9),
                mapOf("id", 2, "name", "商品B", "price", 199.9),
                mapOf("id", 3, "name", "商品C", "price", 299.9)
        );
        result.put("data", data);
        return result;
    }

    private Map<String, Object> mapOf(Object... kvs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            map.put((String) kvs[i], kvs[i + 1]);
        }
        return map;
    }
}

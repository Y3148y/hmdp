package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://192.168.119.128:6379")
                .setTimeout(5000)                   // 命令超时 5s（默认 3s）
                .setConnectTimeout(10000)            // 连接超时 10s
                .setConnectionPoolSize(16)           // 连接池（默认 64，远程 Redis 不宜过大）
                .setConnectionMinimumIdleSize(4)     // 最小空闲连接（默认 24）
                .setRetryAttempts(3)                 // 失败重试 3 次
                .setRetryInterval(1500)              // 重试间隔 1.5s
                .setPingConnectionInterval(60000);   // 心跳间隔 60s（默认 30s）
        return Redisson.create(config);
    }
}

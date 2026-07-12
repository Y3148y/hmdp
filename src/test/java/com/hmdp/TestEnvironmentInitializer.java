package com.hmdp;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.Set;

/**
 * 集成测试环境隔离基类
 * <p>
 * 使用方式：集成测试类 extends 此类，并在 {@code @SpringBootTest} 中激活 test profile：
 * <pre>
 * {@code @SpringBootTest(properties = "spring.profiles.active=test")}
 * class MyIntegrationTest extends TestEnvironmentInitializer { ... }
 * </pre>
 * <p>
 * 生命周期：
 * <ol>
 *   <li>@BeforeAll  — 检查并自动创建 hmdp_test 数据库（如果不存在）</li>
 *   <li>@BeforeEach — 清空所有业务表，保证每次测试独立</li>
 *   <li>测试方法执行</li>
 *   <li>@AfterEach  — 清空 Redis db 1</li>
 * </ol>
 */
public abstract class TestEnvironmentInitializer {

    private static final String MYSQL_HOST = "127.0.0.1";
    private static final String MYSQL_PORT = "3306";
    private static final String MYSQL_USER = "root";
    private static final String MYSQL_PASSWORD = "MySQL901";
    private static final String TEST_DB_NAME = "hmdp_test";

    private static final String REDIS_HOST = "192.168.119.128";
    private static final int REDIS_PORT = 6379;
    private static final String STREAM_KEY = "stream.orders";
    private static final String GROUP_NAME = "g1";

    private static final String[] ALL_TABLES = {
            "tb_voucher_order",
            "tb_seckill_voucher",
            "tb_voucher",
            "tb_blog_comments",
            "tb_blog",
            "tb_follow",
            "tb_user_info",
            "tb_user",
            "tb_shop",
            "tb_shop_type"
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 在 Spring 上下文加载之前，检查并自动创建 hmdp_test 数据库
     */
    @BeforeAll
    static void initTestDatabase() {
        String adminUrl = String.format(
                "jdbc:mysql://%s:%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                MYSQL_HOST, MYSQL_PORT
        );
        try (Connection conn = DriverManager.getConnection(adminUrl, MYSQL_USER, MYSQL_PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE DATABASE IF NOT EXISTS " + TEST_DB_NAME
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
            );
            System.out.println("[TestEnv] 测试数据库 " + TEST_DB_NAME + " 已就绪");
        } catch (Exception e) {
            System.err.println("[TestEnv] 无法创建测试数据库 " + TEST_DB_NAME + ": " + e.getMessage());
            throw new RuntimeException("测试数据库初始化失败，请确认 MySQL 服务已启动", e);
        }

        // 在 Spring 容器启动前创建 Redis Stream 消费者组，避免 VoucherOderHandler
        // @PostConstruct 启动后台线程时因 NOGROUP 报错
        initRedisStreams();
    }

    /**
     * 使用 Lettuce 原生连接在 Spring 容器启动前创建 stream.orders 消费者组 g1。
     * <p>
     * 必须使用原生 Lettuce 而非 StringRedisTemplate，因为 @BeforeAll 是 static 方法，
     * Spring 容器尚未加载，无法注入 Bean。
     * <p>
     * 采用 XADD → XGROUP CREATE → XDEL 三步走，避免依赖 XGroupCreateArgs.mkstream
     * （不同 Lettuce 版本的 Builder API 差异较大，直接用基础命令更可靠）。
     */
    private static void initRedisStreams() {
        RedisURI uri = RedisURI.create("redis://" + REDIS_HOST + ":" + REDIS_PORT + "/1");
        RedisClient client = RedisClient.create(uri);
        try {
            RedisCommands<String, String> commands = client.connect().sync();
            // 清空测试 db，确保每次测试套件从干净状态开始
            try {
                commands.flushdb();
            } catch (Exception e) {
                System.err.println("[TestEnv] Redis flush 忽略: " + e.getMessage());
            }
            // XADD 创建 Stream 并写入一条 dummy 消息
            String msgId = commands.xadd(STREAM_KEY, Collections.singletonMap("_init", "1"));
            // XGROUP CREATE 基于已存在的 Stream 创建消费者组
            commands.xgroupCreate(XReadArgs.StreamOffset.from(STREAM_KEY, "0"), GROUP_NAME);
            // XDEL 删除 dummy 消息，Stream 和消费者组保留
            commands.xdel(STREAM_KEY, msgId);
            System.out.println("[TestEnv] Redis Stream " + STREAM_KEY + " 消费者组 " + GROUP_NAME + " 已就绪");
        } catch (Exception e) {
            System.err.println("[TestEnv] 创建消费者组失败: " + e.getMessage());
            throw new RuntimeException("Redis Stream 初始化失败，请确认 Redis 服务已启动", e);
        } finally {
            client.shutdown();
        }
    }

    /**
     * 每次测试前清空所有业务表，保证测试隔离
     */
    @BeforeEach
    void cleanTestTables() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : ALL_TABLES) {
            try {
                jdbcTemplate.execute("TRUNCATE TABLE " + table);
            } catch (Exception e) {
                System.out.println("[TestEnv] 跳过表 " + table + ": " + e.getMessage());
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    /**
     * 每次测试后清理 Redis db 1。
     * <p>
     * 注意：不能使用 FLUSHDB，因为这会删除 stream.orders 及其消费者组 g1，
     * 导致后台线程 VoucherOderHandler 在下一次 XREADGROUP 时遇到 NOGROUP 错误。
     * 改为选择性删除——保留 Stream 本身，仅清空消息并删除其余 key。
     */
    @AfterEach
    void cleanRedisData() {
        try {
            // 选择性删除：保留 stream.orders，避免后台线程遭遇 NOGROUP
            Set<String> keys = stringRedisTemplate.keys("*");
            if (keys != null && !keys.isEmpty()) {
                keys.remove(STREAM_KEY);
                if (!keys.isEmpty()) {
                    stringRedisTemplate.delete(keys);
                }
            }
            // 清空 Stream 中的旧消息（不删除 Stream 本身）
            try {
                stringRedisTemplate.opsForStream().trim(STREAM_KEY, 0);
            } catch (Exception ignored) {
                // Stream 可能尚不存在，忽略
            }
        } catch (Exception e) {
            System.err.println("[TestEnv] Redis 清理失败: " + e.getMessage());
        }
    }
}

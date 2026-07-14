package com.hmdp;

import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 集成测试环境隔离基类，解决三个中间件的测试数据污染问题。
 * <p>
 * <b>设计原则</b>：集成测试必须使用独立环境，测试数据不能进入生产库/生产 Redis。
 * <p>
 * <b>隔离策略</b>：
 * <table>
 *   <tr><th>中间件</th><th>隔离方式</th><th>生效 Profile</th></tr>
 *   <tr><td>MySQL</td><td>独立数据库 {@code hmdp_test}，每次测试前 TRUNCATE 所有表</td><td>test</td></tr>
 *   <tr><td>Redis</td><td>独立 db 1（生产用 db 0），每次测试前后均 FLUSHDB</td><td>test</td></tr>
 *   <tr><td>RabbitMQ</td><td>{@code auto-startup=false} 禁用监听器 + {@code @MockBean} 阻断投递</td><td>test</td></tr>
 * </table>
 * <p>
 * <b>使用方式</b>：
 * <pre>
 * {@code @SpringBootTest(properties = "spring.profiles.active=test")}
 * class MyIntegrationTest extends TestEnvironmentInitializer { ... }
 * </pre>
 * <p>
 * <b>生命周期</b>：
 * <ol>
 *   <li>{@code @BeforeAll} — 检查并创建 {@code hmdp_test} 数据库（不存在则创建），<b>在 Spring 上下文启动前执行</b></li>
 *   <li>{@code @BeforeEach} — TRUNCATE 所有业务表 → FLUSHDB 清空 Redis</li>
 *   <li>测试方法执行</li>
 *   <li>{@code @AfterEach} — FLUSHDB 清空 Redis + 移除 ThreadLocal 用户上下文（兜底清理）</li>
 * </ol>
 * <p>
 * <b>前提条件</b>：测试 Profile 的 {@code spring.datasource.url} 指向 {@code hmdp_test} 库
 * （见 {@code src/test/resources/application-test.yml}）。
 */
public abstract class TestEnvironmentInitializer {

    private static final String MYSQL_HOST = "127.0.0.1";
    private static final String MYSQL_PORT = "3306";
    private static final String MYSQL_USER = "root";
    private static final String MYSQL_PASSWORD = "MySQL901";
    private static final String TEST_DB_NAME = "hmdp_test";

    private static final String[] ALL_TABLES = {
            "tb_voucher_order_0", "tb_voucher_order_1",
            "tb_voucher_order_2", "tb_voucher_order_3",
            "tb_voucher_order_4", "tb_voucher_order_5",
            "tb_voucher_order_6", "tb_voucher_order_7",
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
     * 在 Spring 上下文加载之前，检查并创建 hmdp_test 数据库，同时从 hmdp 复制表结构。
     * <p>
     * 生产库 {@code hmdp} 需已存在（至少运行过一次应用）。通过 {@code CREATE TABLE ... LIKE}
     * 将表结构同步到测试库，确保两者 schema 一致。
     */
    @BeforeAll
    static void initTestDatabase() {
        String adminUrl = String.format(
                "jdbc:mysql://%s:%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                MYSQL_HOST, MYSQL_PORT
        );
        try (Connection conn = DriverManager.getConnection(adminUrl, MYSQL_USER, MYSQL_PASSWORD);
             Statement stmt = conn.createStatement()) {

            // 1. 创建测试库（不存在则创建）
            stmt.executeUpdate(
                    "CREATE DATABASE IF NOT EXISTS " + TEST_DB_NAME
                    + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
            );
            System.out.println("[TestEnv] 测试数据库 " + TEST_DB_NAME + " 已就绪");

            // 2. 从生产库 hmdp 复制表结构（表存在则跳过）
            for (String table : ALL_TABLES) {
                try {
                    stmt.executeUpdate(
                            "CREATE TABLE IF NOT EXISTS " + TEST_DB_NAME + "." + table +
                            " LIKE hmdp." + table
                    );
                } catch (Exception e) {
                    System.out.println("[TestEnv] 跳过建表 " + table + "（生产库中可能不存在）: " + e.getMessage());
                }
            }
            System.out.println("[TestEnv] 测试表结构已从 hmdp 同步");

        } catch (Exception e) {
            System.err.println("[TestEnv] 测试数据库初始化失败: " + e.getMessage());
            throw new RuntimeException("测试数据库初始化失败，请确认 MySQL 服务已启动且 hmdp 库存在", e);
        }
    }

    /**
     * 每次测试前清空所有业务表，保证测试隔离。
     * 先查 INFORMATION_SCHEMA 确认表存在，避免对不存在的表 TRUNCATE 产生日志噪音。
     */
    @BeforeEach
    void cleanTestTables() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : ALL_TABLES) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                    Integer.class, TEST_DB_NAME, table
            );
            if (exists != null && exists > 0) {
                jdbcTemplate.execute("TRUNCATE TABLE " + table);
            }
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    /**
     * 每次测试前清空 Redis 当前 db（flushDb），确保测试从干净状态开始。
     * <p>
     * 这是主要的清理点。{@code @AfterEach cleanRedisData()} 作为兜底，
     * 防止测试崩溃后残留数据影响后续测试。
     */
    @BeforeEach
    void cleanRedisBeforeTest() {
        try {
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.flushDb();
                return null;
            });
        } catch (Exception e) {
            System.err.println("[TestEnv] Redis 前置清理失败: " + e.getMessage());
        }
    }

    /**
     * 每次测试后清空 Redis 当前 db（flushDb），并移除 ThreadLocal 用户上下文。
     * <p>
     * flushDb 会清空当前连接所选的 db 中的所有 key。
     * 结合 {@code application-test.yml} 中 {@code spring.redis.database=1}，
     * 仅清空测试 db，不影响生产 db 0。
     */
    @AfterEach
    void cleanRedisData() {
        try {
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                connection.flushDb();
                return null;
            });
        } catch (Exception e) {
            System.err.println("[TestEnv] Redis 清理失败: " + e.getMessage());
        }
        // TRUNCATE MySQL 测试表，确保测试完不留脏数据
        cleanTestTables();
        // 兜底清理，防止测试方法忘记调用 UserHolder.removeUser()
        UserHolder.removeUser();
    }
}

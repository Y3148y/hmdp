package com.hmdp;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀压测数据准备工具（自包含，零 Spring 依赖）。
 *
 * 直连 MySQL（DriverManager）和 Redis（Lettuce），绕过 ShardingSphere 路由，
 * 直接读写物理单表 tb_voucher_order。不启动 Spring 上下文，秒级执行。
 *
 * 放 dev 的 src/test 下，mvn test -Dtest=... 即可执行。
 */
public class BenchmarkTool {

    private static final String DB_URL = "jdbc:mysql://127.0.0.1:3306/hmdp"
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "MySQL901";

    private static final String REDIS_URI = "redis://192.168.119.128:6379/0";
    private static final String LOGIN_TOKEN_PREFIX = "login:token:";
    private static final String SECKILL_STOCK_PREFIX = "seckill:stock:";
    private static final String SECKILL_ORDER_PREFIX = "seckill:order:";

    private static final int USER_COUNT = 1000;
    private static final int STOCK = 200;
    private static final String BENCH_PHONE_PREFIX = "199";

    static final String TOKEN_FILE = "benchmark/tokens.txt";
    static final String VOUCHER_ID_FILE = "benchmark/voucher-id.txt";

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 获取 Redis 连接 */
    private static RedisCommands<String, String> redis() {
        RedisClient client = RedisClient.create(REDIS_URI);
        StatefulRedisConnection<String, String> conn = client.connect();
        return conn.sync();
    }

    /** 获取 MySQL 连接 */
    private static Connection db() throws Exception {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    private static String now() {
        return LocalDateTime.now().format(DT);
    }

    /**
     * 第 1 步：批量插入 1000 压测用户 + 生成 token 写入 Redis + 导出 tokens.txt。
     */
    @Test
    void prepareUsersAndTokens() throws Exception {
        Files.createDirectories(Paths.get("benchmark"));

        // MySQL：批量插入用户
        try (Connection conn = db();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tb_user(phone, password, nick_name, create_time, update_time) "
                             + "VALUES (?, '', ?, ?, ?)")) {
            for (int i = 0; i < USER_COUNT; i++) {
                ps.setString(1, String.format("%s%08d", BENCH_PHONE_PREFIX, i));
                ps.setString(2, "bench_" + i);
                ps.setString(3, now());
                ps.setString(4, now());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        System.out.println("[BenchmarkTool] MySQL 已插入 " + USER_COUNT + " 个压测用户");

        // 查出压测用户的 id
        long[] ids = new long[USER_COUNT];
        try (Connection conn = db();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM tb_user WHERE phone LIKE ? ORDER BY phone")) {
            ps.setString(1, BENCH_PHONE_PREFIX + "%");
            try (ResultSet rs = ps.executeQuery()) {
                int i = 0;
                while (rs.next()) {
                    ids[i++] = rs.getLong("id");
                }
            }
        }

        // Redis：写 token（Hash 格式与 RefreshTokenInterceptor 读取一致）
        RedisCommands<String, String> redis = redis();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(TOKEN_FILE))) {
            for (int i = 0; i < USER_COUNT; i++) {
                String token = UUID.randomUUID().toString().replace("-", "");
                String key = LOGIN_TOKEN_PREFIX + token;
                Map<String, String> map = new HashMap<>();
                map.put("id", String.valueOf(ids[i]));
                map.put("nickName", "bench_" + i);
                map.put("icon", "");
                redis.hset(key, map);
                redis.expire(key, 600 * 60); // 600 分钟
                writer.write(token);
                writer.newLine();
            }
        }
        System.out.println("[BenchmarkTool] " + USER_COUNT + " 个 token 已写入 Redis 并导出到 " + TOKEN_FILE);
    }

    /**
     * 第 2 步：创建秒杀券（库存 200，时间窗覆盖当前时间前后 30 天）。
     * 分别写 tb_voucher、tb_seckill_voucher、Redis 库存三处。
     */
    @Test
    void prepareSeckillVoucher() throws Exception {
        String begin = LocalDateTime.now().minusDays(30).format(DT);
        String end = LocalDateTime.now().plusDays(30).format(DT);
        long voucherId;

        try (Connection conn = db();
             PreparedStatement psV = conn.prepareStatement(
                     "INSERT INTO tb_voucher(shop_id, title, sub_title, rules, pay_value, actual_value,"
                             + " type, status, create_time, update_time) VALUES (1, '压测秒杀券', 'benchmark', '仅供压测', 8000, 10000, 1, 1, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS);
             PreparedStatement psS = conn.prepareStatement(
                     "INSERT INTO tb_seckill_voucher(voucher_id, stock, begin_time, end_time, create_time, update_time) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {

            psV.setString(1, now());
            psV.setString(2, now());
            psV.executeUpdate();
            try (ResultSet rs = psV.getGeneratedKeys()) {
                rs.next();
                voucherId = rs.getLong(1);
            }

            psS.setLong(1, voucherId);
            psS.setInt(2, STOCK);
            psS.setString(3, begin);
            psS.setString(4, end);
            psS.setString(5, now());
            psS.setString(6, now());
            psS.executeUpdate();
        }

        // Redis 库存
        redis().set(SECKILL_STOCK_PREFIX + voucherId, String.valueOf(STOCK));

        Files.createDirectories(Paths.get("benchmark"));
        Files.write(Paths.get(VOUCHER_ID_FILE), String.valueOf(voucherId).getBytes());
        System.out.println("[BenchmarkTool] 秒杀券已创建，voucherId = " + voucherId + "（库存 " + STOCK + "）");
    }

    /**
     * 每轮压测后校验：订单数、去重、库存。
     */
    @Test
    void verifyResult() throws Exception {
        long voucherId = readVoucherId();

        int totalOrders, distinctUsers, mysqlStock;
        try (Connection conn = db()) {
            try (PreparedStatement ps = conn.prepareStatement(
                         "SELECT COUNT(*), COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id = ?")) {
                ps.setLong(1, voucherId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    totalOrders = rs.getInt(1);
                    distinctUsers = rs.getInt(2);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                         "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?")) {
                ps.setLong(1, voucherId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    mysqlStock = rs.getInt(1);
                }
            }
        }

        String redisStock = redis().get(SECKILL_STOCK_PREFIX + voucherId);

        System.out.println("========== 压测结果校验 ==========");
        System.out.println("订单总数:     " + totalOrders + "（期望 " + STOCK + "）");
        System.out.println("去重用户数:   " + distinctUsers + "（应等于订单总数）");
        System.out.println("MySQL 库存:   " + mysqlStock + "（期望 0）");
        System.out.println("Redis 库存:   " + redisStock + "（期望 0）");
        System.out.println(totalOrders == STOCK && distinctUsers == totalOrders && mysqlStock == 0
                ? ">>> 校验通过：无超卖、无少卖、无重复下单"
                : ">>> 校验异常（异步落库可能有延迟，等几秒后重跑）");
    }

    /**
     * 每轮压测后重置：库存恢复 200、删订单、清 Redis。
     */
    @Test
    void resetBenchmark() throws Exception {
        long voucherId = readVoucherId();

        try (Connection conn = db()) {
            try (PreparedStatement ps = conn.prepareStatement(
                         "UPDATE tb_seckill_voucher SET stock = ? WHERE voucher_id = ?")) {
                ps.setInt(1, STOCK);
                ps.setLong(2, voucherId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM tb_voucher_order WHERE voucher_id = ?")) {
                ps.setLong(1, voucherId);
                ps.executeUpdate();
            }
        }

        RedisCommands<String, String> redis = redis();
        redis.set(SECKILL_STOCK_PREFIX + voucherId, String.valueOf(STOCK));
        redis.del(SECKILL_ORDER_PREFIX + voucherId);
        System.out.println("[BenchmarkTool] 已重置（voucherId=" + voucherId + "）");
    }

    /**
     * 全部结束后一键清除：压测用户、token、券、订单、Redis key。
     */
    @Test
    void cleanAll() throws Exception {
        // MySQL：删用户（phone 前缀 + nick_name 双条件）
        int userDeleted;
        try (Connection conn = db();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM tb_user WHERE phone LIKE ? AND nick_name LIKE 'bench_%'")) {
            ps.setString(1, BENCH_PHONE_PREFIX + "%");
            userDeleted = ps.executeUpdate();
        }

        // Redis：删 token
        int tokenDeleted = 0;
        if (Files.exists(Paths.get(TOKEN_FILE))) {
            RedisCommands<String, String> redis = redis();
            for (String token : Files.readAllLines(Paths.get(TOKEN_FILE))) {
                if (!token.trim().isEmpty()) {
                    redis.del(LOGIN_TOKEN_PREFIX + token.trim());
                    tokenDeleted++;
                }
            }
        }

        // 删券、订单、Redis key
        if (Files.exists(Paths.get(VOUCHER_ID_FILE))) {
            long voucherId = readVoucherId();
            try (Connection conn = db()) {
                try (PreparedStatement ps = conn.prepareStatement(
                             "DELETE FROM tb_voucher_order WHERE voucher_id = ?")) {
                    ps.setLong(1, voucherId);
                    ps.executeUpdate();
                }
                try (PreparedStatement psS = conn.prepareStatement(
                             "DELETE FROM tb_seckill_voucher WHERE voucher_id = ?")) {
                    psS.setLong(1, voucherId);
                    psS.executeUpdate();
                }
                try (PreparedStatement psV = conn.prepareStatement(
                             "DELETE FROM tb_voucher WHERE id = ?")) {
                    psV.setLong(1, voucherId);
                    psV.executeUpdate();
                }
            }
            RedisCommands<String, String> redis = redis();
            redis.del(SECKILL_STOCK_PREFIX + voucherId);
            redis.del(SECKILL_ORDER_PREFIX + voucherId);
        }

        System.out.println("[BenchmarkTool] 清理完成：用户 " + userDeleted + " 个，token " + tokenDeleted + " 个");

        /*
         * RabbitMQ 清队列。
         *
         * 为什么必须清：cleanAll 删了 MySQL 数据，但队列里可能残留秒杀消息。
         * 消费者拿到消息 → 查 DB 发现数据不存在 → 抛异常 → basicNack(requeue=true)
         * → 消息重新入队 → 立即再次投递 → 再次失败 → 死循环（实测一条消息重试了 58,504 次）。
         *
         * requeue=true 只适合瞬时故障（DB 重连），数据永久不存在应 requeue=false 直接丢弃。
         * 但当前消费者没有做这个区分，所以 cleanup 侧需要清队列兜底。
         */
        purgeRabbitMq();
    }

    private void purgeRabbitMq() {
        String auth = Base64.getEncoder().encodeToString("guest:guest".getBytes());
        String[] queues = {
            "/api/queues/%2F/seckill.order.queue/contents",
            "/api/queues/%2F/seckill.order.dlq/contents"
        };
        for (String queuePath : queues) {
            try {
                URL url = new URL("http://localhost:15672" + queuePath);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                conn.setRequestProperty("Authorization", "Basic " + auth);
                int code = conn.getResponseCode();
                conn.disconnect();
                System.out.println("[BenchmarkTool] MQ 队列已清空 " + queuePath + "（HTTP " + code + "）");
            } catch (Exception e) {
                System.out.println("[BenchmarkTool] MQ 队列清空失败 " + queuePath + ": " + e.getMessage());
            }
        }
    }

    private long readVoucherId() throws Exception {
        return Long.parseLong(new String(Files.readAllBytes(Paths.get(VOUCHER_ID_FILE))).trim());
    }
}

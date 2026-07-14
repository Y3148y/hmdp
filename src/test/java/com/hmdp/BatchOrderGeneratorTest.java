package com.hmdp;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 分表批量数据生成器 + 分布验证
 * <p>
 * 向 tb_voucher_order 逻辑表插入 10 万条订单，由 ShardingSphere 按 id % 8
 * 路由到 8 张物理表，最后用 JDBC 直连每张分表 COUNT 验证数据均匀分布。
 * <p>
 * 前提：8 张物理表 tb_voucher_order_0 ~ tb_voucher_order_7 已建好，Redis 可用。
 * <p>
 * 运行：mvn test -Dtest=BatchOrderGeneratorTest
 */
@SpringBootTest(properties = "spring.profiles.active=test")
class BatchOrderGeneratorTest extends TestEnvironmentInitializer {

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TOTAL = 100;
    private static final int REPORT_INTERVAL = 5_000;

    @Test
    void generate100kOrders() {
        // 1. 记录插入前各分表计数
        Map<Integer, Long> beforeCounts = countAllShards();
        System.out.println("========== 插入前各分表记录数 ==========");
        beforeCounts.forEach((shard, cnt) ->
                System.out.println("  tb_voucher_order_" + shard + ": " + cnt));

        // 2. 批量插入
        long start = System.currentTimeMillis();
        System.out.println("\n开始插入 " + TOTAL + " 条订单...");

        for (int i = 0; i < TOTAL; i++) {
            VoucherOrder order = new VoucherOrder();
            order.setId(redisIdWorker.nextId("order"));
            order.setUserId((long) (10001 + i % 10_000));
            order.setVoucherId((long) (1 + i % 100));
            order.setPayType(1);
            order.setStatus(1);
            order.setCreateTime(LocalDateTime.now());

            voucherOrderMapper.insert(order);

            int done = i + 1;
            if (done % REPORT_INTERVAL == 0) {
                long elapsed = System.currentTimeMillis() - start;
                double rate = done * 1000.0 / elapsed;
                System.out.printf("  已插入 %d / %d (%d%%)  %.0f 条/秒\n",
                        done, TOTAL, done * 100 / TOTAL, rate);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("\n插入完成，总耗时 %.1f 秒 (%.0f 条/秒)\n",
                elapsed / 1000.0, TOTAL * 1000.0 / elapsed);

        // 3. 验证分布
        System.out.println("\n========== 插入后各分表记录数 ==========");
        Map<Integer, Long> afterCounts = countAllShards();
        afterCounts.forEach((shard, cnt) ->
                System.out.println("  tb_voucher_order_" + shard + ": " + cnt));

        // 4. 统计
        System.out.println("\n========== 分表分布验证 ==========");
        long totalInserted = 0;
        for (int i = 0; i < 8; i++) {
            long added = afterCounts.get(i) - beforeCounts.get(i);
            totalInserted += added;
            System.out.printf("  tb_voucher_order_%d: 新增 %d 条\n", i, added);
        }

        System.out.println("\n总计新增: " + totalInserted + " 条");
        System.out.println("期望分布: 每表约 " + (TOTAL / 8) + " 条");
    }

    private Map<Integer, Long> countAllShards() {
        Map<Integer, Long> counts = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order_" + i, Long.class);
            counts.put(i, count != null ? count : 0L);
        }
        return counts;
    }
}

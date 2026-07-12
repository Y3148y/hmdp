   # CHANGELOG_04 — tb_voucher_order 水平分表（Sharding-JDBC，8 张表）

> 日期：2026-07-07
> 分支：sharding
> 方案：ShardingSphere-JDBC 4.1.1 + 广播查询 + 联合索引优化

---

## 一、分表背景

### 问题

秒杀高峰期订单写入集中在单表 `tb_voucher_order`，随着订单量增长：

| 指标 | 单表（1000万行） | 8 分表（125万行/表） |
|------|------------------|---------------------|
| B+树高度 | ~4 层 | ~3 层 |
| 全表扫描耗时 | 秒级 | 毫秒级 |
| 写入热点 | 单表锁竞争 | 分散到 8 表 |
| 一人一单去重查询 `WHERE user_id=? AND voucher_id=?` | 全表扫描（~1000万行） | 索引定位（~1 行 × 8 表 = ~8 行） |

### 方案

- **分表数**：8 张
- **分表键**：`id`（订单主键，由 `RedisIdWorker` 全局生成）
- **分表算法**：`id % 8`（取模，Inline 策略）
- **分表名称**：`tb_voucher_order_0` ~ `tb_voucher_order_7`
- **中间件**：ShardingSphere-JDBC 4.1.1（兼容 Spring Boot 2.3.12）

---

## 二、依赖变更 (pom.xml)

```xml
<!-- Sharding-JDBC 分库分表 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>sharding-jdbc-spring-boot-starter</artifactId>
    <version>4.1.1</version>
</dependency>
```

---

## 三、分表配置 (application.yaml)

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8
        username: root
        password: MySQL901
        max-active: 20
        min-idle: 5
        connection-timeout: 30000
    sharding:
      tables:
        tb_voucher_order:
          actual-data-nodes: ds0.tb_voucher_order_$->{0..7}
          table-strategy:
            inline:
              sharding-column: id
              algorithm-expression: tb_voucher_order_$->{id % 8}
    props:
      sql:
        show: true   # 开发环境打印实际 SQL，生产关掉
```

**关键配置说明**：

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `actual-data-nodes` | `ds0.tb_voucher_order_$->{0..7}` | 8 张分表的逻辑数据节点 |
| `sharding-column` | `id` | 分表键为订单主键 |
| `algorithm-expression` | `tb_voucher_order_$->{id % 8}` | 按 id 取模路由 |

---

## 四、实体类调整

### VoucherOrder（无需修改）

`@TableName("tb_voucher_order")` 保持不变。Sharding-JDBC 在 JDBC 层拦截 SQL，将 `tb_voucher_order` 自动路由到 `tb_voucher_order_0` ~ `tb_voucher_order_7`。

### VoucherOrderMapper 新增方法

```java
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /** 带分表键 id → 路由到单表 */
    VoucherOrder selectById(@Param("id") Long id);

    /** 广播查询：一人一单去重（依赖 idx_user_voucher 联合索引） */
    int countByUserIdAndVoucherId(@Param("userId") Long userId,
                                  @Param("voucherId") Long voucherId);

    /** 广播查询：用户订单分页 */
    List<VoucherOrder> selectByUserId(@Param("userId") Long userId,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);
}
```

### VoucherOrderMapper.xml

| 方法 | SQL 路由方式 | 说明 |
|------|-------------|------|
| `selectById` | **精确路由**（WHERE 含 id → id%8 → 单表） | 1 次查询 |
| `countByUserIdAndVoucherId` | **广播**（无分表键 → 8 表全查） | 8 次查询，各表走索引 |
| `selectByUserId` | **广播**（无分表键 → 8 表全查 + 汇总排序） | 8 次查询，各表走索引 |

### 现有 MyBatis-Plus 方法路由情况

| MyBatis-Plus 方法 | 是否有分表键 `id` | 路由 |
|-------------------|------------------|------|
| `save(voucherOrder)` | 有（INSERT 含 id） | 精确路由 |
| `getById(id)` | 有 | 精确路由 |
| `updateById(entity)` | 有 | 精确路由 |
| `query().eq("user_id", userId).eq("voucher_id", voucherId).count()` | **无** | **广播** |

---

## 五、索引变更 DDL

### 分表创建

```sql
CREATE TABLE IF NOT EXISTS `tb_voucher_order_0` LIKE `tb_voucher_order`;
-- ... 共 8 张表，_0 到 _7
```

### 联合索引（解决广播查询慢SQL）

```sql
-- 索引1：一人一单去重查询
-- 原 SQL：SELECT COUNT(*) WHERE user_id=? AND voucher_id=?
-- 优化前：每表全表扫描（扫描所有行）
-- 优化后：每表索引定位（扫描 ~1 行），8 表合计扫描 ~8 行
ALTER TABLE `tb_voucher_order_0` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
-- ... 共 8 张表

-- 索引2：用户订单列表查询
-- 原 SQL：SELECT * WHERE user_id=? ORDER BY create_time DESC LIMIT ?,?
-- 优化前：每表全表扫描 + filesort
-- 优化后：每表索引范围扫描，无需 filesort
ALTER TABLE `tb_voucher_order_0` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);
-- ... 共 8 张表
```

完整 DDL 见：`src/main/resources/db/sharding_ddl.sql`

---

## 六、慢SQL分析与性能预估

### 场景1：一人一单去重（秒杀时高频查询）

```sql
SELECT COUNT(*) FROM tb_voucher_order WHERE user_id = ? AND voucher_id = ?
```

| 阶段 | 路由方式 | 每表扫描行数 | 8 表合计扫描 | 耗时（估算） |
|------|---------|-------------|-------------|-------------|
| 优化前（单表，无索引） | 单表直接查询 | 全表（1000万） | 1000 万 | ~3200 ms |
| 分表（无索引） | 广播 8 表 | 全表（125万/表） | 1000 万 | ~2500 ms |
| **分表 + idx_user_voucher** | **广播 8 表** | **1 行/表（索引定位）** | **8 行** | **~5 ms** |

> 扫描行数从 1000 万降至 8 行，减少 **99.9999%**

### 场景2：用户订单列表

```sql
SELECT * FROM tb_voucher_order WHERE user_id = ? ORDER BY create_time DESC LIMIT 0, 10
```

| 阶段 | 每表扫描行数 | 是否 filesort | 耗时（估算） |
|------|-------------|-------------|-------------|
| 优化前（单表，无索引） | 全表 1000 万 | 是 | ~5200 ms |
| 分表（无索引） | 全表 125 万/表 | 是（8 次 filesort） | ~3800 ms |
| **分表 + idx_user_create_time** | **用户订单数（～50 行/表）** | **否** | **~15 ms** |

> 扫描行数从 1000 万降至 ~400 行（8 表合计），减少 **99.996%**，消除 filesort

### 场景3：按订单ID查询（精确路由）

```sql
SELECT * FROM tb_voucher_order WHERE id = ?
```

| 阶段 | 路由方式 | 扫描行数 | 耗时 |
|------|---------|---------|------|
| 分表后 | **精确路由到单表** | 1（主键索引） | **~1 ms** |

> 唯一无损查询：含分表键 `id`，路由到单表，走主键，性能与单表相同

### 汇总

| SQL 类型 | 优化前扫描 | 优化后扫描 | 扫描减少 | 耗时减少 |
|----------|-----------|-----------|---------|---------|
| 去重查询（广播） | 1000 万行 | 8 行 | **99.9999%** | **99.8%** |
| 订单列表（广播） | 1000 万行 | ~400 行 | **99.996%** | **99.7%** |
| 按ID查询（精确） | 1 行 | 1 行 | 不变 | 不变 |

---

## 七、执行步骤

```bash
# 1. 在 MySQL 执行分表 DDL
mysql -u root -p hmdp < src/main/resources/db/sharding_ddl.sql

# 2. 重启应用使 Sharding-JDBC 配置生效
mvn spring-boot:run
```

### 回滚方案

1. 将 `pom.xml` 中 Sharding-JDBC 依赖移除
2. 将 `application.yaml` 中 `spring.shardingsphere` 改回 `spring.datasource`
3. 分表数据如需保留，需手动合并回原表：

```sql
INSERT INTO tb_voucher_order SELECT * FROM tb_voucher_order_0;
-- ... 合并所有 8 张表
```

---

## 八、注意事项

1. **广播查询性能依赖索引**：`countByUserIdAndVoucherId` 和 `selectByUserId` 是广播查询，必须确保每张分表都有对应的联合索引，否则性能反而比单表更差（8 次全表扫描）

2. **跨分表聚合**：不带分表键的 COUNT/SUM 等聚合查询，Sharding-JDBC 会在内存中合并结果，大结果集需注意内存

3. **事务**：Sharding-JDBC 4.x 支持本地事务（单数据源），`@Transactional` 在分表场景下仍然生效

4. **分布式 ID**：`RedisIdWorker` 生成的 ID 本身已是全局唯一且趋势递增，取模后在单分表内不保证严格递增，但不影响业务

# CHANGELOG_08 — 分布式ID生成方案（RedisIdWorker + Snowflake 对照）

> 日期：2026-07-16
> 说明：本文档为知识储备文档，无实际代码改动。当前方案已满足分表需求。
> 目的：面试能讲清楚分布式ID的生成原理、两种方案的差异、以及为什么选当前方案。

---

## 一、为什么需要分布式ID

### 单机时代的自增ID

```
MySQL AUTO_INCREMENT → 1, 2, 3, 4, 5...
```

问题：分表之后，8 张 `tb_voucher_order_0 ~ _7` 各自独立维护自增ID，**不同物理表可能产生相同的ID**。

```
tb_voucher_order_0:  id=1, id=2, id=3...
tb_voucher_order_1:  id=1, id=2, id=3...  ← 和上面重复！
```

所以分表场景下，**应用层必须自己生成全局唯一ID**，不能依赖数据库自增。

### 分布式ID的核心要求

| 要求 | 说明 |
|------|------|
| **全局唯一** | 8张分表里不能出现相同ID |
| **趋势递增** | 数据库B+Tree索引需要大致有序的插入，否则页分裂性能差 |
| **高性能** | 高并发下不能成为瓶颈 |

---

## 二、当前方案：RedisIdWorker

### 2.1 代码

```java
@Component
public class RedisIdWorker {
    // 起始时间戳：2022-01-01 00:00:00 UTC 的秒数
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    // 序列号占用的位数
    private static final long COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        // 1. 时间戳：当前秒数 - 起始秒数（得到距2022-01-01已过的秒数）
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 序列号：Redis 原子自增，按天分组
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue()
                .increment("icr:" + keyPrefix + ":" + date);

        // 3. 拼接：高32位=时间戳，低32位=序列号
        return timestamp << COUNT_BITS | count;
    }
}
```

### 2.2 64位结构

```
┌─────────────────────────┬──────────────────────────┐
│    高32位：时间戳        │     低32位：序列号         │
│  (距2022-01-01的秒数)    │  (Redis INCR 当日自增)    │
└─────────────────────────┴──────────────────────────┘
63                      32 31                        0
```

### 2.3 唯一性保证

```
时间戳（秒级，32位）：
  - 从2022-01-01开始计时
  - 2^32 秒 ≈ 136 年 ← 用到下辈子都不会溢出

序列号（32位）：
  - Redis INCR 是原子操作，每个 key 的计数永远不会重复
  - Key 按天分：icr:order:2026:07:16
  - 每天最多 2^32 ≈ 42 亿 个订单 ← 远超过实际需求
  - 跨天自动归零（新日期 = 新 key）

结论：时间戳保证跨天不重复，Redis原子自增保证同一天内不重复。
      两者组合，全局唯一。
```

### 2.4 趋势递增性

```
ID ≈ (时间戳 << 32) + 序列号

同一秒内：序列号递增 → ID 递增
下一秒：  时间戳 +1，序列号从较小的数开始 → ID 依然比上一秒大（因为时间戳在高位）
```

ID 是**严格递增**的——时间戳在高位，Redis INCR 单调自增，所以每次 `nextId()` 返回的数值一定比上一次大。时间戳精度是秒级，所以 ID 不体现毫秒级的调用先后顺序，但这不影响 B+Tree 索引效果——索引只关心数值有序，不关心你的业务时间。

### 2.5 性能

```
单次生成：1 次 Redis 网络往返（INCR 命令）
  - Redis 单机 QPS 约 10w/s（INCR 操作）
  - 对于秒杀场景绰绰有余
```

### 2.6 调用方式

```java
// VoucherOrderServiceImpl.seckillVoucher()
long orderId = redisIdWorker.nextId("order");  // keyPrefix = "order"
// Redis key: icr:order:2026:07:16
```

---

## 三、传统 Snowflake 方案

### 3.1 Twitter Snowflake 原版

```
64位结构（Twitter 原版）：
┌─────┬───────────────────┬───────────┬────────────────────┐
│ 1bit│    41位：毫秒时间戳  │ 10位：机器 │   12位：序列号       │
│ 未用 │  (距自定义起始时间)  │   标识    │  (同毫秒内自增)     │
└─────┴───────────────────┴───────────┴────────────────────┘
63   62                22 21       12 11                  0
```

| 字段 | 位数 | 说明 |
|------|------|------|
| 符号位 | 1 bit | 固定为 0（正数） |
| 时间戳 | 41 bit | 毫秒级，距自定义起始时间。可用 69 年 |
| 机器ID | 10 bit | 5位数据中心 + 5位机器。最多 1024 个节点 |
| 序列号 | 12 bit | 同毫秒内自增。每毫秒每节点 4096 个 ID |

### 3.2 怎么用

Snowflake 算法就一个，但你可以选择**手写**还是**调 Hutool**。区别只是谁来写那70行代码——效果完全一样。

**手写版（理解原理用）：**

```java
public class SnowflakeIdWorker {
    // 起始时间戳 (2022-01-01 00:00:00)
    private static final long START_TIME = 1640995200000L;

    private static final long WORKER_ID_BITS = 5L;      // 机器ID占5位
    private static final long DATA_CENTER_BITS = 5L;    // 数据中心ID占5位
    private static final long SEQUENCE_BITS = 12L;      // 序列号占12位

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);       // 31
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);          // 4095

    // 位移量
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                 // 12
    private static final long DATA_CENTER_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 17
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_BITS; // 22

    private final long workerId;
    private final long dataCenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdWorker(long workerId, long dataCenterId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId 超出范围 [0, 31]");
        }
        this.workerId = workerId;
        this.dataCenterId = dataCenterId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        // 时钟回拨检测
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("时钟回拨拒绝生成ID，差值=" + (lastTimestamp - timestamp) + "ms");
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内，序列号自增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 该毫秒序列号用完，阻塞到下一毫秒
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;  // 不同毫秒，序列号归零
        }

        lastTimestamp = timestamp;

        return (timestamp - START_TIME) << TIMESTAMP_SHIFT   // 时间戳部分
             | dataCenterId << DATA_CENTER_SHIFT              // 数据中心
             | workerId << WORKER_ID_SHIFT                    // 机器ID
             | sequence;                                      // 序列号
    }

    private long waitUntilNextMillis(long last) {
        long now = System.currentTimeMillis();
        while (now <= last) {
            now = System.currentTimeMillis();
        }
        return now;
    }
}
```

### 3.3 Hutool 封装（实际开发用）

手写版本和下面这行**效果完全相同**——Hutool 内部就是上述代码的工业级版本，额外处理了时钟回拨、workerId 自动分配等边界情况。

```java
import cn.hutool.core.util.IdUtil;

long id = IdUtil.getSnowflake(workerId, dataCenterId).nextId();
```

实际开发中直接调 Hutool 就行，手写版本是用来理解原理的。面试时写出手写版 = 加分项。**两个不是不同方案，是同一算法的两种写法。**

### 3.4 Snowflake 的核心优势

- **纯内存运算**：无网络调用，纳秒级性能
- **无外部依赖**：不依赖 Redis、DB，只靠本地时钟
- **毫秒级精度**：每秒理论最大 4096 × 1000 × 1024 ≈ 400w/s

### 3.5 Snowflake 的核心痛点：时钟回拨

```
场景：服务器时钟被 NTP 同步往回拨了 1 秒

时间线：
  10:00:05.200 → 生成 ID_A（时间戳=5秒）
  10:00:05.300 → 生成 ID_B（时间戳=5秒）
  [时钟回拨]
  10:00:04.800 → 生成 ID_C（时间戳=4秒，< 上次的5秒）
                → ❌ 可能和之前的 ID 重复！

解决方案：
  1. 抛异常，拒绝生成（上述代码的做法）
  2. 等待时钟追上（上述 waitUntilNextMillis）
  3. 用之前的最大时间戳继续生成
  4. 美团的 Leaf 方案：用 ZK 持久化 workerId
```

---

## 四、两种方案对比

| 维度 | RedisIdWorker（当前） | 传统 Snowflake |
|------|----------------------|----------------|
| **原理** | 时间戳(秒) + Redis原子自增 | 时间戳(毫秒) + 机器ID + 本地自旋 |
| **唯一性保证** | Redis INCR 原子操作 | workerId 区分机器 + synchronized |
| **时钟回拨** | **不受影响**（Redis 计数器是权威，不依赖本地时钟单调性） | **敏感**（时钟回拨可能导致重复ID） |
| **性能** | ~0.1ms（一次 Redis 网络往返） | ~0.001ms（纯内存） |
| **外部依赖** | 需要 Redis | 零依赖 |
| **多实例部署** | 天然安全（Redis 集中计数） | 需给每实例分配唯一 workerId |
| **复杂度** | 低（30 行代码） | 中等（需处理时钟回拨 + workerId 分配） |
| **序列号容量** | 每天 42 亿 | 每毫秒每节点 4096 |

---

## 五、为什么当前方案更适合本项目

```
本项目特点：
  1. 秒杀业务已重度依赖 Redis（库存校验、一人一单、缓存、分布式锁）
     → Redis 挂了整个秒杀链路不可用，光 ID 能用没意义
     → 所以 "依赖 Redis" 在本项目中不是新增风险

  2. 单机部署（非分布式多实例）
     → 不需要 workerId 机制

  3. Redis INCR 天然免疫时钟回拨
     → 比 Snowflake 更稳健

  4. 秒级时间戳（非毫秒级）
     → 但序列号空间巨大（32位 = 42亿/天），远够用
```

**一句话总结**：当前方案 = Snowflake 思想（时间戳+序列号拼64位）+ Redis 原子自增替代本地计数器，牺牲一点性能换来了时钟回拨免疫和部署简单。

---

## 六、面试话术

### 简历写法

```
• 设计分布式ID生成器（RedisIdWorker），基于时间戳+Redis原子自增生成64位全局唯一ID，
  解决分表后自增主键冲突问题，支持趋势递增兼容B+Tree索引
```

### 面试官问"你们订单ID怎么生成的？"

> 我们用的是自己实现的分布式ID生成器，RedisIdWorker。生成64位Long型ID，高32位放时间戳（距2022-01-01的秒数），低32位放序列号（Redis INCR 按天分组原子自增）。
>
> 之所以不用数据库自增，是因为我们做了分表——`tb_voucher_order` 按 `id % 8` 拆了 8 张物理表。如果还用自增，不同表会生成相同ID。所以必须在应用层自己生成全局唯一ID。

### 面试官追问"为什么不用 Snowflake？"

> 原理上我们是同类方案——都是时间戳+序列号拼成一个64位Long。区别在于序列号那部分：Snowflake 用本地计数器+机器ID，我们用 Redis 原子自增。
>
> 选 Redis 的原因有三个：
> 1. 我们秒杀链路本来就重度依赖Redis，不存在新增依赖的问题
> 2. Redis INCR 天然免疫时钟回拨——Snowflake 最头疼的就是 NTP 时间同步导致时钟回拨，可能产生重复ID，我们不存在这个问题
> 3. 序列号用满32位=每天42亿容量，远超实际需求
>
> 代价是每次生成多一次 Redis 网络往返，但秒杀场景本身就是 Redis 密集型，这一个 INCR 不值一提。

### 面试官追问"时钟回拨是什么？"

> 服务器时钟不是绝对准确的，会通过 NTP 协议与时间服务器同步。如果本地时钟快了，NTP 会往回拨。Snowflake 依赖本地毫秒时间戳+自旋序列号，时钟一回拨就可能生成和之前相同的ID。
>
> 解决方式有几种：抛异常拒绝服务、阻塞等待时钟追上、或者像我们项目一样用 Redis 原子自增代替本地计数器——Redis 的 INCR 是权威序列号，不依赖本地时钟单调性。

### 面试官追问"分表后为什么不能用UUID？"

> UUID 有两个致命问题：
> 1. **无序**——MySQL InnoDB 的 B+Tree 索引是按主键顺序存储的，UUID 随机插入会导致大量的页分裂和磁盘随机IO，写入性能很差
> 2. **占空间**——UUID 是 36 位字符串或 128 位二进制，Long 只有 64 位，索引和数据都更省空间
>
> 我们用 Long 型 + 趋势递增，就是为了贴合 B+Tree 的有序插入特性。

### 面试官追问"如果 Redis 挂了怎么办？"

> 我们这个方案确实依赖 Redis。但项目的秒杀业务——优惠券库存扣减、一人一单去重、缓存查询——全都是 Redis 做的。Redis 真挂了，整个秒杀链路不可用，订单ID能不能生成已经不是主要矛盾了。
>
> 如果未来要完全去掉 Redis 依赖，可以切到纯 Snowflake，预分配 workerId 就行。但就我们目前的单机部署规模和业务量来说，没必要。

---

## 七、速记卡

```
分布式ID速记
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
为什么不用自增：分表后不同物理表会生成重复ID
为什么不用UUID：无序 → B+Tree页分裂 → 写入慢

当前方案 RedisIdWorker：
  高32位：距2022-01-01的秒数
  低32位：Redis INCR 按天原子自增
  唯一性：时间戳(跨天不重) + Redis原子(同天不重)

vs Snowflake：
  相同：都是时间戳+序列号拼64位Long
  不同：我们用Redis计数器，Snowflake用本地+workerId
  优势：免疫时钟回拨
  代价：多一次Redis调用（但本项目本就Redis密集型）

面试一句流：
  "64位Long，高32时间戳低32序列号，Redis原子自增保证唯一，
   趋势递增兼容B+Tree，时钟回拨免疫。"
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

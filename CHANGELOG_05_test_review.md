# 测试规范审查报告 — VoucherOrder 秒杀测试

> 审查日期：2026-07-07
> 审查人：项目经理视角
> 涉及文件：4 个测试类

---

## 一、误报事故分析

用户在未回退 Stream 源码的情况下运行旧集成测试，测试 "通过"（4/4 绿），日志却显示 RabbitMQ 消费者在工作：

```
收到秒杀消息: orderId=611808985984535128, userId=5006, voucherId=999
```

### 为什么会 "通过"？

旧测试 `VoucherOrderIntegrationTest` 是为 Redis Stream 版本写的，但源码已升级为 RabbitMQ 版本。每个断言的实际行为：

| 断言 | 预期（Stream 版） | 实际（MQ 版） | 结果 |
|------|------------------|-------------|:--:|
| `assertTrue(success <= 10)` | Lua 扣库存正确 | Lua 扣库存正确（MQ 版 Lua 也有） | 真绿 |
| `assertEquals("0", stock)` | incrby 10 次到 0 | incrby 10 次到 0 | 真绿 |
| `orderSetSize == success` | SADD 10 个用户 | SADD 10 个用户 | 真绿 |
| `return 排队中` vs `return orderId` | 断言 `result.getData()` 是 Long | 实际是 String | **断言不匹配但没检查类型** |
| `opsForStream().size() >= 1` | seckill.lua XADD 写 Stream | seckill_mq.lua 不写 Stream | **靠旧数据通过（脏数据）** |

### 暴露的四个问题

1. **架构感知盲区** — 测试不知道消息队列从 Stream 变成了 RabbitMQ
2. **脏数据作弊** — Stream 断言靠上次运行残留的旧消息混过去
3. **DB 静默污染** — 消费者写入了 10 条真实订单（`voucherId=999`），无清理
4. **覆盖假象** — 全绿不意味着全对，异步消息链路完全没被测到

---

## 二、Mock vs 集成测试：项目经理必须知道的边界

### Mock 能测什么

Mock 验证的是 **Java 代码的正确性**——你的业务逻辑 if-else 分支对不对、异常处理对不对、返回值格式对不对。

### Mock 不能测什么

| 中间件 | Mock 无法验证的场景 | 如果只靠 Mock 上线会发生什么 |
|--------|-------------------|--------------------------|
| **Redis** | Lua 脚本原子性、50 并发下 incrby 是否正确、SETNX 过期淘汰 | 超卖：2 个人同时读到 stock=1，都扣减成功 |
| **RabbitMQ** | 消息持久化、消费者 ACK/NACK、DLX 死信路由、TTL 超时关单 | 消息丢失：MQ 宕机后订单不翼而飞 |
| **MySQL** | 分表路由 correctness、联合索引是否被使用、事务回滚 | 广播查询扫 8 张全表、分表键路由到错误的表 |
| **ShardingSphere** | SQL 解析、路由策略、结果合并 | INSERT 落入错误分表、跨表聚合结果翻倍 |

### 结论

**Mock 和集成测试不是二选一，是分层互补：**

```
Layer 1: 单元测试 (Mock) — 2s，CI 每次提交都跑，验证 Java 逻辑
Layer 2: Redis 集成测试 — 30s，CI 每天跑，验证 Lua 原子性
Layer 3: 全链路集成测试 — 60s，发版前跑，验证 Redis → MQ → DB 完整链路
```

---

## 三、数据隔离方案

### 为什么必须隔离

你日志里的 `voucherId=999` 订单就是隔离缺失的后果——测试数据写入了 `tb_voucher_order_*`，和真实订单混在一起。

### 三层隔离设计

| 中间件 | 隔离手段 | 清理时机 | 本项目实现 |
|--------|---------|---------|-----------|
| **MySQL** | 独立测试库 `hmdp_test` | `@BeforeEach` TRUNCATE | `TestEnvironmentInitializer.initTestDatabase()` 库不存在则建 + 从 `hmdp` 同步表结构 (CREATE TABLE LIKE) |
| **Redis** | 独立 db 1（生产用 db 0） | `@BeforeEach` + `@AfterEach` 双 FLUSHDB | `TestEnvironmentInitializer.cleanRedisBeforeTest()` + `cleanRedisData()` |
| **RabbitMQ (L2)** | `auto-startup=false` + `@MockBean` | 不启动，消息不投递 | `application-test.yml` + 测试类 `@MockBean RabbitTemplate` |
| **RabbitMQ (L3)** | 独立 vhost `/hmdp_test` | 无需清理（物理隔离，互不可见） | `application-test.yml` 中 `spring.rabbitmq.virtual-host=/hmdp_test`，需手动创建一次 |

### RabbitMQ vhost 隔离原理

vhost（虚拟主机）在 RabbitMQ 里等价于 MySQL 的 database 或 Redis 的 db 编号：

```
┌─────────────────────────────────┐
│         RabbitMQ Broker         │
│                                 │
│  ┌─ vhost "/" (生产) ─────────┐ │
│  │  seckill.exchange           │ │
│  │  seckill.order.queue        │ │
│  │  seckill.order.dlq          │ │
│  └─────────────────────────────┘ │
│                                 │
│  ┌─ vhost "/hmdp_test" ───────┐ │
│  │  seckill.exchange           │ │  ← 同名但物理隔离
│  │  seckill.order.queue        │ │
│  │  seckill.order.dlq          │ │
│  └─────────────────────────────┘ │
└─────────────────────────────────┘
```

**一次性的手动创建（打开 `http://localhost:15672`）**：

1. Admin → Virtual Hosts → Add `/hmdp_test`
2. 点进 `/hmdp_test` → Permissions → 选 `guest` → 全部填 `.*` → Set

**配置端**（`application-test.yml` 中配置）：

```yaml
spring:
  rabbitmq:
    virtual-host: /hmdp_test
```

之后 Spring 启动自动在这个独立空间里声明 exchange/queue/binding，和生产 `/` vhost 物理隔离。即使两个环境用相同的队列名，也不会有消息串号。有了 vhost 隔离后，Layer 3 的 `@BeforeEach purgeQueue` 也可以去掉——每次 TRUNCATE + FLUSHDB 后，MQ 空间本身就是干净的。

### `TestEnvironmentInitializer` 完整生命周期

```
                     @BeforeAll  initTestDatabase()
                     │  1. 创建 hmdp_test 库（不存在则建）
                     │  2. 从 hmdp 同步所有表结构（CREATE TABLE IF NOT EXISTS ... LIKE）
                     ▼
              Spring 上下文启动
              ├─ 加载 application-test.yml
              ├─ 连接 hmdp_test 数据库（schema 已就绪）
              ├─ 连接 Redis db 1
              ├─ L2: RabbitMQ 监听器不启动（auto-startup=false）
              └─ L3: 连接 RabbitMQ vhost /hmdp_test，监听器启动
                     │
    ┌────────────────▼────────────────────────────┐
    │  每个 @Test 方法                             │
    │                                             │
    │  ① @BeforeEach  cleanTestTables()           │
    │     └─ TRUNCATE 所有已存在的业务表              │
    │                                             │
    │  ② @BeforeEach  cleanRedisBeforeTest()      │
    │     └─ FLUSHDB 清空 Redis db 1               │
    │                                             │
    │  ③ @BeforeEach  setUp()（子类）              │
    │     └─ 预置测试数据（Redis 库存 + DB 秒杀券）    │
    │                                             │
    │  ───────────  @Test 执行  ───────────       │
    │                                             │
    │  ④ @AfterEach   tearDown()（子类）            │
    │     └─ 清理测试专用 key + UserHolder         │
    │                                             │
    │  ⑤ @AfterEach   cleanRedisData()            │
    │     └─ FLUSHDB + UserHolder.removeUser()     │
    │        （兜底：防止测试崩溃后残留脏数据）       │
    └─────────────────────────────────────────────┘
```

设计要点：
- `@BeforeAll` 现在**两步**：创建库 + 同步表结构（`CREATE TABLE IF NOT EXISTS hmdp_test.tb_xxx LIKE hmdp.tb_xxx`），保证测试库和生产的 schema 一致
- `@BeforeEach` 的 FLUSHDB 是**主要防线**，保证每次测试从干净 Redis 开始
- `@AfterEach` 的 FLUSHDB 是**兜底防线**，测试崩溃后下次测试的 `@BeforeEach` 会再次清理
- RabbitMQ 通过 **vhost** 物理隔离，exchange/queue 命名不变但空间独立，不需要每次测试 purge 队列
- 子类只需 `extends TestEnvironmentInitializer` + `@SpringBootTest(properties = "spring.profiles.active=test")` 即自动启用全部隔离

### 面试话术：测试数据隔离

> 我们通过 `TestEnvironmentInitializer` 基类实现三层中间件隔离。MySQL 用独立测试库 `hmdp_test`，`@BeforeAll` 从生产库 `hmdp` 同步表结构，`@BeforeEach` TRUNCATE 所有业务表。Redis 用独立 db 1，`@BeforeEach` + `@AfterEach` 双 FLUSHDB 保证干净状态并兜底防崩溃残留。RabbitMQ 分两层——Layer 2 通过 `auto-startup=false` + `@MockBean` 阻断投递，Layer 3 用独立 vhost `/hmdp_test` 物理隔离，等价于 MySQL 的 database。子类只需继承基类并激活 test profile 即可自动获得完整隔离，零侵入。

### 不做隔离的后果

| 后果 | 严重程度 | 实例 |
|------|:--:|------|
| DB 测试数据混入生产 | **高** | 你日志里的 10 条 `voucherId=999` |
| Redis key 残留导致下次测试失败 | 中 | 上次扣到 0 的库存影响本次 |
| MQ 残留消息被消费者处理 | 中 | 订单重复写入 |
| 生产中真实数据被测试清理 | **极高** | `DELETE WHERE voucher_id` 无隔离可能删到生产数据 |
| 多开发者并行跑测试互相干扰 | 中 | `purgeQueue` 清掉对方的测试消息 |

---

## 四、最终测试文件架构

```
src/test/java/com/hmdp/
├── TestEnvironmentInitializer.java                    ← 测试基类（DB/Redis/MQ 三层隔离）
└── service/impl/
    ├── VoucherOrderServiceImplTest.java               ← Layer 1: 单元测试（全部 Mock，3 个用例）
    ├── VoucherOrderRabbitMQIntegrationTest.java       ← Layer 2: Redis 集成测试（真实 Redis + Mock MQ，4 个用例）
    └── VoucherOrderFullStackIntegrationTest.java      ← Layer 3: 全链路集成测试（真实 Redis + 真实 MQ + 真实 DB，4 个用例）
```

### 各文件职责

| 文件 | Layer | Redis | MQ | DB | 数据隔离 | 测试数 | 状态 |
|------|:-----:|:-----:|:--:|:--:|---------|:-----:|:----:|
| `VoucherOrderServiceImplTest` | 1 | Mock | Mock | Mock | 无（不连任何中间件） | 3 | ✅ 已修复 |
| `VoucherOrderRabbitMQIntegrationTest` | 2 | **真实** | **Mock** | 不写入 | voucherId=9999 + 测试库 + Redis db 1 | 4 | ✅ 已创建 |
| `VoucherOrderFullStackIntegrationTest` | 3 | **真实** | **真实** | **真实** | voucherId=8888 + 测试库 + Redis db 1 + MQ vhost | 4 | ✅ 已创建 |
| Stream Backup (Layer 4) | — | — | — | — | — | — | 📋 规划中 |

### 修复记录（2026-07-09）

**Layer 1 修复**：
- Lua 脚本参数：`eq("1"), eq("100"), eq("10001")` → `eq("1"), eq("100")`（MQ 版 Lua 只需 2 个参数，voucherId + userId）
- 去掉 `AopContext` Mock：RabbitMQ 版本不再使用 `AopContext.currentProxy()`
- 返回值断言：`assertEquals(10001L, ...)` → `assertEquals("排队中，订单号：10001", ...)`（MQ 版返回 String）
- 新增 `@Mock RabbitTemplate`：验证消息投递是否正确（成功时投递、失败时不投递）

**Layer 2 新建**：
- 继承 `TestEnvironmentInitializer`：测试库隔离 + 表清空 + Redis flush
- `@MockBean RabbitTemplate`：阻断 MQ 投递，避免触发消费者写 DB
- 移除 Stream 引用（`STREAM_KEY`、`opsForStream().size()`）
- 新增 `application-test.yml`：`hmdp_test` 数据库 + Redis db 1 + 禁用 RabbitMQ 监听器自动启动
- voucherId 改用 9999（测试区间），避免与生产 999 冲突

**Layer 3 新建（2026-07-10）**：
- 继承 `TestEnvironmentInitializer`：完整的 DB + Redis + MQ 三层隔离
- MQ 隔离：手动创建独立 vhost `/hmdp_test`（一次性），`application-test.yml` 中 `spring.rabbitmq.virtual-host=/hmdp_test`
- 覆盖 `auto-startup=true`：启用 RabbitMQ 监听器（test profile 默认为 false）
- `@BeforeEach` 预置 `tb_seckill_voucher` 记录 + Redis 库存，队列无需 purge（vhost 物理隔离）
- voucherId 使用 8888（测试区间）
- 异步等待策略：轮询 DB（200ms 间隔）等待消费者写入，单请求 5s 超时，并发请求 30s 超时
- 验证 Redis 状态（同步）+ DB 订单（异步）+ DB 库存扣减（乐观锁）

### 测试覆盖矩阵

| 测试场景 | Layer 1 (Mock) | Layer 2 (Redis) | Layer 3 (Full Stack) |
|----------|:---:|:---:|:---:|
| Java 分支逻辑（if-else） | ✅ | — | — |
| Lua 脚本原子性 | ❌ | ✅ | ✅ |
| 并发超卖防护 | ❌ | ✅ | ✅ |
| 一人一单去重 | ❌ | ✅ | ✅ |
| MQ 消息投递（成功/失败分支） | ✅ | ❌ | ✅ |
| Redis 库存状态变化 | ❌ | ✅ | ✅ |
| MQ → 消费者 → DB 完整链路 | ❌ | ❌ | ✅ |
| DB 乐观锁库存扣减 | ❌ | ❌ | ✅ |
| 队列消息确认（ACK/NACK） | ❌ | ❌ | ✅ |
| Redisson 锁兜底 | ❌ | ❌ | ✅ |

---

## 五、Layer 3 全链路测试设计

### 核心难题：异步等待

Layer 3 的消费者在独立线程中运行，`seckillVoucher()` 返回时 DB 尚未写入。测试必须**轮询等待**：

```
seckillVoucher() 返回
    │  ← "排队中，订单号：xxx"
    │  ← Redis 库存已扣（同步）
    ▼
 轮询 DB (200ms 间隔)
    │
    ├── 订单未出现 → sleep(200ms) → 重试
    │
    └── 订单出现 → 断言通过
```

### 超时设计

| 场景 | 超时 | 理由 |
|------|:---:|------|
| 单次秒杀 | 5s | 消息投递 + 消费 + 写入 < 100ms，5s 覆盖极端 GC |
| 50 并发 | 30s | 50 条消息由 5~20 个消费者并发处理，5s 足够但留 30s 兜底 |
| 库存耗尽 | 1s 休眠 | 不做轮询——直接断言 DB 无订单，1s 休眠确认 MQ 未投递 |

### 队列隔离

Layer 3 通过 vhost `/hmdp_test` 物理隔离。交换机和队列命名不变但空间独立，`@BeforeEach` 无需 `purgeQueue`——启停期间队列自动由 Spring 声明，每次测试结束父类 `cleanTestTables()` + `cleanRedisData()` 清理数据面后，MQ 空间本身就是干净的。

### auto-startup 覆盖

test profile 默认为 `auto-startup=false`（保护 Layer 2 不被消费者干扰），Layer 3 通过 `@SpringBootTest` 属性覆盖。配合独立 vhost，即使监听器启动也只在隔离空间内消费：

```java
@SpringBootTest(properties = {
    "spring.profiles.active=test",
    "spring.rabbitmq.listener.simple.auto-startup=true"  // 覆盖 test profile
})
```

### 四个测试用例

| 用例 | 场景 | 同步断言 | 异步断言 |
|------|------|---------|---------|
| `testFullStackSingleOrder` | 单用户秒杀 | Redis 库存-1、用户入 Set | DB 出现 1 条订单、DB 库存-1 |
| `testFullStackConcurrentNoOverstock` | 50 用户抢 10 库存 | 成功数 ≤ 10 | DB 订单数 = 成功数、DB 库存正确 |
| `testFullStackOneOrderPerUser` | 同一用户 20 次 | 只有 1 次成功 | DB 只有 1 条订单 |
| `testFullStackOutOfStock` | 库存=0 时请求 | 立即返回"库存不足" | MQ 队列深度=0、DB 无新订单 |

### 面试话术：全链路测试的异步等待

> Layer 3 验证的是 Redis → RabbitMQ → MySQL 的完整链路。核心挑战是消费者异步处理——`seckillVoucher` 返回时订单还没写入数据库，我们的方案是轮询 DB，200ms 间隔检查。对于"不应该发生"的场景（如库存耗尽），不依赖 sleep 等一个不会发生的事，而是用 `RabbitAdmin.getQueueInfo().getMessageCount()` 直接断言队列深度为 0——MQ 没收到消息就是没收到，当场给结论。test profile 通过独立 vhost `/hmdp_test` 物理隔离所有 MQ 资源。

---

## 六、运行指南

```bash
# Layer 1: 单元测试（无需任何外部服务，CI 每次提交必跑）
mvn test -Dtest=VoucherOrderServiceImplTest

# Layer 2: Redis 集成测试（需要 Redis，不需要 MySQL/MQ）
# 测试配置: src/test/resources/application-test.yml
mvn test -Dtest=VoucherOrderRabbitMQIntegrationTest

# Layer 3: 全链路集成测试（需要 Redis + RabbitMQ + MySQL 三项服务全部可用）
# 监听器自动启动，验证 Redis → MQ → DB 完整链路
mvn test -Dtest=VoucherOrderFullStackIntegrationTest

# 全部测试
mvn test -Dtest="VoucherOrder*Test"
```

---

## 七、Mockito 易错陷阱（亲踩实录）

### 陷阱 1：`@MockBean` 没显式调用 ≠ 没用

```java
@MockBean
private RabbitTemplate rabbitTemplate;  // 测试中没有任何 verify(rabbitTemplate)
```

**误区**：这个字段没人调用，删掉。

**真相**：`seckillVoucher()` 调用的是 Spring 注入的 `RabbitTemplate`。不加 `@MockBean`，Spring 注入真的 Bean，消息就进 MQ Broker 了。`@MockBean` 的作用是**替换 Spring 容器中的 Bean**，不是"被测试代码显式调用"才有用。

**口诀**：只要源码里有 `@Autowired RabbitTemplate`，集成测试就得加 `@MockBean RabbitTemplate`（除非你要测真实 MQ，即 Layer 3）。

### 陷阱 2：`@InjectMocks` 的"饥饿注入"

```java
// 源码 VoucherOrderServiceImpl 的内部：
@Resource private ISeckillVoucherService seckillVoucherService;  // seckillVoucher() 没用到
// extends ServiceImpl<VoucherOrderMapper, VoucherOrder>         // 父类带的 Mapper

// 测试被迫全部声明：
@Mock private ISeckillVoucherService seckillVoucherService;  // ← 凑数的
@Mock private VoucherOrderMapper voucherOrderMapper;          // ← 凑数的
@InjectMocks
private VoucherOrderServiceImpl voucherOrderService;          // 少一个就构造失败
```

**原因**：`@InjectMocks` 扫描被测类（含父类）所有 `@Autowired` / `@Resource` 字段，必须全部注入。Mockito 不会自动创建缺失的 Mock，直接抛 `null` 或构造异常。

**口诀**：`@InjectMocks` 是"全家桶"——被测类有几个依赖就得声明几个 `@Mock`，哪怕当前测试一个都用不上。

### 陷阱 3：`@Mock` vs `@MockBean` 别混

| | `@Mock` | `@MockBean` |
|---|---|---|
| 框架 | Mockito | Spring Boot Test |
| 生效位置 | 只在 `@ExtendWith(MockitoExtension.class)` 的单元测试 | 只在加载 Spring 上下文的 `@SpringBootTest` |
| 是否进容器 | 不进，由 `@InjectMocks` 手动注入 | **替换** Spring 容器中同类型的 Bean |
| 典型场景 | Layer 1 单元测试 | Layer 2/3 集成测试 |

**错误示范**：

```java
@SpringBootTest(properties = "spring.profiles.active=test")
class MyIntegrationTest {
    @Mock  // ❌ 在 @SpringBootTest 中无效！Spring 容器不受 MockitoExtension 管理
    private RabbitTemplate rabbitTemplate;
}
```

### 陷阱 4：`@BeforeEach` / `@AfterEach` 继承链执行顺序

```
父类 @BeforeEach  →  子类 @BeforeEach  →  @Test  →  子类 @AfterEach  →  父类 @AfterEach
```

本项目中 `TestEnvironmentInitializer` → 子类的实际顺序：

```
cleanTestTables() → cleanRedisBeforeTest() → setUp()（子类） → @Test → tearDown()（子类） → cleanRedisData()
     TRUNCATE              FLUSHDB            预置数据                   清理特定key         FLUSHDB + removeUser
```

**易错点**：如果你在子类 `setUp()` 之前依赖了已 TRUNCATE 的表数据，或者在子类 `tearDown()` 之后还期望 Redis 有数据，都会出错。记住：父类先打扫，子类后布置。

### 陷阱 5：ThreadLocal 泄漏

```java
// 上一个测试残留
UserHolder.saveUser(user);

// 下一个测试
UserDTO current = UserHolder.getUser();  // ← 拿到了上一个测试的用户！
```

**修复**：父类 `cleanRedisData()` 中已加 `UserHolder.removeUser()` 兜底。但子类 `tearDown()` 调在父类 `@AfterEach` 之前，如果子类 `tearDown()` 需要干净的 ThreadLocal，必须自己在 `tearDown()` 里再清一次（参考 `VoucherOrderRabbitMQIntegrationTest.tearDown()`）。

### 陷阱 6：Mockito 重载方法与模糊参数

```java
// convertAndSend 有多个重载：
// convertAndSend(String, String, Object)
// convertAndSend(String, Object, MessagePostProcessor)

// 这样写会报 "ambiguous method call"：
verify(rabbitTemplate).convertAndSend(
    eq("exchange"),
    eq("routingKey"),
    any(SeckillOrderMessage.class)  // ← Matcher 匹配了多个重载
);

// 正确写法：强制类型转换消除歧义
verify(rabbitTemplate).convertAndSend(
    (String) any(),      // ← 显式 cast
    (String) any(),
    (Object) any()
);
```

| 场景 | 陷阱 | 修复 |
|------|------|------|
| 单元测试用 `@Mock` | 在 `@SpringBootTest` 中不生效 | Layer 1 必须加 `@ExtendWith(MockitoExtension.class)` |
| 集成测试用 `@MockBean` | 在纯 Mockito 测试中不生效 | Layer 2/3 必须用 `@SpringBootTest` |
| 偷懒不声明"没用"的 `@Mock` | `@InjectMocks` 构造失败 | 被测类有几个依赖就声明几个 |
| 删掉"没用"的 `@MockBean` | MQ 消息真实投递，污染 Broker | 看源码的 `@Autowired`，都有对应 |
| 忘清 ThreadLocal | 测试间用户数据串号 | 父类兜底 + 子类自己再清 |

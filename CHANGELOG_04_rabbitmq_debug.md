# CHANGELOG_04 — RabbitMQ 异步秒杀：从踩坑到落地的完整复盘

## 一、项目背景

> 将秒杀下单从 **Redis Stream** 迁移到 **RabbitMQ**，利用 TTL + 死信队列（DLX）实现超时未支付自动关单，同时保持原有的三级防护体系（Lua 原子预检 → 分布式锁一人一单 → DB 乐观锁兜底）。

### 迁移前后架构对比

```
迁移前（Redis Stream）:
  seckill.lua (原子预检 + XADD) → Redis Stream → 单线程消费 → DB

迁移后（RabbitMQ）:
  seckill_mq.lua (原子预检，不含发布) → RabbitMQ → 多线程消费 → DB
                                                    ↓ TTL 过期
                                               seckill.order.dlq → 回滚库存
```

### 核心变更

| 维度 | Redis Stream | RabbitMQ |
|------|-------------|----------|
| 消息投递 | Lua 脚本内 XADD | `RabbitTemplate.convertAndSend` |
| 消费模型 | 单线程 `VoucherOderHandler` | `@RabbitListener` + `concurrency:5` |
| 超时关单 | 无 | TTL(15min) + DLX 自动路由 |
| 消息可靠性 | 消费者组 ACK | 手动 ACK + Redis SETNX 幂等 |
| 锁机制 | `tryLock()` 无参（Watchdog） | **同左，保持一致** |

---

## 二、问题 1：旧消息序列化不兼容（启动报错）

### 现象

```
WARN  DefaultExceptionStrategy : Fatal message conversion error
ERROR ConditionalRejectingErrorHandler : x-death header detected - discarding
ImmediateAcknowledgeAmqpException: Fatal and x-death present
```

消息 body：

```json
{
  "createTime": {
    "year": 2026, "monthValue": 7, "dayOfMonth": 6,
    "month": "JULY", "chronology": {...}
  }
}
```

### 根因

迁移过程中 `SeckillOrderMessage.createTime` 从 `LocalDateTime` 改为 `long`（毫秒时间戳）。4 条旧格式消息滞留在死信队列 `seckill.order.dlq`，`Jackson2JsonMessageConverter` 无法将 JSON 对象反序列化为 `long`。

### 临时方案（已被后续 PM 评审否决）

最初写了一个 `RabbitMQCleanupRunner` 实现 `SmartLifecycle`，在容器启动前 `deleteQueue` + `rabbitAdmin.initialize()` 重建。**这个方案有两个问题**：

1. **每次启动都删队列重建** — 删队列是一次性迁移操作，不应持久化为启动逻辑
2. **同时硬编码预热 Redis 数据** — 库存预热应在运营创建秒杀券时完成（`addSeckillVoucher`），不应混在队列清理里

### 最终方案

**该迁移操作已在 RabbitMQ 管理后台完成，代码中不保留。** 队列现由 `RabbitMQConfig` 的 Bean 声明自动管理，无需任何启动清理逻辑。

---

## 三、问题 2：Redisson 锁 `IllegalMonitorStateException`

### 现象

```
ERROR SeckillOrderConsumer : 处理秒杀消息异常

IllegalMonitorStateException: attempt to unlock lock, not locked by current thread
    by node id: 77db5a6c thread-id: 102
    at RedissonLock.lambda$unlockAsync$3(RedissonLock.java:605)

ERROR CachingConnectionFactory : Channel shutdown:
    PRECONDITION_FAILED - unknown delivery tag 1
```

### 故障链

```
lock.unlock() 抛异常 → 穿透到外层 catch
→ catch 尝试 basicNack → 但消息已 basicAck（订单已写入 DB）
→ RabbitMQ 返回 "unknown delivery tag"
→ Channel 关闭，消费者重启
```

### 根因分析

对比两个版本的锁调用：

```java
// ✅ Stream 版本（正确）
boolean isLock = lock.tryLock();              // 无参 → Watchdog 自动续期
// ...
lock.unlock();                                // 直接释放，线程必定持有锁

// ❌ RabbitMQ 版本（有问题）
boolean isLock = lock.tryLock(3, 10, SECONDS); // 显式 leaseTime=10s → 禁用 Watchdog
// ...
lock.unlock();                                 // 10 秒后锁可能已过期 → 抛异常
```

**`tryLock(waitTime, leaseTime, unit)` 指定了显式租约时间，Redisson 的 Watchdog 机制被禁用。** 锁在 10 秒后自动过期释放，但业务线程不知情，`finally` 块中的 `unlock()` 发现锁已被其他线程持有（或已过期消失），抛出 `IllegalMonitorStateException`。

### 错误方案（中间迭代）

```java
// ❌ 治标不治本
} finally {
    if (lock.isHeldByCurrentThread()) {   // 掩盖了锁过期的根因
        lock.unlock();
    }
}
```

**`isHeldByCurrentThread()` 的问题**：

- **掩盖根因**：真正的问题是 `tryLock` 参数禁用了 Watchdog，锁 10 秒过期。加了判断后代码不报错了，但锁过期的后果（一人一单被打破）照样发生
- **与 Stream 版本不一致**：同一个项目两种锁行为，维护者困惑
- **引入数据风险**：如果 `isHeldByCurrentThread` 返回 false 跳过 `unlock`，此时锁实际已被另一个线程获取 → 同一用户的两个请求同时进入 `createVoucherOrder` → **一人一单被打破**

### 最终方案

```java
// ✅ 与 Stream 版本完全一致
RLock lock = redissonClient.getLock("lock:order:" + message.getUserId());
boolean isLock = lock.tryLock();            // 无参，Watchdog 每 10s 续期
if (!isLock) {
    channel.basicNack(deliveryTag, false, true);
    return;
}
try {
    voucherOrderService.createVoucherOrder(voucherOrder);
    channel.basicAck(deliveryTag, false);
} finally {
    lock.unlock();                          // 直接释放，与 Stream 版本一致
}
```

---

## 四、问题 3：压测时 Redis PING 超时 + 线程饥饿

### 现象

```
ERROR PingConnectionHandler : Unable to send PING command over channel
RedisTimeoutException: Command execution timeout for command: (PING)

WARN  HikariPool : HikariPool-1 - Thread starvation or clock leap detected
    (housekeeper delta=1m16s)
```

### 根因

Redisson 配置过于简陋，全部使用默认值：

```java
// ❌ 原配置
config.useSingleServer().setAddress("redis://192.168.119.128:6379");
// 没有 timeout、retry、连接池控制
```

压测时大量并发连接导致 Redis Server 响应变慢：

```
Redisson 默认连接池 64+24 → 大量 PING 心跳（每 30s × 24 空闲连接 = 每秒 0.8 次）
→ Redis 远程 VM 网络延迟 + 高负载
→ PING 超时 → 连接被误判断开 → 重建连接 → 更多 PING → 恶性循环
→ 线程全部阻塞等待 Redis → HikariCP 线程饥饿
→ 秒杀流程不执行
```

### 修复

```java
// ✅ 优化后
config.useSingleServer()
    .setAddress("redis://192.168.119.128:6379")
    .setTimeout(5000)                   // 命令超时 5s（默认 3s）
    .setConnectTimeout(10000)            // 连接超时 10s
    .setConnectionPoolSize(16)           // 连接池 16（默认 64）
    .setConnectionMinimumIdleSize(4)     // 最小空闲 4（默认 24）
    .setRetryAttempts(3)                 // 失败重试 3 次
    .setRetryInterval(1500)              // 重试间隔 1.5s
    .setPingConnectionInterval(60000);   // 心跳间隔 60s（默认 30s）
```

| 指标 | 修改前 | 修改后 | 改善 |
|------|--------|--------|------|
| 空闲连接数 | 24 | 4 | -83% |
| 每秒 PING 次数 | 0.8 | 0.07 | -91% |
| 命令超时 | 3s | 5s | +67% |
| 失败重试 | 0 | 3 次 | — |

### 配套优化（application.yaml）

```yaml
datasource:
  hikari:
    maximum-pool-size: 20      # DB 连接池（默认 10）
    minimum-idle: 5
redis:
  lettuce:
    pool:
      max-active: 20           # Lettuce 连接池（原 10）
      min-idle: 5              # （原 1）
```

---

## 五、问题 4：RabbitMQ 管理页面 15672 端口打不开

### 根因

```
1. rabbitmq-plugins.bat enable → 写入 D:\erlang\RabbitMQ409\enabled_plugins
2. RabbitMQ 作为 Windows Service 运行（LOCAL SYSTEM 账户）
   → 实际读取 C:\Users\y\AppData\Roaming\RabbitMQ\enabled_plugins
   → 该文件不存在 → 加载了 0 个插件 → 15672 未监听
3. rabbitmqctl 也无法连接 → Erlang cookie 与 Service 账户不一致
```

### 修复

1. 停止 RabbitMQ 服务 → 同步 Erlang cookie 到 `SYSTEM` 账户目录
2. 在服务实际读取路径创建 `enabled_plugins` 文件
3. `rabbitmqctl stop_app` → `start_app` 加载管理插件

---

## 六、设计决策复盘（PM 视角）

### 决策 1：不在启动代码中预热 Redis 库存

**错误做法**：写一个 `ApplicationRunner` 在启动时从 DB 全量同步库存到 Redis。

**为什么错误**：
- 库存预热应该在**运营创建秒杀券时**完成（`addSeckillVoucher` 已经做了）
- 启动时自动从 DB 回灌 Redis 是**静默恢复** — Redis 宕机丢数据是 P0 事故，应该告警 + 人工确认恢复，不是代码偷偷补回去
- 99.9% 的时间里这个 Runner 什么都不做（key 已存在），纯粹浪费启动时间

**正确做法**：保持 `addSeckillVoucher` 的同步写入逻辑。Redis 数据丢失通过 RDB/AOF 持久化 + 管理后台手动同步按钮 + 监控告警来保障。

### 决策 2：不在启动代码中删除/重建 RabbitMQ 队列

**错误做法**：把迁移时的一次性清队列操作写成 `SmartLifecycle`。

**为什么错误**：队列的增删应该通过运维操作或 CI/CD 发布脚本完成，不应嵌入应用启动逻辑。每次启动删队列 → 绑定的 TTL/DLX 配置依赖 `rabbitAdmin.initialize()` 重建 → 如果某个 Bean 定义变了，重建出来的队列配置可能不一致。

**正确做法**：RabbitMQ 队列由 `RabbitMQConfig` 的 `@Bean` 声明 + `RabbitAdmin` 自动管理。迁移时的清理操作做到管理后台（或一次性脚本），不留代码痕迹。

### 决策 3：锁的 `tryLock` 参数与 Stream 版本保持一致

**最初（错误）**：`tryLock(3, 10, SECONDS)` → 显式租约 → Watchdog 禁用 → 加 `isHeldByCurrentThread` 兜底

**中间（仍然错误）**：保留 `tryLock(3, 10, SECONDS)` + 加 `isHeldByCurrentThread` → 掩盖根因

**最终（正确）**：`tryLock()` 无参 → Watchdog 自动续期 → `finally` 直接 `unlock()` → 与 Stream 版本完全一致

核心原则：**同一个系统，同一种分布式锁行为。不要引入"修复补丁"来掩盖参数错误。**

---

## 七、最终架构总览

```
用户请求 POST /voucher-order/seckill/{id}
  │
  ▼
VoucherOrderServiceImpl.seckillVoucher()
  │
  ├─ seckill_mq.lua（Redis 原子操作）
  │   ├─ 检查 stock > 0
  │   ├─ 检查用户未购买（SISMEMBER）
  │   ├─ 扣库存（INCRBY -1）
  │   └─ 标记用户（SADD）
  │
  ├─ RabbitTemplate.convertAndSend()
  │   消息 → seckill.exchange → seckill.order.queue（TTL=15min）
  │
  └─ 立即返回 "排队中，订单号：{orderId}"
  
  
SeckillOrderConsumer（并发消费，concurrency=5）
  │
  ├─ 1. Redis SETNX 幂等（mq:dedup:{orderId}, TTL=1h）
  ├─ 2. Redisson 分布式锁（lock:order:{userId}, tryLock() + Watchdog）
  ├─ 3. DB 写入（一人一单校验 + 乐观锁扣库存 + 保存订单）
  └─ 4. 手动 ACK
  
  
超时关单流程:
  seckill.order.queue（TTL 15min 过期）
    → seckill.dlx.exchange → seckill.order.dlq
    → SeckillOrderDlxConsumer
      ├─ 回滚库存（INCRBY stockKey 1）
      ├─ 移除去重标记（SREM orderKey userId）
      └─ 删除幂等 key（DEL mq:dedup:{orderId}）
```

### 三级防护体系

```
第一层：Lua 脚本（Redis 原子预检）
  → 库存校验 + 去重 + 扣库存，一个原子操作完成

第二层：Redisson 分布式锁 + Watchdog（lock:order:{userId}）
  → tryLock() 无参 → Watchdog 每 10s 续期 → 线程不死锁不释放
  → 保证同一用户不会有两个请求同时进入 createVoucherOrder

第三层：DB 乐观锁 + 业务校验（createVoucherOrder）
  → stock > 0 乐观锁
  → user_id + voucher_id 组合 count 校验（数据库级别兜底）
```

---

## 八、面试话术

### 一句话概括

> 在将秒杀系统从 Redis Stream 迁移到 RabbitMQ 的过程中，遇到了消息格式兼容、分布式锁参数错误、Redis 连接池配置不足等一系列问题。通过深入分析 Redisson Watchdog 机制、Spring 生命周期、以及从 PM 视角审视哪些逻辑属于"代码不该做的事"，最终实现了一套三级防护的异步秒杀架构。

### 技术亮点（按面试深度递进）

| 层级 | 话题 | 关键词 |
|------|------|--------|
| 表层 | RabbitMQ TTL + DLX 超时关单 | 死信队列、延迟取消 |
| 中层 | 消息可靠性保证 | 手动 ACK、Redis SETNX 幂等、乐观锁兜底 |
| 深层 | Redisson `tryLock()` 有参 vs 无参 | Watchdog 续期、leaseTime 陷阱 |
| 架构 | 哪些逻辑不该写在启动代码里 | 预热时机、一次性操作 vs 持久化逻辑 |

### 最容易被追问的问题

**Q：为什么 `tryLock()` 不加参数，不怕锁永不释放吗？**

A：Redisson 的 Watchdog 机制只在**线程存活期间**续期。如果 JVM 崩溃，Redis 中的锁 key 会在默认 30 秒后自动过期。而正常流程中 `finally { unlock() }` 会立即释放。所以不会死锁。

**Q：如果消息消费失败了怎么办？**

A：`createVoucherOrder` 抛异常 → 外层 catch 执行 `basicNack(deliveryTag, false, true)` → 消息回到队列等待重试 → 多次重试仍失败 → 消息被拒绝或 TTL 过期 → 进入 DLQ → `SeckillOrderDlxConsumer` 回滚 Redis 库存和去重标记。

**Q：`lombok.RequiredArgsConstructor` 报错怎么办？**

A：需要在 pom.xml 中确认 Lombok 依赖，并在 IDE 中安装 Lombok 插件。或者手动写 `@Autowired` 构造器。

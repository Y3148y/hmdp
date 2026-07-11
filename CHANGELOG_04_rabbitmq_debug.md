# CHANGELOG_04 — RabbitMQ 异步秒杀：从踩坑到落地的完整复盘

## 一、项目背景

> 将秒杀下单从 **Redis Stream** 迁移到 **RabbitMQ**，利用 TTL + 死信队列（DLX）实现超时未支付自动关单，同时保持原有的三级防护体系（Lua 原子预检 → 分布式锁一人一单 → DB 乐观锁兜底）。

### 迁移前后架构对比

```
迁移前（Redis Stream）:
  seckill.lua (原子预检 + XADD) → Redis Stream → 串行消费 → DB

迁移后（RabbitMQ）:
  seckill_mq.lua (原子预检，不含发布) → RabbitMQ → 多线程消费 → DB
                                                    ↓ TTL 过期
                                               seckill.order.dlq → 回滚库存
```

### 核心变更

| 维度 | Redis Stream | RabbitMQ |
|------|-------------|----------|
| 消息投递 | Lua 脚本内 XADD | `RabbitTemplate.convertAndSend` |
| 消费模型 | 串行消费 `VoucherOderHandler` | 并发消费 `@RabbitListener`（5~20 线程） |
| 超时关单 | 无 | TTL(15min) + DLX 自动路由 |
| 消息可靠性 | 消费者组 ACK | 手动 ACK + 死信队列重试 |
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

## 六-A、Stream 到 RabbitMQ 的迁移原理

### 核心认知：队列只是传输通道

Stream 和 RabbitMQ 在秒杀链路中的角色完全一样——**把 Lua 预检通过的消息从生产者运到消费者**。迁移的本质不是"换一种处理逻辑"，而是"换一种运输方式"。

```
Stream 版本：
  seckill.lua（原子预检 + XADD） → Redis Stream → 串行消费（1 worker pull） → tryLock → createVoucherOrder → ACK

RabbitMQ 版本：
  seckill_mq.lua（原子预检） → RabbitTemplate.send → RabbitMQ → 并发消费（5~20 workers push） → tryLock → createVoucherOrder → ACK
```

两者的差异只在"怎么把消息投递到队列"和"怎么从队列取消息"，**消费者拿到消息后的处理逻辑完全相同**。

### 为什么 Lua 是唯一的数据权威

Lua 脚本在 Redis 内以**原子方式**执行了三件事：

```
1. 检查 stock > 0          → 库存不足直接拒绝
2. SISMEMBER 检查是否已购买  → 重复下单直接拒绝
3. INCRBY stock -1          → 扣库存
4. SADD 添加用户到购买集合   → 标记已购买
```

这四步是一个原子操作，中间不会被任何其他请求插入。所以 Lua 返回 0 的那一刻，这个请求就是**合法的**——库存已扣、去重已标记、用户有权下单。

### 为什么 SETNX 是多余的

迁移初期在消费者中多加了一个 `SETNX mq:dedup:{orderId}`：

```java
// ❌ 多余的幂等层
Boolean firstTime = stringRedisTemplate.opsForValue()
        .setIfAbsent("mq:dedup:" + orderId, "1", 3600, TimeUnit.SECONDS);
```

这是过度设计。原因：

1. **Lua 已经做了去重**：`SISMEMBER` + `SADD` 保证了同一用户同一优惠券只会通过一次。通过了的消息就是合法的，不存在"需要去重"的场景。

2. **DB 层还有兜底**：`createVoucherOrder` 中 `count > 0` 的 DB 查询是第三层保护。

3. **引入了新问题**：SETNX 写在 tryLock 之前。如果 tryLock 失败需要 NACK 重新投递，dedup key 已经污染了 Redis，消息回来时被 SETNX 拦截 → 订单丢失。

4. **与 Stream 版本不一致**：Stream 版本的消费者没有 SETNX，RabbitMQ 版本也不应该有。

### 修正后的消费者逻辑

```
拿消息 → tryLock（兜底） → createVoucherOrder（DB 持久化） → ACK
```

与 Stream 版本完全一致，只是消息来源从 `XREADGROUP` 变成了 `@RabbitListener`。

### 什么时候幂等去重才是必要的

当**生产者侧没有原子去重**时，消费者需要自行去重。比如：

- 用户直接调用 HTTP 接口创建订单（无 Lua 预检）
- 多个服务实例同时消费，且上游没有用锁或事务保证唯一性

但本项目的秒杀链路中，Lua 已经在上游原子性地完成了去重，消费者再做一遍就是画蛇添足。

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
  ├─ 1. Redisson 分布式锁（lock:order:{userId}, tryLock() + Watchdog）
  ├─ 2. DB 写入（一人一单校验 + 乐观锁扣库存 + 保存订单）
  └─ 3. 手动 ACK
  
  
超时关单流程:
  seckill.order.queue（TTL 15min 过期）
    → seckill.dlx.exchange → seckill.order.dlq
    → SeckillOrderDlxConsumer
      ├─ 回滚库存（INCRBY stockKey 1）
      └─ 移除去重标记（SREM orderKey userId）
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

## 七-A、真实业务场景流程推演

以下推演基于这个前提：**Lua 脚本是唯一的数据权威**，原子操作中的每一步都不会被其他请求打断。

### 场景 1：正常秒杀（用户成功下单）

```
用户A (userId=1001) 抢 voucherId=100，库存初始=100

Step 1: 用户发起 POST /voucher-order/seckill/100
Step 2: seckillVoucher() 生成 orderId=88001234567890001（基于 Redis 自增 + 时间戳）
Step 3: 执行 seckill_mq.lua：
          GET seckill:stock:100 → 100 > 0 ✅
          SISMEMBER seckill:order:100 1001 → false（首次） ✅
          INCRBY seckill:stock:100 -1 → 99
          SADD seckill:order:100 1001
          返回 0（成功）
Step 4: RabbitTemplate 发送消息到 seckill.exchange
          → 路由到 seckill.order.queue
Step 5: 立即返回 {"success": true, "data": "排队中，订单号：88001234567890001"}

--- 消费者侧 ---
Step 6: SeckillOrderConsumer 收到消息
          tryLock("lock:order:1001") → 拿到锁
          createVoucherOrder():
            DB 查询 count(user_id=1001, voucher_id=100) → 0 ✅
            DB 乐观锁 UPDATE stock=stock-1 WHERE voucher_id=100 AND stock>0 → 成功
            INSERT voucher_order(id=88001234567890001, user_id=1001, voucher_id=100)
          basicAck → 消息确认
          lock.unlock()
Step 7: ✅ 完成，Redis 库存=99，DB 库存=99，订单已创建
```

### 场景 2：库存不足

```
用户B (userId=1002) 抢 voucherId=100，库存已为 0

Step 1-2: 同上
Step 3: 执行 seckill_mq.lua：
          GET seckill:stock:100 → 0，不大于 0 ❌
          返回 1（库存不足）
Step 4: 返回 {"success": false, "errorMsg": "库存不足"}
Step 5: ❌ 消息未入队，请求结束，无副作用
```

### 场景 3：同一用户重复下单

```
用户A (userId=1001) 再次抢 voucherId=100（已在场景1中秒杀成功）

Step 1-2: 同上
Step 3: 执行 seckill_mq.lua：
          GET seckill:stock:100 → 99 > 0 ✅
          SISMEMBER seckill:order:100 1001 → true（已购买）❌
          返回 2（不能重复下单）
Step 4: 返回 {"success": false, "errorMsg": "不能重复下单"}
Step 5: ❌ 消息未入队，库存未扣，无副作用
```

### 场景 4：超时关单 — TTL + DLX 自动回滚

```
前提：用户下单后 15 分钟内未支付（消费者还没来得及处理或处理卡住）

Step 1-5: 同场景 1，消息已入队，Lua 已扣库存（Redis 库存=99）
Step 6: ⏰ 15 分钟过去了，消费者因某种原因未能处理
Step 7: RabbitMQ 检测 TTL 到期
          → 消息自动路由到 seckill.dlx.exchange
          → 落入 seckill.order.dlq
Step 8: SeckillOrderDlxConsumer 收到死信消息
          INCRBY seckill:stock:100 1 → 100（库存回滚）
          SREM seckill:order:100 1001（移除去重标记，允许重购）
          basicAck
Step 9: ✅ Redis 恢复到秒杀前状态，库存=100，用户可以重新抢
```

关键设计要点：**DLX 回滚的是 Redis，不是 DB**。
- 正常路径：Lua 扣 Redis → 消费者写 DB
- 超时路径：Lua 扣 Redis → 消息 TTL 过期 → DLX 回滚 Redis
- 超时回滚是安全的：因为消费者没处理成功，DB 中不存在这笔订单，回滚 Redis 不会造成不一致

### 场景 5：消费者处理异常，NACK 重试

```
前提：消息正常入队，消费者开始处理，DB 写入时抛异常

Step 1-5: 同场景 1
Step 6: SeckillOrderConsumer 收到消息
          tryLock → 拿到锁
          createVoucherOrder() → DB 连接超时，抛异常
          catch 块：channel.isOpen() → true
          basicNack(deliveryTag, false, true) → 消息重新入队等待重试
          lock.unlock()
Step 7: 消息回到队头，等待下一次投递
Step 8: 重试 N 次后：
          成功 → ACK → 完成
          一直失败 → TTL 15 分钟到期 → DLX 回滚库存（同场景 4）
```

### 场景 6：同一用户并发抢两个不同优惠券

```
用户A (userId=1001) 同时抢 voucherId=100 和 voucherId=200
两个请求分别执行 Lua（互不影响）:

请求1（voucherId=100）:
  Lua: stock>0 ✅, 未购买 ✅ → 扣库存 → 标记 → 消息A入队

请求2（voucherId=200）:
  Lua: stock>0 ✅, 未购买 ✅ → 扣库存 → 标记 → 消息B入队

--- 消费者侧（两个消费者线程） ---
线程1: 拿消息A → tryLock("lock:order:1001") → 拿到 → 写入 DB → ACK → unlock
线程2: 拿消息B → tryLock("lock:order:1001") → 等待 → 拿到 → 写入 DB → ACK → unlock

✅ 两个不同的优惠券都成功，锁串行化避免了 DB 层的并发竞争
```

注意：不同优惠券的 Lua 操作各自独立，锁的粒度是用户级别（`lock:order:{userId}`），
不同用户之间完全并行。同一用户的不同优惠券被锁串行化，吞吐量影响极小
（同一用户同时抢两个不同券的概率很低）。

### 场景 7：服务宕机后恢复

```
前提：消息已入队（Lua 已执行，Redis 库存已扣），消费者正在处理中

宕机前：
  Redis: 库存=95, 已标记 5 个用户已购买
  RabbitMQ: 队列中有 5 条消息（持久化到磁盘，服务重启后仍在）
  消费者: 3 条已处理，2 条拿到但未 ACK

服务重启后：
  RabbitMQ 重新投递未 ACK 的 2 条消息
  → SeckillOrderConsumer 收到
  → tryLock → createVoucherOrder → ACK
  → 5 条全部处理完毕

如果其中 1 条永远处理不了（DB 异常等）：
  → TTL 15 分钟后自动进入 DLX
  → 回滚该条消息对应的 Redis 库存和去重标记
  → 该优惠券的库存自动恢复
```

### 场景流程总结

```
                   用户请求
                      │
              ┌───────▼────────┐
              │  seckill_mq.lua │  ← 唯一的数据权威
              │  原子：检查+扣+标记│
              └───────┬────────┘
                      │
          ┌───────────┼───────────┐
          │           │           │
      返回 0         返回 1       返回 2
     （成功）      （库存不足）   （重复下单）
       │              │           │
       ▼              ▼           ▼
  发送 RabbitMQ    直接拒绝     直接拒绝
       │
       ▼
  seckill.order.queue (TTL=15min)
       │
   ┌───┴───────────┐
   │               │
   ▼               ▼
消费者处理      15min 超时
   │               │
   ▼               ▼
 DB写入         DLX 回滚 Redis
   │           (库存+1, SREM)
   ▼
  ACK → 完成
```

---

## 七-B、可靠性设计：手动 ACK、重连机制、一致性保证

### 一、为什么用手动 ACK 而不是自动 ACK

两种模式的核心区别在于"消息什么时候从队列中删除"：

```
自动 ACK（auto）：
  消费者收到消息 → 立即从队列删除 → 开始处理
  风险：处理失败（DB 宕机、OOM、进程崩溃）→ 消息已删，永久丢失

手动 ACK（manual）：
  消费者收到消息 → 消息仍在队列中 → 处理完成 → 显式 basicAck → 删除
  风险：处理失败 → basicNack → 消息重新入队 → 其他消费者重试
```

**为什么秒杀场景必须用手动 ACK：**

```
假设使用自动 ACK，以下场景会导致数据不一致：

1. 消费者收到消息，消息被自动删除
2. 开始处理 → DB 写入时抛异常
3. ⚠️ 消息已删除，无法重试
4. 结果：Redis 库存已扣（Lua 做的），DB 无订单，永久不一致

手动 ACK 下的同一场景：
1. 消费者收到消息（消息仍在队列）
2. 处理 → DB 异常 → catch 块 basicNack → 消息回到队列
3. 重新投递 → 消费者再次处理 → 成功 → basicAck
4. 始终重试失败 → TTL 15 分钟到期 → DLX 回滚 Redis
5. ✅ 无论哪种结果，最终一致
```

**一句话：手动 ACK 把"消息删除"的控制权交给了业务代码，只有 DB 写入成功才确认，失败则重试或进入 DLX 兜底。**

### 二、为什么没有配置 RabbitMQ 重连机制

实际上，**重连机制Spring AMQP 已经内置了，不需要显式配置**。

`spring-boot-starter-amqp` 使用的 `CachingConnectionFactory` 默认启用了自动恢复：

```java
// Spring AMQP 默认行为（无需配置）：
// 1. connection-timeout: 默认 30s，连接不上时会持续重试
// 2. 断连自动重连：底层使用 RabbitMQ 的 automatic-recovery 机制
// 3. 重连间隔：默认 5s，指数退避到 60s
```

当前 `application.yaml` 中的 RabbitMQ 配置：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    listener:
      simple:
        acknowledge-mode: manual     # 手动 ACK
        prefetch: 10                 # 每次预取 10 条
        concurrency: 5               # 并发消费者数
        max-concurrency: 20          # 峰值扩容上限
    publisher-confirm-type: correlated  # 生产者确认
    publisher-returns: true             # 不可达消息回调
```

**生产者端可靠性**（这两行配置是显式加的）：
- `publisher-confirm-type: correlated`：消息发送到 Broker 后，Broker 异步回调确认。发送失败 → 抛出异常 → 接口返回错误。
- `publisher-returns: true`：消息到达 Broker 但无法路由到队列（routing key 写错等）→ 回调 `ReturnCallback` → 记录日志。

**消费者端重试**：
- `concurrency: 5` 和 `max-concurrency: 20`：消费者线程池。线程崩溃后 Spring 自动创建新线程补充。
- `prefetch: 10`：每次预取 10 条。配合手动 ACK，未 ACK 的消息不会从队列删除，服务重启后重新投递。

**为什么不需要额外配置重试参数**：
- 自动重连：Spring AMQP 默认开启
- 消费者 recover：Spring AMQP 默认行为就足够
- 消息持久化：队列声明为 `durable`，消息持久化到磁盘，Broker 重启不丢消息
- 死信兜底：即使所有重试都失败，DLX 在 15 分钟 TTL 后自动回滚 Redis

### 三、一致性保证机制

秒杀链路的完整一致性由以下层次共同保障：

```
┌─────────────────────────────────────────────────┐
│ 第一层：生产者确认（publisher-confirm）           │
│   → 消息必须到达 Broker 才返回"排队中"           │
│   → 发送失败 → 抛异常 → 调用方感知               │
├─────────────────────────────────────────────────┤
│ 第二层：消息持久化（durable queue）               │
│   → 消息写入磁盘后才确认（非内存）               │
│   → Broker 重启不丢消息                          │
├─────────────────────────────────────────────────┤
│ 第三层：手动 ACK（consumer acknowledge）         │
│   → DB 写入成功后才删除消息                      │
│   → 处理失败 → NACK → 重新投递                   │
├─────────────────────────────────────────────────┤
│ 第四层：死信队列兜底（DLX）                      │
│   → 15 分钟未消费 → 自动转入 DLX                 │
│   → 回滚 Redis 库存 + 去重标记                   │
├─────────────────────────────────────────────────┤
│ 第五层：消费者重连（Spring AMQP 自动）           │
│   → Broker 断开 → 自动重连                       │
│   → 重连成功后继续消费未 ACK 的消息              │
├─────────────────────────────────────────────────┤
│ 第六层：DB 事务 + 乐观锁（createVoucherOrder）   │
│   → @Transactional 保证写入原子性                │
│   → stock > 0 乐观锁防止超卖                     │
│   → user_id + voucher_id count 防止重复下单      │
└─────────────────────────────────────────────────┘
```

### 四、有效性保证（为什么不丢消息）

| 故障场景 | 保护机制 | 结果 |
|---------|---------|------|
| 生产者发送失败 | `publisher-confirm` 抛异常 | 用户看到错误，可以重试 |
| Broker 宕机 | durable queue + disk persist | 重启后消息仍在 |
| 消费者崩溃 | 手动 ACK，未确认消息留在队列 | 新消费者继续处理 |
| DB 写入失败 | basicNack requeue | 消息重新投递 |
| 持续重试失败 | TTL 15min + DLX | 回滚 Redis，库存恢复 |
| 网络闪断 | Spring AMQP 自动重连 | 恢复后继续消费 |
| 消费者 OOM | 线程被回收，新线程自动创建 | `max-concurrency:20` 保证资源 |

### 五、与 Stream 版本的可靠性对比

| 维度 | Stream | RabbitMQ | 提升 |
|------|--------|----------|------|
| 消息持久化 | Redis RDB/AOF | 磁盘持久化 | Broker 级可靠性 |
| 消费确认 | 手动 ACK | 手动 ACK | 持平 |
| 超时兜底 | ❌ 无 | TTL + DLX | 新增 |
| 生产者确认 | ❌ 无 | Publisher Confirm | 新增 |
| 管理运维 | 无 UI | Management UI | 新增 |
| 消费者弹性 | 串行消费（1 worker） | 并发消费（5~20 workers） | 新增 |
| 断连恢复 | 依赖 Redisson | Spring AMQP 内置 | 持平 |



---

## 七-C、技术选型对比：为什么是这个方案而不是其他

### 对比 1：DLX 死信队列 vs 独立 Error 队列

提出的方案：新建一个 error queue，消费者 catch 异常时不 requeue，转而发到 error queue 触发回滚。

| 维度 | TTL + DLX（当前方案） | 独立 Error 队列 |
|------|----------------------|----------------|
| 实现方式 | 队列声明时指定 TTL + DLX 参数，零代码 | 消费者 catch 块手动发送 error 消息 |
| 重试机制 | NACK requeue → Broker 自动重试 N 次 → TTL 到期 → DLX | 需手写重试计数器（header `x-retry-count`）逐次判断 |
| 回滚时机 | TTL 15 分钟统一自动触发 | 第一次 NACK 就触发（重试还没做）或需自己实现延迟 |
| 代码耦合 | 无，配置即行为 | 回滚逻辑和消费者业务逻辑耦合 |
| 运维复杂度 | 队列声明即完成 | 需要维护额外的 error queue 和重试计数逻辑 |

**结论**：DLX 覆盖了"重试 → 兜底回收"整条链路，不用额外写一行代码。独立 error queue 需要手写重试计数器、手动判断重试次数是否耗尽、手动决定何时回滚——所有逻辑都是当前 DLX 已经原生支持的。**这是在用代码重新实现 RabbitMQ 已有的功能。**

### 对比 2：单级 TTL vs 支付系统的阶梯延迟

支付系统典型方案：TTL=1min → 检查 → TTL=5min → 检查 → TTL=10min → 取消，多个延迟时间点逐步逼近过期。

| 维度 | 阶梯延迟（支付系统） | 单级 TTL（当前秒杀方案） |
|------|-------------------|------------------------|
| 等待对象 | 用户（主动付款行为） | 系统（消费者写 DB） |
| 状态机 | 待支付 → 已支付/已取消，中间态可查 | 无中间态，消息要么消费成功要么没消费 |
| 检查内容 | 查支付流水、查第三方回调 | 无内容可查 |
| 延迟目的 | 给用户多次提醒和补救时间 | 给消费者处理时间（几秒内就完成） |
| 实现成本 | 多个不同 TTL 队列 或 Delayed Message Plugin | 一个队列声明搞定 |

秒杀场景不存在"用户可能会在 1 分钟后支付，也可能在 10 分钟后支付"这种不确定性。消息被消费是**瞬时完成的系统动作**。如果消费者 15 分钟还没处理完，不是"在等用户"，而是**消费者已经挂了**——此时不需要阶梯检查，直接判定失败回收资源即可。

如果用阶梯延迟：需要引入 RabbitMQ Delayed Message Plugin 或建多个不同 TTL 的队列、消费者要实现"收到检查消息 → 查 DB → 发下一级延迟消息"的状态机。而实际上查 DB 也是一无所获——订单要么写成功了要么没写，不存在"支付中"状态。

**结论**：用支付系统的复杂度去解决一个秒杀场景不存在的问题，过度设计。单级 TTL 足够覆盖"消费者严重故障"的兜底场景。

### 对比 3：为什么不依赖 Redis key 过期监听做回滚

另一个常见方案：Lua 扣库存后设置一个 Redis key（TTL=15min），监听 key 过期事件自动回滚。

| 维度 | Redis 过期监听 | RabbitMQ DLX（当前方案） |
|------|--------------|------------------------|
| 可靠性 | fire-and-forget，过期事件不保证送达 | 消息持久化到磁盘，DLX 可靠路由 |
| 原子性 | 创建 key 和 Lua 是两步操作，非原子 | 队列 TTL 与消息生命周期绑定 |
| 额外成本 | 需开启 `notify-keyspace-events`，影响 Redis 性能 | 零额外配置 |
| 与消息的关系 | 需要额外维护 key 和消息的一致性 | 消息即数据，无需额外一致性维护 |

**结论**：Redis key 过期通知是"尽力而为"的，不能作为事务回滚的依赖。RabbitMQ 的 TTL + DLX 是协议的组成部分，可靠得多。

---

## 八、面试话术

### 项目介绍（30 秒版）

> 这是一个基于 Spring Boot 的本地生活服务平台，我负责秒杀优惠券模块。核心链路是：Lua 脚本在 Redis 内原子完成库存校验和用户去重，通过后将消息投递到 RabbitMQ 异步削峰，消费者并发处理写入 MySQL。利用 RabbitMQ 的 TTL + 死信队列实现 15 分钟超时自动关单和库存回滚，Spring AMQP 的手动 ACK 和生产者确认保证消息零丢失。整个方案支持约 2000 QPS，接口响应时间控制在 100ms 以内。

### 完整业务流程（面试描述版）

> 分布式环境下，用户多线程抢购秒杀券。请求到达后首先执行 Lua 脚本，在 Redis 内原子完成三项校验：检查库存、检查用户是否已购买该券、扣库存并标记用户。只有 Lua 返回成功的请求，才会将订单消息投递到 RabbitMQ 持久化队列，并立即返回"排队中"给用户，降低接口响应延迟。校验不通过的直接拒绝。
>
> 消费者端通过 `@RabbitListener` 并发监听，5 到 20 个 worker 线程，Broker 主动推送消息。消费者拿到消息后，使用 Redisson 分布式锁（`tryLock()` 无参，Watchdog 自动续期）作为一人一单的 DB 层兜底。获取锁成功后写入 MySQL：DB 层再次校验一人一单、乐观锁扣减库存、保存订单。写入成功则手动 ACK 确认消费，finally 释放锁。
>
> 如果 DB 写入异常，执行 basicNack 将消息重新入队重试。持续重试失败直到 15 分钟 TTL 到期，RabbitMQ 自动将消息路由到死信队列，死信消费者负责回滚 Redis 库存、清退去重标记，让库存恢复供其他用户抢购。
>
> 生产者端通过 Publisher Confirm 保证消息到达 Broker，消费者端通过 Spring AMQP 内置断连重连恢复消费。消息从入队到最终处理，要么成功写入 DB 并 ACK，要么超时进入 DLX 回滚 Redis——不会出现"Redis 已扣、DB 未写"的中间态。

### 经典面试 Q&A

**Q1：为什么从 Redis Stream 换成 RabbitMQ？**

> 三个核心原因。第一，Stream 版本不支持超时关单，消息消费后如果订单未支付，库存不会自动恢复。RabbitMQ 的 TTL + DLX 原生支持超时自动回滚。第二，运维能力——Stream 没有管理 UI，消息积压无法直观排查，RabbitMQ 的管理界面可以直接看到队列深度、消费速率。第三，消费者弹性——Stream 版本是串行消费模式，RabbitMQ 支持 5 到 20 个并发消费者动态扩缩，吞吐上限更高。
>
> 另外迁移后接口响应时间从约 80ms 降到约 30ms，因为 Lua 脚本不再需要 XADD，响应路径更短。

**追问：那 Stream 就一无是处吗？**

> 也不是。如果团队已经重度依赖 Redis，不想引入额外的中间件运维成本，Stream 是一个够用的选择。另外 Stream 的消费者组机制在某些轻量级场景下足够用。但当需求出现"超时自动回收""消息堆积可视化""多线程并发消费"时，就是切换到专业消息队列的信号。

**Q2：为什么用手动 ACK 而不是自动 ACK？**

> 自动 ACK 的问题是：消息一到消费者就从队列删除，如果后续写入 DB 失败，消息已经没了，但 Redis 库存已经扣了——这会造成永久不一致。手动 ACK 把删除消息的控制权交给业务代码，只有 DB 写入成功才确认删除。如果写入失败就 NACK 重新入队重试，重试到 TTL 到期还没成功就由 DLX 回滚 Redis。这样就保证了最终一致性。

**Q3：Lua 脚本已经做了原子校验，为什么消费者还要加 tryLock？**

> tryLock 是兜底，不是主防线。Lua 脚本可以保证同一用户同一优惠券在 Redis 层面不会重复通过，但多个不同优惠券的消息在消费者端并发处理时，DB 写入可能产生竞态。tryLock 把同一用户的所有 DB 写入串行化，避免并发写入导致的数据问题。实际上在绝大部分正常流程中，tryLock 永远不会冲突——Lua 已经拦住了冲突的可能性。但如果 Lua 出现极端漏洞或 Redis 数据丢失导致两条消息进入队列，tryLock 就是最后一道防线。

**Q4：为什么 tryLock() 不加参数？**

> Redisson 的 `tryLock()` 有参数和无参数行为不同。有参数指定 leaseTime 会禁用 Watchdog，锁到期自动释放，如果业务还没执行完就可能导致锁被其他线程获取，打破一人一单。无参调用则启用 Watchdog，每 10 秒自动续期，只要线程还活着锁就不会释放。finally 中直接 unlock 释放。这和 Stream 版本的行为一致。

**Q5：为什么消费者不需要额外做幂等去重（SETNX）？**

> 因为 Lua 脚本在 Redis 内已经用 SISMEMBER + SADD 原子完成了用户去重。Lua 返回成功的那一刻，用户已经被标记为已购买，不可能再通过同券的第二次校验。消费者拿到的每一条消息都是"已验证合法"的，不需要再自行去重。Stream 版本没有 SETNX，RabbitMQ 版本也不应该有。Lua 是唯一的数据权威。

**Q6：RabbitMQ 宕机了怎么办？消息会丢吗？**

> 不会。队列声明为 durable，消息投递时是持久化的，写入磁盘后 Broker 才确认。同时生产者端配置了 publisher-confirm，如果消息没能到达 Broker，生产者端会感知到并抛异常，调用方收到错误可以重试。Broker 恢复后，未消费的持久化消息还在队列里，消费者重新连接后继续处理。

**Q7：如果消费者在 ACK 之前崩溃了，消息会丢吗？**

> 不会。手动 ACK 机制下，消息在 ACK 之前一直留在队列里。消费者崩溃后，RabbitMQ 检测到连接断开，未 ACK 的消息自动重新入队，由其他消费者线程或重启后的消费者继续处理。

**Q8：为什么是 15 分钟 TTL，不是 5 分钟或 30 分钟？**

> 正常流程下消息在几秒内就被消费，TTL 只是一个安全阈值。15 分钟的选择平衡了两点：足够长，覆盖临时性网络抖动、DB 慢查询等短暂故障；又足够短，避免库存被长时间锁定。具体数值需要根据业务监控数据调整，比如 P99 消费延迟是 5 秒，15 分钟已经有 180 倍的安全余量。

**Q9：这套方案的瓶颈在哪里？**

> 短期的瓶颈在 Lua 脚本——Redis 单线程执行，QPS 上限取决于 Redis 的单线程处理能力。中期瓶颈在 RabbitMQ 的消息堆积能力，极端峰值下需要考虑队列容量和磁盘。长期瓶颈在 DB——乐观锁高并发下冲突率上升。当前方案通过异步削峰已经将瓶颈从 DB 转移到了中间件层，可以支撑更高的并发。

**Q10：如果让你重新设计，你会怎么做？**

> 首先，消息队列的选择取决于团队技术栈，Kafka 在高吞吐场景下比 RabbitMQ 更强，但 RabbitMQ 的 TTL + DLX 在超时关单场景下更自然。其次，当前方案中 Lua 脚本的扣库存和 RabbitMQ 的发送是两步操作——Lua 成功了但 RabbitMQ 发送失败了怎么办？目前 producer-confirm 抛异常可以让接口返回错误，但库存已经扣了。理想方案是通过事务消息或 outbox 模式保证两者原子性。但引入事务消息会增加复杂度，当前方案在业务上可以接受极小概率的不一致（手工补偿），取舍是合理的。

---

## 九、文档变更记录

| 日期 | 变更 |
|------|------|
| 2026-07-05 | 初稿：Stream → RabbitMQ 迁移方案 |
| 2026-07-07 | 新增问题 1~4 的故障分析和修复方案 |
| 2026-07-08 | 新增 PM 设计决策复盘（启动代码清理） |
| 2026-07-08 | 修正 tryLock 参数（有参 → 无参），与 Stream 版本对齐 |
| 2026-07-09 | 新增六-A：Stream → RabbitMQ 迁移原理（移除 SETNX 的理由） |
| 2026-07-09 | 新增七-A：7 个真实业务场景流程推演 |
| 2026-07-09 | 新增七-B：可靠性设计（手动 ACK、重连、一致性保证） |
| 2026-07-09 | 新增七-C：技术选型对比（DLX vs ErrorQueue、阶梯延迟、Redis 过期监听） |
| 2026-07-09 | 重写八：完整项目介绍 + 10 个经典面试 Q&A |


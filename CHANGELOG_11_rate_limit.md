# CHANGELOG_11：秒杀接口限流——Redis ZSET 滑动窗口

> 日期：2026-07-19
> 类型：新增功能
> 涉及：`RateLimitInterceptor.java`、`seckill_rate_limit.lua`、`RedisConstants.java`、`SpringMVC.java`

---

## 一、为什么需要限流

秒杀接口 `/voucher-order/seckill/{id}` 没有任何频率限制。拿到 token 的脚本可以无限制调用，压测已验证单机能到 700+ QPS——恶意脚本可瞬间打满 CPU/连接池。

限流解决三个问题：

- **公平性**：正常人 1 秒点不了 10 次，脚本却可以 1000 次
- **资源保护**：每次请求都要调 Lua 脚本 + MQ 投递，无效请求浪费 Redis/CPU
- **面试考点**：高并发三件套——缓存、**限流**、降级

## 二、设计选型：为什么是 ZSET 滑动窗口

| 方案 | 原理 | 优点 | 缺点 |
|------|------|------|------|
| 固定窗口 | `INCR key` + `EXPIRE key 1` | 最简单 | 边界突发（第 0.9s + 第 1.1s = 两倍） |
| 令牌桶 | ZSET 存令牌发放时间 | 允许突发、精确 | Lua 脚本复杂 |
| **ZSET 滑动窗口** | `ZADD ts member` → `ZREMRANGEBYSCORE 清旧` → `ZCARD 计` | 精确、无边界效应 | 每次请求一次 Redis Lua |
| Sentinel | 开箱即用 | 零代码、Dashboard | 固定窗口、引入新中间件 |

**边界效应演示**（固定窗口的致命缺陷）：

```lua
-- 固定窗口：0.9s 第 10 个请求 → EXPIRE 续期 → 1.0s key 过期 → 1.1s 又能发 10 个
-- 实际 0.2 秒内收到 20 个请求（10+10），限流失效
```

ZSET 滑动窗口每次 ZADD 写当前毫秒时间戳，ZREMRANGEBYSCORE 清掉 1 秒以前的，ZCARD 精确计算最近 1 秒内的请求数——没有边界漏洞。

**为什么不用简单计数器**：同上，固定窗口有边界效应，秒杀场景不能容忍窗口边界的瞬时双倍流量。

## 三、实现

### 3.1 拦截器链位置

```text
请求 → RefreshTokenInterceptor(order=0, 加载用户到UserHolder)
     → LoginInterceptor(order=1, 未登录返回401)
     → RateLimitInterceptor(order=2, 限流检查)  ← 新增
     → Controller
```

在 LoginInterceptor 之后：保证 userId 已加载到 ThreadLocal，且未登录请求已被 401 拦截（不浪费 Redis 往返做限流）。

### 3.2 Lua 脚本 `seckill_rate_limit.lua`

```lua
-- ARGV[1] key prefix (rate_limit:seckill:)
-- ARGV[2] user id
-- ARGV[3] current time millis
-- ARGV[4] window size (seconds)
-- ARGV[5] max requests
-- 返回: 0 放行, 1 限流

local key = ARGV[1] .. ARGV[2]
local now = tonumber(ARGV[3])
local window = tonumber(ARGV[4])
local maxReq = tonumber(ARGV[5])

redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)   -- 1. 清窗口外
local count = redis.call('ZCARD', key)                         -- 2. 计数
if count >= maxReq then return 1 end                           -- 3. 超阈值拒绝

local seq = redis.call('INCR', key .. ':seq')                  -- 4. 唯一 member
redis.call('EXPIRE', key .. ':seq', 2)
redis.call('ZADD', key, now, now .. ':' .. seq)                -- 5. 写入
redis.call('EXPIRE', key, window * 2)                          -- 6. 自动过期

return 0
```

关键细节：

- **同毫秒 member 唯一性**：ZSET member 必须唯一，否则 ZADD 只更新 score 不新增元素。用 `INCR key:seq` 自增计数器做后缀，保证同一毫秒多个请求不会互相覆盖
- **EXPIRE**：`window * 2` 自动清理不活跃 key，防止 Redis 内存泄漏
- **score 用毫秒时间戳**：`ZREMRANGEBYSCORE 0 (now - 1000)` 精确删掉 1 秒前的记录

### 3.3 RateLimitInterceptor

```java
public boolean preHandle(...) {
    Long userId = UserHolder.getUser().getId();
    Long result = stringRedisTemplate.execute(SCRIPT, Collections.emptyList(),
            RATE_LIMIT_SECKILL_KEY, userId.toString(),
            String.valueOf(System.currentTimeMillis()),
            WINDOW.toString(), MAX_REQ.toString());

    if (result != null && result == 1) {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(
                Result.fail("请求过于频繁，请稍后再试")));
        return false;
    }
    return true;
}
```

- 构造器注入 `StringRedisTemplate`，与 `RefreshTokenInterceptor` 一致
- Lua 脚本 `static` 块加载，与 `VoucherOrderServiceImpl` 的 `SECKILL_MQ_SCRIPT` 模式一致
- 返回 JSON 格式 `Result.fail()`，HTTP 200，与项目统一约定一致

### 3.4 配置参数

```java
// RedisConstants.java
public static final String RATE_LIMIT_SECKILL_KEY = "rate_limit:seckill:";
public static final Long RATE_LIMIT_SECKILL_WINDOW = 1L;    // 窗口 1 秒
public static final Long RATE_LIMIT_SECKILL_MAX_REQ = 10L;  // 上限 10 次
```

## 四、验证

```bash
# 快速连发 11 次
for i in $(seq 1 11); do
  curl -s -X POST http://localhost:8081/voucher-order/seckill/10000 \
    -H "authorization: <token>"
done

# 预期：前 10 次 {"success":true}
#       第 11 次 {"success":false,"errorMsg":"请求过于频繁，请稍后再试"}
```



## 五、附：踩坑——压测残留消息致 58,504 次重试

验证限流时发现应用启动即报错，消费者反复重试 `seckill.order.queue` 中一条残留消息。

**现象**：

- 应用启动后 `SeckillOrderConsumer` 无限循环报错，CPU 打满
- RabbitMQ 管理界面 Purge Messages 无效——消息处于 unack 状态，purge 不掉
- `seckill.order.queue` 统计：`deliver: 60709`，`redeliver: 58504`（一条消息重试了 58,504 次）

**根因链**：

1. 上一轮压测结束后 `BenchmarkTool.cleanAll()` 清空了 MySQL 和 Redis
2. RabbitMQ 队列中残留 1 条秒杀消息，其 voucherId 对应的 MySQL 数据已不存在
3. 消费者拉取消息 → `createVoucherOrder` 查 DB 失败抛异常 → `basicNack(requeue=true)` 放回队列
4. 消息立即重新投递 → 再次失败 → 再次 requeue → **死循环**
5. 用户尝试 Purge 时正值消息被消费（unack），purge 命令清不到它

**教训**：

- 数据清理要覆盖全链路：MySQL + Redis + MQ 三者缺一不可
- `basicNack` 的 `requeue=true` 适用于瞬时故障（DB 重连），**数据永久不存在应 `requeue=false` 直接丢弃**
- HTTP Management API 的 Purge 只在消息处于 ready 状态时生效，unack 消息要通过消费者端处理

**修复**：

- `BenchmarkTool.cleanAll()` 新增 RabbitMQ 队列清理（通过 HTTP Management API `DELETE /api/queues/.../contents`）

## 六、附：踩坑——JDK 17 反射限制致 MyBatis Plus 启动报错

写完限流拦截器启动时突然报 `InaccessibleObjectException: Unable to make field final long java.lang.Number.serialVersionUID accessible`，MyBatis Plus 初始化反射 `Number` 类被 JDK 17 强封装模块拒绝。

**现象**：

- 启动即报 `InaccessibleObjectException`，应用起不来
- 此前 2 个月 IDEA 运行秒杀接口从未报过此错

**排查**：
表现：加限流拦截器后启动报 InaccessibleObjectException，MyBatis Plus 反射 Number.serialVersionUID 被 JDK 17
模块化限制阻止。但 .idea/misc.xml 里 languageLevel="JDK_17" 已存在 2 个月，之前 IDEA 运行秒杀接口从未报错。

Why：IDEA Project SDK 选的是 JDK 17，JDK 17+ 默认禁止反射访问 java.lang 内部字段。为什么之前 2
个月没触发——没找到确切原因，但现象是改回 JDK 1.8 即消失。

- `.idea/misc.xml` 显示 `languageLevel="JDK_17"`，且已存在 2 个月
- 为什么之前不报错——未找到确切原因

**修复**：

- IDEA → Project Structure → Project SDK 改为 1.8

**教训**：

- JDK 9+ 模块化系统默认禁止反射访问 `java.lang` 内部包，MyBatis Plus 3.4.3 未完全适配 JDK 17
- 遇到"之前好的突然不行"的启动报错，检查 `.idea/misc.xml` 的 `languageLevel` 和 `project-jdk-name`

## 七、面试话术

**"你们的秒杀接口怎么防止刷单？"**

> 用 Redis ZSET 做了滑动窗口限流。每个用户请求进来时，Lua 脚本原子性地 ZADD 时间戳、ZREMRANGEBYSCORE 清掉 1 秒前的记录、ZCARD 计数，超过阈值就返回"请求过于频繁"。拦截器放在 LoginInterceptor 之后——保证 user 已加载，未登录直接 401 不浪费 Redis 往返。

**"为什么不用简单计数器？"**

> 计数器 + EXPIRE 是固定窗口，有边界效应——第 0.9 秒和第 1.1 秒窗口末端各发 10 个请求，实际 0.2 秒内来了 20 个。ZSET 滑动窗口用毫秒时间戳做 score，每次精确计算最近 1 秒，没有边界漏洞。

**"为什么不用 Sentinel？"**

> Sentinel 版本在下一轮迭代（CHANGELOG_12）。面试时用 Redis ZSET 手写一遍更体现代码功底——面试官想看的是你理解限流原理，而不是"引入 Sentinel 配置一下就完了"。生产环境优先用 Sentinel（Dashboard 可视化、规则热更新），但手写 ZSET 说明你懂底层。

## 八、待续：Phase 2 Sentinel

用 `@SentinelResource` + `blockHandler` 替换拦截器，Dashboard 可视化。详见 CHANGELOG_12（待切换分支后实施）。

# CHANGELOG_07 — 应用监控体系（Prometheus + Micrometer + Grafana）

> 日期：2026-07-15
> 说明：本文档为监控体系知识储备，不涉及实际代码改动。
> 目的：理解后端开发在监控体系中的职责，面试能讲清楚三层架构和自己的代码埋点。

---

## 一、先搞清三样东西

| 组件 | 一句话 | 谁负责 |
|------|--------|--------|
| **Micrometer** | Java 库，你在代码里用它埋点计数 | **你（后端开发）** |
| **Prometheus** | 独立程序，定时来你的应用取数据，存自己那 | DevOps / SRE |
| **Grafana** | 独立程序，从 Prometheus 取数据画成折线图/饼图/仪表盘 | DevOps / SRE |

类比：

```
Micrometer  = 体温计（你装在身上的传感器）
Prometheus = 护士站的记录本（护士每小时来读你的体温，记下来）
Grafana    = 病房墙上挂的显示屏（把记录本画成体温曲线，医生一目了然）
```

你的工作就是把体温计装好，数据格式对，让护士能读。护士和显示屏不是你管的事。

---

## 二、为什么需要这个

你用 Postman 测接口，一次一次发，看一次结果。这叫**手动测试**。

但应用上线后：

- 昨天 QPS 多少？峰值在几点？
- `/shop/of/name` 平均响应时间是不是变慢了？
- Redis 缓存到底挡了多少请求？命中率是多少？
- JVM 内存是不是快爆了？

你不会 24 小时盯着。你需要**代码自己记录**，然后有地方看。

这就是监控体系。

---

## 三、数据怎么流的

```
你的应用（hm-dianping:8081）
  │
  │  /actuator/prometheus  ← Micrometer 自动暴露的一个 HTTP 接口
  │                         访问它返回一大段文本，全是指标数据
  │
  ▼  Prometheus 每隔 15 秒 GET /actuator/prometheus 拉一次
Prometheus（时序数据库，默认 9090 端口）
  │
  ▼  Grafana 配好 Prometheus 数据源，选个图表模板
Grafana（可视化面板，默认 3000 端口）
  │
  ▼  你在浏览器打开
http://localhost:3000 → 看到漂亮的仪表盘
```

**核心就是**：`/actuator/prometheus` 这个接口吐出来的内容被 Prometheus 定时抓走。

---

## 四、/actuator/prometheus 返回什么

你 curl 一下（或者 Postman GET）：

```
GET http://localhost:8081/actuator/prometheus
```

返回的不是 JSON，而是纯文本：

```
# HELP jvm_memory_used_bytes JVM 已用内存
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap"} 2.8E8
jvm_memory_used_bytes{area="nonheap"} 1.2E8

# HELP http_server_requests_seconds HTTP 请求耗时
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{uri="/shop/of/name"} 8234
http_server_requests_seconds_seconds_sum{uri="/shop/of/name"} 49.2

# HELP shop_search_requests_total 店铺搜索请求次数
# TYPE shop_search_requests_total counter
shop_search_requests_total 8234
```

**格式解读**：

```
指标名{标签1="值1", 标签2="值2"} 当前数值
```

| 行 | 含义 |
|----|------|
| `jvm_memory_used_bytes{area="heap"} 2.8E8` | JVM 堆内存用了 280MB |
| `http_server_requests_seconds_count{uri="/shop/of/name"} 8234` | `/shop/of/name` 被调了 8234 次 |
| `shop_search_requests_total 8234` | 你自定义的店铺搜索计数器，当前 8234 |

---

## 五、四种指标类型（后端必须知道的）

Micrometer 定义了四种，对应 Prometheus 的四种。你不用记全，记住前两个就行。

### 5.1 Counter — 计数器（只增不减）

```
概念：只能往上加的计数器，永远不会减少。
类比：里程表——只能往前跑，不能往回拨。

什么时候用：
  - 接口被调了多少次
  - 登录失败了多少次
  - 秒杀卖出了多少单
```

### 5.2 Gauge — 瞬时值（可增可减）

```
概念：一个随时波动的数值，可大可小。
类比：油量表——随时上上下下。

什么时候用：
  - 当前 JVM 内存占用量
  - 当前 Redis 连接池活跃连接数
  - 缓存命中率（0.85 → 0.93 → 0.78，来回变）
```

### 5.3 Timer — 计时器

```
概念：记录每次执行的耗时，自动帮你统计 平均值/最大值/百分位。
类比：秒表——跑一圈按一次，最后看平均成绩。

什么时候用：
  - 接口响应时间
  - 数据库查询耗时
```

### 5.4 Summary/DistributionSummary — 分布统计

```
概念：记录每次请求的数据大小，统计分布。
类比：称重——每件快递多重，最后看 90% 的快递有多重。

什么时候用：
  - 响应体大小
  - 请求体大小
```

---

## 六、代码怎么写（埋点示例）

写法都一样：定义指标 → 在业务代码里记录 → Micrometer 自动暴露。

### 6.1 计数器：记录 QPS

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ShopServiceImpl implements IShopService {

    private final Counter searchCounter;

    public ShopServiceImpl(MeterRegistry registry) {
        // 定义一个计数器：名叫 "shop.search.requests"，描述是"店铺搜索请求次数"
        this.searchCounter = Counter.builder("shop.search.requests")
                .description("店铺搜索请求次数")
                .register(registry);
    }

    public List<Shop> searchByName(String name, Integer current) {
        searchCounter.increment();  // 每调一次 +1
        // ... 搜索逻辑
    }
}
```

### 6.2 Gauge：缓存命中率

```java
import io.micrometer.core.instrument.Gauge;

@Service
public class CacheMonitor {

    private volatile double cacheHitRatio = 0.0;  // volatile 保证多线程可见

    public CacheMonitor(MeterRegistry registry) {
        // Gauge 不主动更新，Prometheus 来拉的时候读一次当前值
        Gauge.builder("cache.hit.ratio", this, CacheMonitor::getRatio)
                .description("Redis 缓存命中率")
                .baseUnit("%")
                .register(registry);
    }

    public void setRatio(double ratio) { this.cacheHitRatio = ratio; }
    public double getRatio() { return cacheHitRatio; }
}
```

### 6.3 Timer：接口耗时

```java
private final Timer searchTimer;

public ShopServiceImpl(MeterRegistry registry) {
    this.searchTimer = Timer.builder("shop.search.duration")
            .description("店铺搜索耗时")
            .register(registry);
}

public List<Shop> searchByName(String name, Integer current) {
    return searchTimer.record(() -> {
        // record() 自动计时，执行完自动记录
        return shopIndexService.search(name, current, 10);
    });
}
```

### 6.4 Spring Boot 自动暴露的指标（零代码）

只要你加了依赖，下面这些都是自动的：

| 指标 | 含义 |
|------|------|
| `http_server_requests_seconds_count` | 每个 HTTP 接口被调了多少次 |
| `http_server_requests_seconds_sum` | 每个 HTTP 接口总耗时 |
| `jvm_memory_used_bytes` | JVM 内存占用量 |
| `jvm_gc_pause_seconds_count` | GC 次数 |
| `jvm_gc_pause_seconds_sum` | GC 总耗时 |
| `jvm_threads_live_threads` | 当前活跃线程数 |
| `process_cpu_usage` | 进程 CPU 占用率 |
| `hikaricp_connections_active` | 数据库活跃连接数 |
| `lettuce_connected_replicas` | Redis 连接数 |

JVM、HTTP、数据库连接池、Redis 全给你自动监控了。这才是 Spring Boot 的杀手锏。

---

## 七、实际改动清单（如果要改的话）

```
pom.xml:
  + micrometer-registry-prometheus  ← Micrometer 的 Prometheus 适配器
  + spring-boot-starter-actuator    ← 暴露 /actuator/* 端点

application.yaml:
  management:
    endpoints:
      web:
        exposure:
          include: prometheus,health  ← 暴露 /actuator/prometheus 和 /actuator/health

新建:
  config/MetricsConfig.java          ← 集中管理自定义 Counter/Gauge/Timer
  service/CacheMonitor.java          ← 缓存命中率监控

改动:
  ShopServiceImpl.java               ← 在 searchByName() 里加 searchCounter.increment()
  CacheClient.java                   ← 缓存命中/未命中时更新 cacheMonitor.setRatio()

总计: 新增 ~40 行代码，改 2 处业务代码
```

---

## 八、面试话术

### 简历写法

```
• 基于 Micrometer + Prometheus 实现应用可观测性，自定义 Counter（接口 QPS）、
  Gauge（缓存命中率）、Timer（接口耗时）三类指标，集成到 Spring Boot Actuator 暴露
```

### 面试官问"你们项目怎么做监控的？"

先把三层架构说清楚：

> 我们用 Spring Boot Actuator + Micrometer 在代码里埋点——Counter 记接口调用次数，Gauge 记缓存命中率，Timer 记耗时。Micrometer 统一暴露在 `/actuator/prometheus` 端点。运维那边部署的 Prometheus 每 15 秒来抓一次数据，Grafana 从 Prometheus 取数据画面板。
>
> 我作为后端主要负责：决定监控哪些指标、在代码里埋点、保证指标格式正确能被 Prometheus 消费。Prometheus 和 Grafana 的部署是运维做的。

### 面试官追问"你具体埋了哪些指标？"

> 业务指标三个：店铺搜索接口 QPS（Counter）、搜索平均耗时（Timer）、Redis 缓存命中率（Gauge）。Spring Boot 自动暴露的指标还有 HTTP 请求统计、JVM 内存和 GC、数据库连接池、Redis 连接数。这些不用写代码，actuator 自带。

### 面试官追问"Counter 和 Gauge 什么区别？"

> Counter 只增不减，适合累计量——接口总调用次数、登录失败总数。Gauge 是瞬时值，上下浮动——内存占用量、缓存命中率、在线用户数。Prometheus 底层其实只用 Counter 实现，Gauge 是伪装成可增可减的 Counter，但我们写代码时不用管这些。

### 面试官追问"缓存命中率怎么算的？"

> 每次查 Redis 命中 hitCount++，没命中 missCount++。Prometheus 来拉的时候计算 hitCount / (hitCount + missCount) 返回给 Gauge。不是实时推的，是拉的时候算一次，这样性能开销最小。

### 面试官追问"你们有没有配告警？"

> Grafana 里配了告警规则——接口 P99 响应时间超过 500ms 或 QPS 暴跌 50% 时，推钉钉消息到群。运维配的，我提供指标名。

### 不会回答时的兜底话术

> 我们项目体量不大，监控方面我用 Micrometer 在代码层做了指标暴露，Prometheus + Grafana 是运维配置的，我作为后端主要负责确定监控哪些指标和埋点。概念和原理我清楚，实际部署的话我会配合 DevOps 来做。

---

## 九、速记卡

```
监控体系速记
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
三层架构：Micrometer(你写) → Prometheus(运维装) → Grafana(运维装)

Micrometer 四种指标：
  Counter  = 里程表（只增不减）→ 接口调用次数
  Gauge    = 油量表（可增可减）→ 内存、缓存命中率、连接数
  Timer    = 秒表（自动统计耗时）→ 接口耗时
  Summary  = 称重（统计分布）→ 请求体大小

你的职责：决定监控什么 + 在代码里埋点 + 暴露 /actuator/prometheus

Spring Boot 自动暴露（零代码）：
  HTTP请求统计、JVM内存/GC、DB连接池、Redis连接

面试关键句：
  "Micrometer 是体温计，Prometheus 是护士的记录本，
   Grafana 是墙上的显示屏。我做的是把体温计装好。"
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

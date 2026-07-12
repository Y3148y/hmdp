# CHANGELOG — 测试环境隔离方案

## 概述

为项目构建完整的测试环境隔离方案，确保集成测试不会污染开发数据库（hmdp）和开发 Redis（db 0）。

同时顺带修复了一个预存的单元测试 bug（见第 4 条）。

## 修改文件清单

### 1. `src/test/resources/application-test.yaml` (新增)

测试环境专用配置文件，激活方式：`spring.profiles.active=test`

| 配置项 | 开发环境 | 测试环境 | 说明 |
|--------|---------|---------|------|
| MySQL 数据库 | hmdp | hmdp_test | 完全独立的数据库 |
| Redis 数据库索引 | 0 (默认) | 1 | 独立 Redis db，数据隔离 |
| 连接地址/端口/账号/密码 | 与 application.yaml 一致 | 与 application.yaml 一致 | 不改变连接信息 |

### 2. `src/test/java/com/hmdp/TestEnvironmentInitializer.java` (新增)

集成测试环境隔离基类，提供三个生命周期钩子：

| 钩子 | 时机 | 作用 |
|------|------|------|
| `@BeforeAll initTestDatabase()` | Spring 上下文加载前 | 检查并自动创建 hmdp_test 数据库（不存在则创建） |
| `@BeforeEach cleanTestTables()` | 每个测试方法前 | TRUNCATE 所有业务表（10 张表），确保每次测试数据独立 |
| `@AfterEach cleanRedisData()` | 每个测试方法后 | FLUSHDB 清空 Redis db 1 的全部数据 |

被清空的表（共 10 张）：
- tb_voucher_order
- tb_seckill_voucher
- tb_voucher
- tb_blog_comments
- tb_blog
- tb_follow
- tb_user_info
- tb_user
- tb_shop
- tb_shop_type

### 3. `src/test/java/com/hmdp/service/impl/VoucherOrderServiceImplTest.java` (修改)

**VoucherOrderIntegrationTest 内部类变更：**

- 添加 `extends TestEnvironmentInitializer` — 继承环境隔离能力
- `@SpringBootTest` 增加 `properties = "spring.profiles.active=test"` — 激活测试 profile
- 新增 import `com.hmdp.TestEnvironmentInitializer`

修改前后对比：
```java
// 修改前
@SpringBootTest(classes = HmDianPingApplication.class)
class VoucherOrderIntegrationTest {

// 修改后
@SpringBootTest(classes = HmDianPingApplication.class, properties = "spring.profiles.active=test")
class VoucherOrderIntegrationTest extends TestEnvironmentInitializer {
```

**注意**：外层的 `VoucherOrderServiceImplTest`（单元测试类，使用 Mockito）除修复第 4 条中的 bug 外，未做其他修改，仍使用 mock 方式运行。

### 4. 附带修复：单元测试预存 bug
- **位置**: `VoucherOrderServiceImplTest.java:93`
- **问题**: `verify(stringRedisTemplate, times(0)).execute(...)` — 期望方法"从未调用"
- **实际**: `seckillVoucher()` 成功路径必然调用 `stringRedisTemplate.execute()`
- **修复**: `times(0)` → `times(1)`
- **原因**: 该 bug 之前被 `mockito-core` 不支持 `mockStatic` 的错误屏蔽（测试在第 83 行提前 ERROR 退出，永远跑不到第 93 行）。引入 `mockito-inline` 后暴露。

## 如何验证环境隔离

### 前置条件
- MySQL 服务运行在 127.0.0.1:3306（账号 root/MySQL901）
- Redis 服务运行在 192.168.119.128:6379

### 验证步骤

**第 1 步 — 确认测试数据库自动创建**
```bash
# 运行任意一个集成测试
mvn test -Dtest=VoucherOrderIntegrationTest

# 控制台应输出:
# [TestEnv] 测试数据库 hmdp_test 已就绪
```

**第 2 步 — 验证 MySQL 隔离**
```sql
-- 检查测试库是否存在
SHOW DATABASES LIKE 'hmdp_test';

-- 开发库 hmdp 的数据应完整无损
SELECT COUNT(*) FROM hmdp.tb_voucher_order;

-- 测试库 hmdp_test 中测试后无残留数据
SELECT COUNT(*) FROM hmdp_test.tb_voucher_order;  -- 应为 0
```

**第 3 步 — 验证 Redis 隔离**
```bash
# 连接 Redis
redis-cli -h 192.168.119.128 -p 6379

# 开发库 db 0 的数据应完整无损
SELECT 0
KEYS *

# 测试库 db 1 的数据在测试后被清空
SELECT 1
KEYS *   # 应为 (empty array)
```

**第 4 步 — 运行单元测试确认不受影响**
```bash
mvn test -Dtest=VoucherOrderServiceImplTest
# 3 个单元测试应全部通过（使用 Mockito mock，不连接真实数据库）
```

## 注意事项

1. **首次运行**：`TestEnvironmentInitializer.@BeforeAll` 会自动创建 `hmdp_test` 数据库。如果 MySQL 用户没有 CREATE DATABASE 权限，需手动执行：
   ```sql
   CREATE DATABASE IF NOT EXISTS hmdp_test
   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
   ```

2. **表结构同步**：测试库 `hmdp_test` 需要有与开发库 `hmdp` 相同的表结构。MyBatis Plus 会在应用启动时根据实体类自动建表，或需手动导入 DDL。

3. **新的集成测试**：只需 `extends TestEnvironmentInitializer` 并在 `@SpringBootTest` 中指定 `properties = "spring.profiles.active=test"`，即可自动获得数据隔离能力。

4. **单元测试不受影响**：仅使用 Mockito 的单元测试（不标注 `@SpringBootTest`）不继承此基类，行为不变。

## 复盘提醒

- **测试专用配置文件一律放在 `src/test/resources/`**，不要放 `src/main/resources/`。
  - Spring Boot 的 test classpath 优先级高于 main classpath，放 test 目录同样能被 `spring.profiles.active=test` 加载。
  - 放 main 目录会被 `mvn package` 打进生产 jar 包，虽然不会造成数据污染（因为 profile 不激活），但测试配置出现在生产制品里就是不合规。
  - 将来新建 `application-{profile}.yaml` 时，先判断是测试用还是生产用，再决定放 `src/test/resources` 还是 `src/main/resources`。

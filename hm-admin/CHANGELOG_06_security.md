# CHANGELOG_06 — Spring Security + JWT + RBAC 后台权限管理

> 日期：2026-07-15
> 模块：hm-admin（独立项目）
> 方案：Spring Security 5.x + jjwt 0.9.1 + BCrypt + RBAC 五表 + 动态 URL 鉴权

---

## 一、背景

原有点评项目（hm-dianping）没有后台管理界面和权限体系，所有用户平权，登录为手机验证码 + Redis token。本次新建独立的 `hm-admin` 模块，实现标准后台权限管理系统。

两个项目完全隔离：

| 维度 | hm-dianping | hm-admin |
|------|------------|----------|
| 端口 | 8081 | 8082 |
| 数据库 | hmdp | hmdp_admin |
| 认证 | UUID Token + Redis | JWT（HS256 签名） |
| 鉴权 | 自写拦截器 | Spring Security Filter Chain |
| 权限模型 | 无 | RBAC（五表） |
| 登录 | 手机号 + 验证码 | 用户名 + BCrypt 密码 |

---

## 二、依赖变更 (pom.xml)

```xml
<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<!-- JWT (Java 8 兼容) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt</artifactId>
    <version>0.9.1</version>
</dependency>
<!-- 参数校验 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

---

## 三、项目结构

```
hm-admin/
├── pom.xml
└── src/main/
    ├── java/com/hmdp/admin/
    │   ├── AdminApplication.java
    │   ├── config/
    │   │   ├── SecurityConfig.java          ← Spring Security 总配置
    │   │   └── （已移除 DataInitializer，种子数据直接写进 init.sql）
    │   ├── security/
    │   │   ├── JwtTokenProvider.java         ← JWT 签发 + 验签
    │   │   ├── JwtAuthenticationFilter.java  ← 从 Header 提取 JWT
    │   │   ├── AdminUserDetails.java         ← UserDetails 实现
    │   │   ├── AdminUserDetailsService.java  ← 查 DB 加载用户/角色/权限
    │   │   └── JwtAuthenticationEntryPoint.java ← 401 返回 JSON
    │   ├── entity/
    │   │   ├── AdminUser.java
    │   │   ├── AdminRole.java
    │   │   └── AdminPermission.java
    │   ├── mapper/
    │   │   ├── AdminUserMapper.java          ← 含 selectByUsernameWithRoles
    │   │   ├── AdminRoleMapper.java
    │   │   └── AdminPermissionMapper.java
    │   ├── controller/
    │   │   ├── AuthController.java           ← POST /auth/login
    │   │   └── UserManageController.java     ← CRUD /admin/users
    │   └── dto/
    │       ├── LoginRequest.java
    │       └── LoginResponse.java
    └── resources/
        ├── application.yml
        └── db/init.sql                       ← 建表 DDL
```

---

## 四、RBAC 表结构（五表）

```
  admin_user              admin_role             admin_permission
  ┌──────────────┐       ┌──────────────┐       ┌──────────────────┐
  │ id           │       │ id           │       │ id               │
  │ username     │       │ name         │       │ name             │
  │ password     │       │ code         │       │ code (如 user:list)
  │ nickname     │       │ (ROLE_ADMIN) │       │ url (如 /admin/users)
  │ enabled      │       └──────────────┘       │ description      │
  └──────────────┘                              └──────────────────┘
       │                                                  │
       │  admin_user_role                                 │ admin_role_permission
       │  ┌──────────────┐                               │ ┌─────────────────┐
       ├──┤ user_id(FK)  │                               │ │ role_id(FK)     │
       │  │ role_id(FK)  │                               │ │ permission_id   │
       │  └──────────────┘                               │ └─────────────────┘
       │                                                  │
       └──────────── 用户最终权限 = 角色 + 权限 ──────────────┘
```

建表 DDL 见 `db/init.sql`。

---

## 五、登录流程

```
POST /auth/login  { "username": "admin", "password": "admin123" }

1. AuthController 接收 → AuthenticationManager.authenticate()
2. AdminUserDetailsService.loadUserByUsername("admin")
     ├─ SELECT * FROM admin_user WHERE username = 'admin'
     ├─ SELECT r.* FROM admin_role r
     │   JOIN admin_user_role ur ON r.id = ur.role_id WHERE ur.user_id = 1
     ├─ SELECT p.* FROM admin_permission p
     │   JOIN admin_role_permission rp ON p.id = rp.permission_id
     │   WHERE rp.role_id IN (...)
     └─ 返回 AdminUserDetails { username, password(BCrypt), authorities }

3. DaoAuthenticationProvider 校验密码 BCrypt.matches()
4. JwtTokenProvider.generateToken()
     ├─ Payload: { sub, roles: "ROLE_SUPER_ADMIN", perms: "user:list,role:manage,...",
     │             exp: now + 2h }
     └─ HS256 签名 → "eyJhbGci..."

5. 返回 { token: "eyJhbGci...", tokenType: "Bearer", username: "admin" }
```

---

## 六、请求鉴权流程

```
GET /admin/users  Header: Authorization: Bearer eyJhbGci...

1. JwtAuthenticationFilter（OncePerRequestFilter）
     ├─ 提取 "Bearer " 后 JWT
     ├─ JwtTokenProvider.validateToken() → 验签 + 过期检查
     ├─ JwtTokenProvider.getAuthentication()
     │   └─ 解析 payload 中 roles + perms → UsernamePasswordAuthenticationToken
     └─ SecurityContextHolder.setAuthentication()

2. FilterSecurityInterceptor
     ├─ 当前请求 URL: GET /admin/users
     ├─ 数据库配置该 URL 需要的权限: user:list
     ├─ 用户有: [ROLE_SUPER_ADMIN, user:list, user:create, ...]
     ├─ 交集非空 → 放行 ✅
     └─ 否则 → 403

3. Controller 收到请求，@PreAuthorize 二次校验（可选）
     @PreAuthorize("hasAuthority('user:list')")
```

**不查 DB 的原因**：JWT payload 里已编码角色和权限标识，验签通过即可信任。代价是角色/权限变更后旧 token 的 2h 过期时间窗口内有延迟，后台管理场景可接受。

---

## 七、SecurityConfig 完整配置（带注释）

```java
/**
 * Spring Security 总配置。
 *
 * 四个职责：
 * 1. 定义密码加密方式（BCrypt）
 * 2. 定义登录数据源（AdminUserDetailsService → 查 DB）
 * 3. 定义 URL 规则（放行哪些 / 哪些要什么权限 / 动态从 DB 加载）
 * 4. 把 JWT 过滤器插入 Security 过滤链
 */
@Configuration
@EnableWebSecurity                    // 开启 Security，用自己的规则
@EnableGlobalMethodSecurity(prePostEnabled = true)  // 让 @PreAuthorize 生效
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    // === Bean ===

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();  // BCrypt 单向哈希，自带随机盐
    }

    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();  // 暴露出去，Controller 登录用
    }

    // === 认证：怎么验证一个人 ===

    @Override
    protected void configure(AuthenticationManagerBuilder auth) {
        auth.userDetailsService(adminUserDetailsService)  // 数据源：查 DB
            .passwordEncoder(passwordEncoder());           // 比对：BCrypt
    }

    // === 安全：什么人能访问什么 ===

    @Override
    protected void configure(HttpSecurity http) {

        // Step 1: 从 DB 加载 URL→权限 映射
        List<AdminPermission> perms = permissionMapper.selectList(null);

        // Step 2: 关闭 CSRF + 不创建 Session
        http.csrf().disable()
            .sessionManagement()
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS);

        // Step 3: 未登录 → 返回 {"code":401,"message":"未登录或 Token 已过期"}
        http.exceptionHandling()
            .authenticationEntryPoint(jwtAuthenticationEntryPoint);

        // Step 4: URL 权限规则（按代码顺序匹配，命中即停）
        http.authorizeRequests()
            .antMatchers("/auth/login").permitAll()          // 登录放行
            .antMatchers(HttpMethod.OPTIONS).permitAll();    // 预检放行

        // 动态注册：/admin/users → user:list, /admin/roles → role:manage ...
        for (AdminPermission p : perms) {
            http.authorizeRequests()
                .antMatchers(p.getUrl()).hasAuthority(p.getCode());
        }

        http.authorizeRequests()
            .anyRequest().authenticated();  // 兜底：其他全要登录

        // Step 5: JWT 过滤器插到 Security 内置过滤器前面
        http.addFilterBefore(jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class);
    }
}
```

---

## 七-B、请求处理完整流程

### 登录请求

```
POST /auth/login { "username": "admin", "password": "admin123" }
  │
  ├─ [JwtAuthenticationFilter]
  │   └─ 请求头无 Authorization → 跳过，不设 Authentication
  │
  ├─ [规则匹配]
  │   └─ 路径 /auth/login → permitAll() → 直接放行，不检查权限
  │
  ├─ AuthController.login()
  │   └─ authenticationManager.authenticate(username, password)
  │       │
  │       ├─ AdminUserDetailsService.loadUserByUsername("admin")
  │       │   ├─ 查 admin_user WHERE username='admin'
  │       │   ├─ 查 admin_role（通过 admin_user_role 中间表）
  │       │   ├─ 查 admin_permission（通过 admin_role_permission 中间表）
  │       │   └─ 返回 AdminUserDetails {
  │       │         username: "admin",
  │       │         password: "$2a$10$MzZ1VTBN...",  ← BCrypt 哈希
  │       │         authorities: [ROLE_SUPER_ADMIN, user:list, user:create, ...]
  │       │       }
  │       │
  │       └─ BCryptPasswordEncoder.matches("admin123", "$2a$10$MzZ1V...")
  │           └─ true → 认证成功
  │
  ├─ JwtTokenProvider.generateToken(authentication)
  │   ├─ Payload: { sub: "admin", roles: "ROLE_SUPER_ADMIN",
  │   │             perms: "user:list,user:create,...", exp: now+2h }
  │   └─ HS256 签名 → "eyJhbGci..."  (JWT 三段式)
  │
  └─ 返回 { "code": 200, "data": { "token": "eyJhbGci...", "username": "admin" } }
```

### 带 JWT 的请求

```
GET /admin/users  Header: { Authorization: "Bearer eyJhbGci..." }
  │
  ├─ [JwtAuthenticationFilter]  ← OncePerRequestFilter，每个请求只执行一次
  │   ├─ 取 Header "Authorization"
  │   ├─ 去掉 "Bearer " 前缀 → 拿到纯 JWT
  │   ├─ JwtTokenProvider.validateToken(jwt)
  │   │   └─ Jwts.parser().setSigningKey(secret).parseClaimsJws(jwt)
  │   │       ├─ 签名不匹配 → 异常 → validateToken 返回 false → 跳过
  │   │       ├─ 已过期 → 异常 → validateToken 返回 false → 跳过
  │   │       └─ 合法 → 解析 payload 成功
  │   ├─ JwtTokenProvider.getAuthentication(jwt)
  │   │   └─ 从 payload 提取 subject(用户名)、roles、perms
  │   │       → 组装 UsernamePasswordAuthenticationToken
  │   │         { principal: "admin", authorities: [ROLE_SUPER_ADMIN, user:list, ...] }
  │   └─ SecurityContextHolder.getContext().setAuthentication(auth)
  │       ↑ 把认证信息存入当前线程的安全上下文
  │
  ├─ [FilterSecurityInterceptor]  ← Security 内置，负责 URL 权限匹配
  │   ├─ 当前请求 URL: GET /admin/users
  │   ├─ 查配置规则：/admin/users → 需要权限 user:list
  │   ├─ 查当前用户权限列表：[ROLE_SUPER_ADMIN, user:list, user:create, ...]
  │   ├─ hasAuthority("user:list") → 用户权限列表包含 "user:list" → 放行 ✅
  │   └─ 如果没有匹配的权限 → 返回 403 Forbidden
  │
  ├─ UserManageController.listUsers()
  │   └─ @PreAuthorize("hasAuthority('user:list')")  ← 方法级二次校验
  │       └─ 通过 → 执行方法体 → 返回用户列表
  │
  └─ 请求结束，SecurityContextHolder 自动清理
```

### 无权限请求

```
GET /admin/users  Header: { Authorization: "Bearer <user_token>" }
  │
  ├─ [JwtAuthenticationFilter]
  │   └─ 解析 JWT → authorities: [ROLE_ADMIN, user:list]  ← user 只有 user:list
  │
  ├─ [FilterSecurityInterceptor]
  │   ├─ URL /admin/users → 需要权限 user:create
  │   ├─ 用户权限：[ROLE_ADMIN, user:list]
  │   ├─ "user:create" 不在列表中 → 403 Forbidden ❌
  │   └─ 返回 403 状态码
  │
  └─ Controller 未执行
```

### 未登录请求

```
GET /admin/users  Header: {}  (无 Authorization)
  │
  ├─ [JwtAuthenticationFilter]
  │   └─ Header 为空 → 跳过，不设 Authentication
  │
  ├─ [FilterSecurityInterceptor]
  │   └─ 当前用户未认证 → 触发了 ExceptionTranslationFilter
  │
  ├─ [JwtAuthenticationEntryPoint.commence()]
  │   ├─ response.setStatus(401)
  │   └─ response body: {"code":401,"message":"未登录或 Token 已过期"}
  │
  └─ Controller 未执行
```

### 过滤链完整顺序

```
HTTP 请求
  │
  ├─ ChannelProcessingFilter          ← 检查 HTTPS 等通道安全
  ├─ SecurityContextPersistenceFilter  ← 从 Session 恢复 SecurityContext（已关闭）
  ├─ JwtAuthenticationFilter          ← ★ 你的过滤器（提取 JWT → 验签 → 设认证）
  ├─ UsernamePasswordAuthenticationFilter ← Security 内置表单登录（JWT 已设认证则跳过）
  ├─ ExceptionTranslationFilter       ← 捕获异常 → 转给 EntryPoint 处理
  ├─ FilterSecurityInterceptor        ← ★ URL 权限匹配（核心鉴权）
  │
  └─ Controller
```

---

## 七-C、一图看懂所有类（对照表 + 数据流）

### 每个类是干什么的

```
                     ┌─────────────────────────────────────────────┐
                     │              你的项目（hm-admin）             │
                     │                                             │
  浏览器              │  ① 拦截请求   ② 查用户     ③ 签发/验签 JWT  │
  POST /auth/login   │                                             │
 ──────────────────► │  SecurityConfig  AdminUserDetailsService   │
                     │  "谁可以访问     "根据用户名       JwtTokenProvider
  带 JWT 的请求       │   什么URL"      查DB返回用户"    "生成token/
 ──────────────────► │                                             │
                     │      ↓              ↓               ↓       │
                     │  ┌──────────┐ ┌───────────┐ ┌───────────┐  │
                     │  │保安队长   │ │  HR系统   │ │ 工牌制作机 │  │
                     │  └──────────┘ └───────────┘ └───────────┘  │
                     │                                             │
                     │  ④ 拦截器（每个请求过一遍）                    │
                     │  JwtAuthenticationFilter                    │
                     │  "看工牌 → 验真伪 → 贴胸牌"                   │
                     │      ↓                                      │
                     │  ┌──────────┐                               │
                     │  │  门禁闸机  │                              │
                     │  └──────────┘                               │
                     │                                             │
                     │  ⑤ 全局盒子（ThreadLocal）                    │
                     │  SecurityContextHolder                      │
                     │  "存当前请求的用户信息"                        │
                     │      ↓                                      │
                     │  ┌──────────┐                               │
                     │  │  临时胸牌  │                              │
                     │  └──────────┘                               │
                     │                                             │
                     │  ⑥ 权限比对（Security 自动做）                 │
                     │  FilterSecurityInterceptor                  │
                     │  "你的胸牌权限  vs  这个门需要的权限"           │
                     │      ↓                                      │
                     │  ┌──────────┐                               │
                     │  │  刷卡验证  │                              │
                     │  └──────────┘                               │
                     └─────────────────────────────────────────────┘
```

### 一个真实请求走过的东西

```
你的浏览器                          Spring Security 过滤链                     Controller
─────────                          ──────────────────────                     ──────────

POST /auth/login
{username,password}
  │
  │  Authorization 头为空
  │  → JwtAuthenticationFilter 跳过（没工牌，不查）
  │  → URL 是 /auth/login → permitAll → 放行
  │                                                                    → AuthController.login()
  │                                                                       │
  │                                                                       ├─ 调 AuthenticationManager
  │                                                                       │   └─ AdminUserDetailsService
  │                                                                       │       查 DB 验证用户名密码
  │                                                                       │
  │                                                                       └─ JwtTokenProvider
  │                                                                            生成 JWT 返回
  │  ← {"token":"eyJhbG...","username":"admin"}  ←──────────────────────────┘


GET /admin/users
Header: Bearer eyJhbG...
  │
  ├── JwtAuthenticationFilter.doFilterInternal()
  │     │  从 Header 拿 JWT
  │     │  → JwtTokenProvider.validateToken()  验签 + 查过期
  │     │  → JwtTokenProvider.getAuthentication()  解析出 username/roles/perms
  │     │  → SecurityContextHolder.setAuthentication()  存入 ThreadLocal
  │     │
  │     └── 放行，给后面的过滤器
  │
  ├── FilterSecurityInterceptor（Security 内置）
  │     │  当前 URL: /admin/users
  │     │  规则: 这个 URL 需要 user:list 权限（SecurityConfig 已从 DB 加载）
  │     │  用户权限: [user:list, user:create, ...]（从 SecurityContextHolder 取）
  │     │  匹配成功 → 放行
  │     │
  │     └── 放行
  │                                                                    → UserManageController.listUsers()
  │                                                                       │
  │                                                                       ├─ @PreAuthorize("hasAuthority('user:list')")
  │                                                                       │   二次校验通过
  │                                                                       │
  │                                                                       └─ 返回用户列表
  │  ← [{"username":"admin",...},{"username":"user",...}]  ←─────────────┘
```

### 类名 → 大白话对照表

```
SecurityConfig                  保安队长    安排谁查什么人能进哪个门
JwtAuthenticationFilter        门禁闸机    每个请求过一遍：看工牌→验真伪→贴胸牌
JwtTokenProvider               工牌制作机  登录时做新工牌(.generateToken)
                                           进门时验工牌(.validateToken)
SecurityContextHolder           临时胸牌    ThreadLocal，存当前请求的用户
AdminUserDetailsService         HR系统     输入用户名，输出这个人+角色+权限
AdminUserDetails                员工档案袋  装用户名、密码(加密)、角色、权限
JwtAuthenticationEntryPoint    门禁报警器  没工牌→返回401 JSON
AuthenticationManager           打卡机     用户名密码输入→内部调HR系统→通过就放行
PasswordEncoder(BCrypt)         密码锁     你输入的密码 比对 数据库存的加密密码
@PreAuthorize                   门禁刷卡    "这个房间需要user:list卡"
FilterSecurityInterceptor      自动门禁    Security内置，URL vs 权限自动匹配
OncePerRequestFilter           一道闸机    每个请求只过一次，不会重复拦
UsernamePasswordAuthenticationToken 标准工牌  Security要求的统一格式，装用户信息用
```

### 数据存在哪

```
登录时（用户名密码）：请求体 → Controller 收到 → AuthenticationManager → DB 查 → 校验通过
登录后（JWT Token）：  response 返回 → 浏览器存着 → 后续请求放在 Header 里带过来
运行时（用户信息）：    JWT 解析后 → 塞进 SecurityContextHolder（ThreadLocal）→ 请求结束自动清
权限规则（URL→权限）：  数据库 admin_permission 表 → 启动时 SecurityConfig 加载 → 内存
```

---

## 七-D、Spring Security 做了什么 vs 我做了什么

### 一句话

**你只做了"认人（认证）"和"定规矩（授权规则）"，真正执行拦截/比对/拒绝的是 Spring Security 内置的 Filter Chain。**

### 分工表

| | Spring Security 内置（没写过的） | 你写的 |
|---|---|---|
| **拦截每个请求** | `FilterSecurityInterceptor` 自动拦每个请求，查 URL 匹配规则 | 无 |
| **提取 Token** | `UsernamePasswordAuthenticationFilter`（表单登录，你没用） | `JwtAuthenticationFilter`（从 Header 取 Bearer Token） |
| **验签/校验密码** | `DaoAuthenticationProvider` 比对密码 | `JwtTokenProvider.validateToken()` 验 JWT 签名 |
| **查用户信息** | `InMemoryUserDetailsManager`（内存用户，你没用） | `AdminUserDetailsService.loadUserByUsername()` 查 DB |
| **用户信息格式** | `UserDetails` 接口定义了标准格式 | `AdminUserDetails` 实现这个接口，把 DB 数据包进去 |
| **存当前用户** | `SecurityContextHolder`（ThreadLocal） | 无——你在 Filter 里调 `setAuthentication()` 往里放 |
| **URL 规则定义** | `authorizeRequests()` API 让你声明规则 | `SecurityConfig.configure(HttpSecurity)` 里声明：哪些 URL 放行、哪些要什么权限 |
| **动态加载 URL→权限** | 无——Security 只认你代码里写的规则 | `SecurityConfig` 启动时从 `admin_permission` 表加载，按 URL 合并后用 `hasAnyAuthority` 注册 |
| **执行鉴权** | `FilterSecurityInterceptor` 拿 URL → 查规则 → 拿用户权限 → 比对 → 放行/403 | 无 |
| **方法级鉴权** | `@PreAuthorize` / `@PostAuthorize` 注解解析器 | 你在 Controller 方法上写 `@PreAuthorize("hasAuthority('user:list')")` |
| **未登录处理** | 默认 302 重定向到 `/login` 页面 | `JwtAuthenticationEntryPoint` 返回 401 JSON |
| **权限不足处理** | 默认返回 403 页面 | 无（默认行为就够用——返回 403 状态码） |
| **密码加密** | `BCryptPasswordEncoder` 类 | 你在 `SecurityConfig` 里声明为 Bean |
| **登录认证执行** | `AuthenticationManager.authenticate()` ——内部串起查用户 + 比密码 | `AuthController` 调它 |

### 数据流里的归属

```
请求进来
  │
  └─ JwtAuthenticationFilter          ← 你写的（提取 Token）
       │
       └─ JwtTokenProvider            ← 你写的（验签 + 解析）
            │
            └─ SecurityContextHolder  ← Security 内置（ThreadLocal 盒子）
                 │
                 └─ FilterSecurityInterceptor  ← Security 内置 ★ 最核心
                      │
                      ├─ 当前 URL: /admin/users
                      ├─ 查规则: 这个 URL 要什么权限？ ← 规则是你写的（SecurityConfig）
                      ├─ 查用户: 当前用户有什么权限？ ← 信息是你写的（JWT → Authentication）
                      ├─ 比对
                      ├─ 有权限 → 放行
                      └─ 没权限 → 403
                           │
                           └─ Controller.method()
                                │
                                └─ @PreAuthorize      ← 注解是你写的，执行是 Security 内置
```

### 面试话术

> **问：Spring Security 你做了哪些，它自己做了哪些？**
>
> 我主要负责认证和授权规则的配置。认证这边写了 JwtAuthenticationFilter 做 Token 提取和验签，AdminUserDetailsService 做 DB 用户查询。授权这边写 SecurityConfig——从数据库加载 URL→权限映射，动态注册到 Security 的过滤链里。
>
> Spring Security 本身负责执行：FilterSecurityInterceptor 自动拦截每个请求，拿 URL 匹配我配置的规则，从 SecurityContextHolder 取当前用户权限，做比对——权限够就放行，不够就 403。这套拦截/比对/拒绝的流程我没有写一行代码，全是 Security 内置 Filter Chain 做的。

---

## 八、权限注解示例

```java
// 方法级权限控制
@PreAuthorize("hasAuthority('user:list')")
@GetMapping("/admin/users")
public Object listUsers() { ... }

@PreAuthorize("hasAuthority('user:create')")
@PostMapping("/admin/users")
public Object createUser() { ... }

// 多权限满足其一
@PreAuthorize("hasAnyAuthority('user:update', 'role:manage')")

// 同时满足角色+权限
@PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('user:delete')")
```

---

## 九、种子数据

种子数据直接写在 `init.sql` 中（BCrypt 哈希预计算），执行建表即完成：

| 用户名 | 密码 | 角色 | 权限 |
|--------|------|------|------|
| admin | admin123 | ROLE_SUPER_ADMIN | 全部 6 个权限 |
| user | user123 | ROLE_ADMIN | 仅 user:list |

密码使用 BCrypt 加密存储。

---

## 十、启动与测试

```bash
# 1. 建库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS hmdp_admin DEFAULT CHARSET utf8mb4"

# 2. 建表（执行 init.sql）
mysql -u root -p hmdp_admin < src/main/resources/db/init.sql

# 3. 启动（首次自动写入种子数据）
cd D:/IDEAprojects/hm-admin
mvn spring-boot:run

# 4. 测试登录
POST http://localhost:8082/auth/login
Body: { "username": "admin", "password": "admin123" }
# → 返回 JWT token

# 5. 测试鉴权
GET http://localhost:8082/admin/users
Header: Authorization: Bearer <token>
# → 返回用户列表

# 6. 测试权限不足 → 403
# 用 user/user123 登录，访问 /admin/users 创建用户
POST http://localhost:8082/admin/users
Header: Authorization: Bearer <user_token>
# → 403 Forbidden（user 只有 user:list，没有 user:create）
```

---

## 十一、面试话术

### 简历一句话

```
基于 Spring Security + JWT 实现后台 RBAC 权限管理系统，设计五表模型与动态 URL 鉴权，
支持方法级 @PreAuthorize 注解控制，JWT 自包含角色权限信息实现无状态认证
```

### 简历 Bullet Points

```
• 基于 Spring Security + JWT 构建无状态认证体系，自定义 OncePerRequestFilter
  从 Authorization Header 提取令牌，验签后自动装配 SecurityContext

• 设计 RBAC 五表权限模型（用户-角色-权限 + 两张中间表），
  实现动态 URL 鉴权：权限与 URL 映射存于 DB，应用启动时加载至 Security Filter Chain

• 自定义 UserDetailsService 一次性加载用户/角色/权限，编码至 JWT payload，
  后续请求零 DB 查询，验签即完成鉴权

• BCrypt 加密存储密码，401/403 统一返回 JSON，配置 @EnableGlobalMethodSecurity
  开启 @PreAuthorize 方法级注解控制
```

### STAR 叙述

**S**：点评项目没有后台管理系统，所有接口无权限区分。

**T**：独立设计后台管理模块，实现用户名密码登录 + RBAC 权限控制。

**A**：
- 选型 Spring Security 5.x + jjwt 0.9.1（Java 8 兼容）
- 设计五表 RBAC：用户、角色、权限、两张中间表
- JWT payload 编码角色+权限，验签后直接拿到全部信息，不查 DB
- 动态 URL 鉴权：DB 存 URL→权限映射，启动时加载到 SecurityConfig
- `@PreAuthorize` 注解做方法级二次校验
- 种子数据 `CommandLineRunner` + `@Profile("!test")` 自动初始化

**R**：无状态认证 + RBAC 完整闭环。新增权限只需在 DB 插入一条记录，无需改代码重启。

### 面试官追问

**Q: 为什么用 JWT 而不是 Redis session？**

后台管理系统网络暴露面小（内网/VPN），JWT 无状态更合适——服务端不存任何东西，验签通过即放行。配合 Authorization header 跨域无 Cookie 问题。Redis session 的优势在于集中管控（即时踢人、统一过期），后台场景对即时性要求不高，不需要引入 Redis 做 session 存储。

**Q: 为什么不做双 Token（Access Token + Refresh Token）？**

先搞清楚双 Token 解决什么问题——三个核心场景：

1. **公网暴露**：C 端 App 的请求走公网，Token 可能在公共 WiFi、中间人攻击中被窃取。Access Token 15min 过期 → 泄露窗口极短。后台系统跑内网/VPN，攻击者根本接触不到请求。
2. **用户不能被打断**：用户正刷着 App，Token 突然过期弹登录页 → 体验灾难。后台运营是"上班登录，干完就走"，2h 过期重新登一次不构成问题。
3. **即时踢人**：删 Redis 里的 Refresh Token → 最多等 Access Token 过期（15min）就自动下线。后台没这个需求——开除了直接删 DB 里的 enabled 字段，JWT 过期后自然无法登录。

**判断标准不是用户数量，是使用场景。** 100 个后台用户也是内网 + 短时间使用，单 Token 够。2 个 C 端用户如果是公网 App + 全天挂着，就该上双 Token。

本题项目后台管理 → 三样都不占 → 单 JWT 是正确的选型，双 Token 是过度设计。

**Q: 密码怎么存的？**

单 JWT 简化版做不到即时踢人，需等 token 过期（2h）。完整方案加 Refresh Token 存 Redis：删 Refresh Token → Access Token 30min 过期后自动失效。后台管理对即时性要求不高，2h 可接受。

**Q: 为什么角色和权限都放 JWT 里？不怕 token 太大？**

5 个角色 + 20 个权限的字符串不到 200 字节，base64 后 ~270 字节。HTTP header 限制一般是 8KB，完全不构成问题。优势是验签后直接鉴权，零 DB 查询。

**Q: 怎么扩展新的受保护接口？**

1. DB 插入权限记录：`INSERT INTO admin_permission (code, url) VALUES ('order:manage', '/admin/orders')`
2. Controller 方法加 `@PreAuthorize("hasAuthority('order:manage')")`
3. 重启应用，SecurityConfig 加载新 URL→权限映射

**Q: 密码怎么存的？**

BCrypt 加密，`BCryptPasswordEncoder.encode(rawPassword)`。即使数据库被拖库，密码原文不会泄露。BCrypt 自带 salt，两次对同一密码加密结果不同。

**Q: 怎么踢人下线？**

单 JWT 简化版做不到即时踢人，需等 token 过期（2h）。后台管理场景——直接删 DB 里用户的 enabled 字段，该用户无法再登录，等旧 token 过期自然失效。如果需要即时踢人（比如金融系统），加 Refresh Token 存 Redis 即可：删 Refresh Token → 等 Access Token 过期（15min）后自动下线。

### 速记卡

```
Spring Security + JWT + RBAC 速记
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
架构：hm-admin 独立模块，端口 8082，DB hmdp_admin
认证：JWT（HS256），2h 过期，header Authorization: Bearer
组件：JwtTokenProvider → 签发/验签
      JwtAuthenticationFilter → OncePerRequestFilter
      AdminUserDetailsService → loadUserByUsername
      JwtAuthenticationEntryPoint → 401 JSON
RBAC：admin_user / admin_role / admin_permission + 2 中间表
动态鉴权：DB 加载 URL→权限 → SecurityConfig 注册
方法鉴权：@EnableGlobalMethodSecurity + @PreAuthorize
种子数据：admin/admin123（SUPER_ADMIN），user/user123（ADMIN）
安全：CSRF 关闭 / Session 无状态 / BCrypt 加密
关键：JWT payload 含 roles+perms → 验签后零 DB 查询
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

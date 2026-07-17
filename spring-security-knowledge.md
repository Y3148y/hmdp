# Spring Security 知识体系

> 日期：2026-07-17
> 类型：知识储备文档，不涉及代码改动。
> 目的：理解 Spring Security 的架构、能力边界、以及你在项目中需要做什么。

---

## 一、本质：一条过滤器链

Spring Security 不神秘，它本质上就是**插在 HTTP 请求和 Controller 之间的一组 Filter**。

```
没有 Spring Security：
  HTTP 请求 ────────────────────────────→ Controller → 返回数据
  谁都能调，不用登录，没有权限控制

有了 Spring Security：
  HTTP 请求 ──→ [F₁] → [F₂] → ... → [F₁₀] → [F₁₁] ──→ Controller → 返回数据
                ↑      ↑              ↑       ↑
              这些 Filter 就是 Spring Security 加的，负责拦截和鉴权
```

## 二、15 个内置 Filter（按顺序）

每个 HTTP 请求按顺序经过下面这些过滤器。你写的 `JwtAuthenticationFilter` 也是插在这个链里。

| # | Filter | 干什么 | 你动过没 |
|---|--------|--------|:---:|
| 1 | `ChannelProcessingFilter` | 检查请求走的是 HTTP 还是 HTTPS，可强制 HTTPS | ✗ |
| 2 | `WebAsyncManagerIntegrationFilter` | 让 SecurityContext 能在异步线程里用 | ✗ |
| 3 | `SecurityContextPersistenceFilter` | 请求开始从 Session 恢复 SecurityContext，请求结束保存 | ✗（Session 关了，这个等于空过） |
| 4 | `HeaderWriterFilter` | 给响应加安全头（X-Content-Type-Options 等） | ✗ |
| 5 | `LogoutFilter` | 处理 /logout，清 SecurityContext + 清 Session | ✗（JWT 无状态，没用） |
| 6 | **`JwtAuthenticationFilter`** ← 你写的 | 从 Header 取 JWT → 验签 → 解析用户 → 设 SecurityContext | **✓** |
| 7 | `UsernamePasswordAuthenticationFilter` | 处理表单登录 POST /login | ✗（JWT 已设认证，被跳过） |
| 8 | `RequestCacheAwareFilter` | 恢复被中断的请求（登录后跳回原页面） | ✗ |
| 9 | `SecurityContextHolderAwareRequestFilter` | 包装 HttpServletRequest，加 Security 方法 | ✗ |
| 10 | `AnonymousAuthenticationFilter` | 没登录的人给一个 anonymous 身份 | ✗ |
| 11 | `SessionManagementFilter` | 管理 Session 并发控制 | ✗（无 Session） |
| 12 | `ExceptionTranslationFilter` | 捕获认证/授权异常，转给 EntryPoint 处理 | ✗ |
| 13 | **`FilterSecurityInterceptor`** ★ 最核心 | 拿当前 URL → 查你配的规则 → 拿用户权限 → 比对 → 放行/403 | ✗（规则是你配的，执行是它做的） |

## 三、它自动做了的事（你不写代码也能用的能力）

| 能力 | 说明 |
|------|------|
| **拦截所有请求** | 默认每个请求都必须登录，不需要额外代码 |
| **密码加密** | 提供 `BCryptPasswordEncoder`、`PBKDF2`、`SCrypt`、`SHA-256`，引入即用 |
| **登录认证流程** | `AuthenticationManager` 自动串起 UserDetailsService（查用户）+ PasswordEncoder（比密码） |
| **CSRF 防护** | 自动生成并校验 CSRF Token（你用 JWT 所以主动关了） |
| **Session 管理** | 登录后自动创建 Session，支持分布式 Session 托管（你用 JWT 主动关了） |
| **退出登录** | `/logout` 自动清 SecurityContext + 清 Session |
| **401/403 处理** | 自动拦截异常返回 HTTP 状态码 |
| **SecurityContext 存储** | 提供 `SecurityContextHolder`（ThreadLocal），请求级全局可用 |
| **方法级安全** | 只需加 `@EnableGlobalMethodSecurity` + 方法上写 `@PreAuthorize` |
| **匿名用户** | 未登录自动给匿名身份，配合 `isAnonymous()` 使用 |
| **Remember Me** | 记住我功能，勾选后长期免登录 |

## 四、你必须自己做的事

这些它做不到自动，因为 Spring Security 不知道你的业务：

| 你必须写的 | 为什么不能自动 | 对应文件 |
|-----------|---------------|---------|
| **用户数据怎么查** | 不知道你的表结构，不知道哪个字段是用户名、哪个是密码 | `AdminUserDetailsService` |
| **用户信息怎么包装** | UserDetails 是接口，你要适配自己的实体类 | `AdminUserDetails` |
| **Token 怎么提取和验签** | 默认只认表单登录，不认识 JWT | `JwtAuthenticationFilter` |
| **验签逻辑** | 不知道你的密钥、过期时间、签名算法 | `JwtTokenProvider` |
| **哪些 URL 放行哪些要什么权限** | 不知道你的业务规则 | `SecurityConfig.configure(HttpSecurity)` |
| **没登录返回什么格式** | 默认返回 HTML 登录页，你要返回 JSON | `JwtAuthenticationEntryPoint` |
| **暴露 AuthenticationManager** | 默认不暴露为 Bean | `SecurityConfig.authenticationManagerBean()` |

## 五、执行 vs 定义 分工

```
                                    你写的         Security 自动做的
                                    ──────         ────────────────
  JWT 过滤器 拦截请求                  ✓
  └─ 提取 Token                                     ✗（不认识 JWT）
  └─ 验签                              ✓             ✗
  └─ 解析用户信息                       ✓             ✗
  └─ 设 SecurityContext                ✓             ✓（提供了 ThreadLocal 盒子）

  URL 权限拦截                                          ✓（FilterSecurityInterceptor）
  └─ 哪些 URL 放行 / 需要登录            ✓              ✗
  └─ 哪些 URL 需要什么权限               ✓              ✗
  └─ 拿当前 URL 查规则                                   ✓
  └─ 拿用户权限                                          ✓
  └─ 比对 + 放行/403                                     ✓

  方法级 @PreAuthorize                 ✓（写注解）      ✓（解析注解、执行校验）

  认证失败 → 401                                        ✓（ExceptionTranslationFilter）
  └─ 返回什么格式                       ✓               ✗（默认 HTML，你是 JSON）

  授权失败 → 403                                        ✓

  查数据库验证用户名密码                 ✓               ✗
  └─ 比密码                             ✗               ✓（BCryptPasswordEncoder）
  └─ 串起查用户 + 比密码                                  ✓（AuthenticationManager）
```

## 六、hm-admin 和 hm-token-demo 用了什么，没用什么

| Spring Security 能力 | hm-admin 后台管理 | hm-token-demo 双 Token |
|----------------------|:---:|:---:|
| Filter Chain | ✓ | ✓ |
| Basic Auth 表单登录 | ✗（自己写 JWT 登录） | ✗（自己写登录） |
| RSESSLESS Session | ✓ | ✓ |
| UserDetailsService | ✓ | ✗（yml 内存用户） |
| PasswordEncoder(BCrypt) | ✓ | ✓ |
| authorizeRequests URL 规则 | ✓（动态 DB 加载） | ✓（3 个 URL 写死） |
| hasAuthority / hasAnyAuthority | ✓ | ✗（只判断登录/未登录） |
| @EnableGlobalMethodSecurity + @PreAuthorize | ✓ | ✗ |
| AuthenticationEntryPoint（401JSON） | ✓ | ✓ |
| CSRF | ✗ 关闭 | ✗ 关闭 |
| LogoutFilter | ✗ | ✗ |
| Remember Me | ✗ | ✗ |

## 七、为什么要引 Spring Security 而不是自己写 Filter

1. **面试必考**：面试官不问"你怎么用 Filter 做鉴权"，他问"Spring Security 怎么做认证和授权"
2. **已经是轻量用法**：你只用了 Filter Chain + Context Holder + URL 规则，没用表单登录、Session、RememberMe
3. **扩展成本低**：从"只判断登录"升级到 RBAC 只需加 `@EnableGlobalMethodSecurity` + 一张权限表，不用重写过滤器链
4. **生态**：和 Spring Boot Actuator、Spring Session、OAuth2 无缝对接，未来要扩展不换技术栈

如果完全不引 Spring Security，自己写 Filter 也就 100 行——但要加 RBAC、方法级注解、Session 管理，最终等于自造一个 Spring Security。

## 八、面试话术

**面试官问"Spring Security 的原理是什么？"**

> 本质是一条过滤器链，插在 HTTP 请求和 Controller 之间。15 个内置过滤器按序执行——有的管 HTTPS 强制跳转，有的管 SecurityContext 持久化，核心是 FilterSecurityInterceptor 做 URL 权限匹配。我做的事是在这链里插入一个 JWT 过滤器——OncePerRequestFilter，从 Header 提取 Token 验签，把用户信息设进 SecurityContextHolder。后面的 FilterSecurityInterceptor 拿 URL 规则和我设的权限做比对，决定放行还是 403。

**面试官问"Spring Security 哪些是你做的，哪些是它自己做的？"**

> 认证部分我做了 Token 提取和验签，它提供了 SecurityContextHolder 存用户。授权部分我做了规则定义——哪些 URL 要什么权限——它执行拦截和比对。登录认证我写了 UserDetailsService 查数据库，它用 AuthenticationManager 串起查用户和比密码。总结就是：规则定义是我的，执行层面是它的。

**面试官问"SecurityContextHolder 是什么，为什么用 ThreadLocal？"**

> 存当前请求的用户信息。一次 HTTP 请求从进 Filter 到出 Controller 都在一个线程里，ThreadLocal 保证同一线程内任何地方都能拿到当前用户，不同请求之间互不干扰。请求结束后 Filter 链末尾自动清理，不会内存泄露。

**面试官问"CSRF 是什么，你们的项目为什么关了？"**

> 跨站请求伪造——你登录了银行网站，Cookie 里有登录态，黑客诱导你点链接，借你的 Cookie 冒充你发起转账。防御靠 CSRF Token——服务端发一个随机 Token 给前端，前端每次请求带回来，攻击者伪造不了。我们关了是因为前后端分离 + JWT——Token 放在 Header 里不靠 Cookie，浏览器不会自动携带，攻击者伪造不了请求头里的 Authorization，CSRF 天然免疫。

**面试官问"你的 JWT 过滤器为什么继承 OncePerRequestFilter？"**

> 保证每个请求只过一次 JWT 验签。普通 Filter 可能因为请求转发（forward/include）被多次触发。OncePerRequestFilter 有去重判断——同一个请求过了就不再过第二次。JWT 验签本质上是"认人"，认一次就够了，重复执行浪费性能。

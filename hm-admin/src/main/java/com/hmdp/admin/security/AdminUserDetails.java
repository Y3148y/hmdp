package com.hmdp.admin.security;

import com.hmdp.admin.entity.AdminUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 员工档案袋。
 *
 * <h3>一句话</h3>
 * 实现了 Spring Security 的 UserDetails 接口——把数据库查出来的 AdminUser 包装成 Security 认识的标准员工档案格式。
 *
 * <h3>UserDetails 接口要求实现什么</h3>
 * Spring Security 不关心你的 AdminUser 类长什么样，它只认 UserDetails 接口。
 * 所以需要这个适配器把 AdminUser → Security 的标准格式。
 *
 * <h3>接口方法（必须实现的）</h3>
 * <pre>
 * getUsername()           → 用户名（Security 用它来识别"你是谁"）
 * getPassword()           → 加密后的密码（Security 用它和登录输入的密码比对）
 * getAuthorities()        → 权限列表（角色 + 具体权限混在一起，Security 鉴权时用）
 * isAccountNonExpired()   → 账号过期没？（我们都返回 true = 永不过期）
 * isAccountNonLocked()    → 账号被锁没？（true = 没锁）
 * isCredentialsNonExpired() → 密码过期没？（true = 没过期）
 * isEnabled()             → 账号启用没？（从数据库 enabled 字段读）
 * </pre>
 */
@Getter  // Lombok 自动生成 getId()、getUsername()、getPassword()、isEnabled() 等方法
public class AdminUserDetails implements UserDetails {

    // ==================== 用户基本属性 ====================

    /** 用户ID（数据库主键），不是 Security 要求的，但业务代码可能需要 */
    private final Long id;

    /** 用户名，UserDetails 接口要求必须提供 */
    private final String username;

    /** 加密后的密码（BCrypt 哈希），UserDetails 接口要求必须提供 */
    private final String password;

    /** 账号是否启用（0=禁用, 1=启用），true = 能登录 */
    private final boolean enabled;

    // ==================== 权限属性 ====================

    /**
     * 权限列表：角色 + 具体权限混在一起。
     * 例如 [ROLE_SUPER_ADMIN, user:list, user:create, role:manage, perm:manage]
     * Security 鉴权时从这里面查——看用户有没有访问某个 URL 需要的权限。
     */
    private final Collection<? extends GrantedAuthority> authorities;

    // ==================== 构造函数 ====================

    /**
     * 从数据库实体 AdminUser 构建 Security 的标准用户对象。
     *
     * <h3>做了什么</h3>
     * <ol>
     *   <li>复制基本字段：id、username、password、enabled</li>
     *   <li>遍历用户关联的角色，把角色 code（如 ROLE_SUPER_ADMIN）加进权限列表</li>
     *   <li>遍历用户关联的权限，把权限 code（如 user:list）加进权限列表</li>
     * </ol>
     *
     * <h3>SimpleGrantedAuthority 是什么</h3>
     * Spring Security 里权限的标准表示——就是一个字符串。
     * 角色存 "ROLE_SUPER_ADMIN"，权限存 "user:list"。
     * Security 鉴权时用字符串匹配。
     *
     * @param user 数据库查出来的 AdminUser（必须包含 roles 和 permissions 集合）
     */
    public AdminUserDetails(AdminUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        // enabled: 数据库字段可能为 null，null 当 false（禁用）处理
        this.enabled = user.getEnabled() != null && user.getEnabled();

        // 构建权限列表
        List<GrantedAuthority> authList = new ArrayList<>();

        // 把角色加进去（角色编码如 ROLE_SUPER_ADMIN）
        if (user.getRoles() != null) {
            user.getRoles().forEach(role ->
                    authList.add(new SimpleGrantedAuthority(role.getCode())));
        }

        // 把具体权限加进去（权限编码如 user:list）
        if (user.getPermissions() != null) {
            user.getPermissions().forEach(perm ->
                    authList.add(new SimpleGrantedAuthority(perm.getCode())));
        }

        this.authorities = authList;
    }

    // ==================== 账号状态方法（简化版全部返回 true） ====================

    /** 账号是否未过期。我们没做这个功能，永远返回 true。 */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /** 账号是否未被锁定。我们没做这个功能，永远返回 true。 */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /** 密码是否未过期。我们没做这个功能，永远返回 true。 */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
}

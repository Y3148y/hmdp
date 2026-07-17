-- hm-admin RBAC 初始化 DDL + 种子数据
-- 数据库: hmdp_admin

CREATE DATABASE IF NOT EXISTS hmdp_admin DEFAULT CHARACTER SET utf8mb4;
USE hmdp_admin;

-- ==================== 用户表 ====================
CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(200) NOT NULL COMMENT 'BCrypt 加密密码',
    nickname VARCHAR(50) DEFAULT '' COMMENT '昵称',
    email VARCHAR(100) DEFAULT '' COMMENT '邮箱',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '管理员用户';

-- ==================== 角色表 ====================
CREATE TABLE IF NOT EXISTS admin_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL COMMENT '角色名称',
    code VARCHAR(50) NOT NULL UNIQUE COMMENT '角色编码，如 ROLE_ADMIN'
) COMMENT '角色';

-- ==================== 权限表 ====================
CREATE TABLE IF NOT EXISTS admin_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL COMMENT '权限名称，如 用户列表',
    code VARCHAR(50) NOT NULL UNIQUE COMMENT '权限编码，如 user:list',
    url VARCHAR(200) DEFAULT NULL COMMENT '受保护的 URL 路径',
    description VARCHAR(200) DEFAULT '' COMMENT '权限描述'
) COMMENT '权限';

-- ==================== 用户-角色 中间表 ====================
CREATE TABLE IF NOT EXISTS admin_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
) COMMENT '用户角色关联';

-- ==================== 角色-权限 中间表 ====================
CREATE TABLE IF NOT EXISTS admin_role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
) COMMENT '角色权限关联';

-- ==================== 种子数据 ====================

-- 角色
INSERT INTO admin_role (name, code) VALUES
('超级管理员', 'ROLE_SUPER_ADMIN'),
('普通管理员', 'ROLE_ADMIN');

-- 权限
INSERT INTO admin_permission (name, code, url, description) VALUES
('用户管理', 'user:list', '/admin/users', '查看用户列表'),
('用户管理', 'user:create', '/admin/users', '创建用户'),
('用户管理', 'user:update', '/admin/users', '修改用户'),
('用户管理', 'user:delete', '/admin/users', '删除用户'),
('角色管理', 'role:manage', '/admin/roles', '角色 CRUD'),
('权限管理', 'perm:manage', '/admin/permissions', '权限 CRUD');

-- 管理员用户（BCrypt 加密密码）
INSERT INTO admin_user (username, password, nickname, enabled) VALUES
('admin', '$2a$10$MzZ1VTBNz.NLASFZbupzv.hVWAenUgxLFdnhw8OLwoHK0hSiVxtHy', '超级管理员', 1),
('user',  '$2a$10$2JqS0muRiZttjqALXmpBJOJBK9MZ7Xo2.TDf36M4iWIVgep5vtuMe', '普通管理员', 1);
-- 明文: admin123 / user123

-- 超级管理员(uid=1): 拥有所有权限
INSERT INTO admin_user_role (user_id, role_id) VALUES (1, 1);
INSERT INTO admin_role_permission (role_id, permission_id)
SELECT 1, id FROM admin_permission;

-- 普通管理员(uid=2): 只有用户查看权限
INSERT INTO admin_user_role (user_id, role_id) VALUES (2, 2);
INSERT INTO admin_role_permission (role_id, permission_id)
SELECT 2, id FROM admin_permission WHERE code IN ('user:list');

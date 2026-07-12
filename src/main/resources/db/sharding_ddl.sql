-- ============================================================
-- Sharding DDL：tb_voucher_order 水平分表（按 id % 8 分 8 张表）
-- 执行方式：先执行此 DDL 创建分表，再重启应用使 Sharding-JDBC 生效
-- ============================================================

-- 1. 创建 8 张分表（结构完全一致，从原表复制）
CREATE TABLE IF NOT EXISTS `tb_voucher_order_0` LIKE `tb_voucher_order`;
CREATE TABLE IF NOT EXISTS `tb_voucher_order_1` LIKE `tb_voucher_order`;
CREATE TABLE IF NOT EXISTS `tb_voucher_order_2` LIKE `tb_voucher_order`;
CREATE TABLE IF NOT EXISTS `tb_voucher_order_3` LIKE `tb_voucher_order`;
CREATE TABLE IF NOT EXISTS `tb_voucher_order_4` LIKE `tb_voucher_order`;
CREATE TABLE IF NOT EXISTS `tb_voucher_order_5` LIKE `tb_voucher_order`;
CREATE TABLE IF NOT EXISTS `tb_voucher_order_6` LIKE `tb_voucher_order`;
CREATE TABLE IF NOT EXISTS `tb_voucher_order_7` LIKE `tb_voucher_order`;

-- 2. 慢SQL优化 —— 联合索引
-- 原因：一人一单去重查询 SELECT COUNT(*) WHERE user_id=? AND voucher_id=?
--       Sharding-JDBC 广播到所有 8 张表，无索引时每表全表扫描
--       加 idx_user_voucher 联合索引后，每表走索引覆盖，扫描行数从全表 → 1 行

ALTER TABLE `tb_voucher_order_0` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
ALTER TABLE `tb_voucher_order_1` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
ALTER TABLE `tb_voucher_order_2` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
ALTER TABLE `tb_voucher_order_3` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
ALTER TABLE `tb_voucher_order_4` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
ALTER TABLE `tb_voucher_order_5` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
ALTER TABLE `tb_voucher_order_6` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);
ALTER TABLE `tb_voucher_order_7` ADD INDEX `idx_user_voucher` (`user_id`, `voucher_id`);

-- 3. 用户订单列表索引优化
-- 原因：SELECT * FROM tb_voucher_order WHERE user_id=? ORDER BY create_time DESC LIMIT ?,?
--       Sharding-JDBC 广播到所有 8 张表后汇总排序，无索引时每表全表扫描+filesort
--       加 idx_user_create_time 联合索引后，每表走索引范围扫描，无需 filesort

ALTER TABLE `tb_voucher_order_0` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);
ALTER TABLE `tb_voucher_order_1` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);
ALTER TABLE `tb_voucher_order_2` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);
ALTER TABLE `tb_voucher_order_3` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);
ALTER TABLE `tb_voucher_order_4` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);
ALTER TABLE `tb_voucher_order_5` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);
ALTER TABLE `tb_voucher_order_6` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);
ALTER TABLE `tb_voucher_order_7` ADD INDEX `idx_user_create_time` (`user_id`, `create_time`);

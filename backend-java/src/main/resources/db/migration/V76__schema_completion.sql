-- ============================================================
--  V76__schema_completion.sql
--  补齐全新库缺失列 —— 实体 schema 与迁移全量比对（2026-10-30 实测）后仅缺 2 列。
--
--  背景：主库的这两列由早期 ddl-auto=update 自动建表遗留，迁移从未创建，
--  导致全新环境 Hibernate validate 必失败：
--    - external_data_source.driver_class_name（实体 ExternalDataSourceEntity）
--    - users.email（实体 UserEntity）
--
--  静态比对方法：解析全部 JPA 实体 @Table/@Column 与 information_schema 全量列，
--  差异 = external_data_source.driver_class_name + users.email（见交付报告）。
--
--  已有库已含这两列 → IF NOT EXISTS 跳过，零影响。
-- ============================================================

ALTER TABLE external_data_source ADD COLUMN IF NOT EXISTS driver_class_name VARCHAR(128);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255);

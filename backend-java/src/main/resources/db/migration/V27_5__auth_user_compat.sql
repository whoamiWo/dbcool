-- ============================================================
--  V27_5__auth_user_compat.sql
--  兼容遗留表 auth_user —— 修复"全新部署必失败"问题。
--
--  背景（Wave4 离线包实测复现，2026-10-30）：
--    V28__platform_enhancement.sql:110 的 auth_mapping.local_user_id
--    外键引用 auth_user(id)，但 auth_user 从未由任何 Flyway 迁移创建 ——
--    它是项目早期在主库手动创建的遗留表（V54/V55 已把 external_user_mapping
--    指向 users 并注明"auth_user 是遗留表"）。主库因遗留表存在而通过，
--    全新环境（含离线包部署）执行到 V28 必然失败：
--      ERROR: relation "auth_user" does not exist
--
--  修复：在 V27 与 V28 之间插入本迁移创建最小兼容表（仅满足外键所需列）。
--    - IF NOT EXISTS：主库已有遗留表时零影响（结构可能不同，但外键仅需 id UUID）；
--    - 真实用户表始终是 users（V1），auth_user 不承载业务数据。
--
--  版本号 27.5：Flyway 版本按点分段数字比较，27 < 27.5 < 28。
-- ============================================================

CREATE TABLE IF NOT EXISTS auth_user (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

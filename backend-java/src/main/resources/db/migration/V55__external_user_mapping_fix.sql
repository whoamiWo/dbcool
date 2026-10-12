-- ============================================================
-- PHASE96 B0/B1: 修复 V54 的存量问题
--
-- 1. local_user_id 外键指向 auth_user → users（auth_user 是遗留表，实际为 users）
-- 2. metadata JSONB → TEXT（Hibernate 6 + H2Dialect 识别 jsonb 失败）
--
-- 注：V54 首次建表时 auth_user 已是遗留表（Flyway 基线之外），
-- 且库中 ldap_user_mapping 已指向 users(id)。
-- ============================================================
-- 外键指向修复：删掉指向 auth_user 的旧外键，改为指向 users
ALTER TABLE external_user_mapping
    DROP CONSTRAINT IF EXISTS external_user_mapping_local_user_id_fkey;
ALTER TABLE external_user_mapping
    ADD CONSTRAINT external_user_mapping_local_user_id_fkey
    FOREIGN KEY (local_user_id) REFERENCES users(id) ON DELETE CASCADE;

-- 列类型修复：Hibernate 6 + H2Dialect 识别 jsonb 失败
ALTER TABLE external_user_mapping ALTER COLUMN metadata TYPE TEXT;

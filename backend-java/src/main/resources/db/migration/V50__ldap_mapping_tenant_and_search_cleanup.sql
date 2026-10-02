-- Phase69 R1: 修复迁移 P0 问题 - 新增租户字段
-- 说明：此迁移承载原 V28/V41 的增量修改，幂等

-- 1. 为 ldap_user_mapping 添加 tenant_id 列（若不存在）
ALTER TABLE ldap_user_mapping ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

-- 2. 创建租户索引
CREATE INDEX IF NOT EXISTS idx_ldap_user_mapping_tenant ON ldap_user_mapping(tenant_id);

-- 3. 清理旧的搜索触发器（若存在）
DROP TRIGGER IF EXISTS trg_update_search_vector ON unified_search_index;

-- 4. 为 workflows 添加 last_triggered_at 列（若不存在）
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS last_triggered_at TIMESTAMPTZ;
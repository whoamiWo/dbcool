-- ============================================================
--  V75__collection_version.sql
--  建表 collection_version —— 补齐"全新库 Hibernate validate 必失败"问题。
--
--  背景（Wave4 离线包实测复现，2026-10-30）：
--    V1..V74 全部迁移通过后，后端启动仍报
--      Schema-validation: missing table [collection_version]
--    实体 meta/CollectionVersionEntity 需要该表，但所有迁移从未建过 ——
--    主库靠早期 ddl-auto=update 自动建表遗留而可用，全新环境直接启动失败。
--
--  结构与实体字段一一对应（id BIGSERIAL / collection_id UUID / version INT /
--  fields_json JSONB / change_desc VARCHAR(500) / tenant_id VARCHAR(64) /
--  created_at TIMESTAMPTZ / created_by UUID）。
--
--  已有主库如已自动建表 → IF NOT EXISTS 跳过，零影响。
-- ============================================================

CREATE TABLE IF NOT EXISTS collection_version (
    id BIGSERIAL PRIMARY KEY,
    collection_id UUID NOT NULL,
    version INT NOT NULL,
    fields_json JSONB NOT NULL,
    change_desc VARCHAR(500),
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID
);

CREATE INDEX IF NOT EXISTS idx_collection_version_collection ON collection_version(collection_id);
CREATE INDEX IF NOT EXISTS idx_collection_version_tenant ON collection_version(tenant_id);

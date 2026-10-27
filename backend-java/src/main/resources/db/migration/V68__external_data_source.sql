-- ============================================================
--  V68__external_data_source.sql
--  PHASE B Wave 2 B3: 外部数据源（企业已有 MySQL/PostgreSQL 库）
--
--  设计决策：同步式映射（拉取快照生成平台内 Collection），而非实时直连。
--  理由：企业目标库多在内网，容器直连有网络与安全风险；快照让数据享受
--  平台既有 ACL/软删除/BI 聚合能力，且故障隔离。
--
--  external_data_source：连接配置（host/port/db/username/password_enc），
--  密码用 CredentialCryptoService(AES-GCM) 加密，明文永不落库。
-- ============================================================

CREATE TABLE IF NOT EXISTS external_data_source (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    -- vendor: mysql / postgresql（驱动反射加载；mysql 驱动为可选 runtime 依赖）
    vendor VARCHAR(32) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    username VARCHAR(128) NOT NULL,
    -- AES-GCM 密文（Base64(IV || ciphertext || tag)），明文永不落库
    password_enc TEXT NOT NULL,
    -- 状态：ACTIVE / DISABLED
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    CONSTRAINT uq_external_data_source_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_external_data_source_tenant ON external_data_source(tenant_id);
CREATE INDEX IF NOT EXISTS idx_external_data_source_vendor ON external_data_source(vendor);

-- 物理表（schema）级：外部映射的 Collection 落 collection_meta 的
-- 可选列。外部表名与数据源 ID 联合标识来源，nullable 不影响存量 Collection。
ALTER TABLE collection_meta ADD COLUMN IF NOT EXISTS datasource_id UUID;
ALTER TABLE collection_meta ADD COLUMN IF NOT EXISTS external_table_name VARCHAR(255);
ALTER TABLE collection_meta ADD COLUMN IF NOT EXISTS read_only BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_collection_meta_datasource ON collection_meta(datasource_id);

COMMENT ON TABLE external_data_source IS 'PHASE B B3: 外部数据库连接配置（密码 AES-GCM 加密）';
COMMENT ON COLUMN collection_meta.datasource_id IS 'PHASE B B3: 外部数据源 ID；非空表示该 Collection 由外部库映射而来';
COMMENT ON COLUMN collection_meta.external_table_name IS 'PHASE B B3: 外部库中的物理表名';
COMMENT ON COLUMN collection_meta.read_only IS 'PHASE B B3: 外部映射 Collection 默认只读（快照同步只读）';

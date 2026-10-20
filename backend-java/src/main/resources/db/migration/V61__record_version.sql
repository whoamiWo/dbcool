-- ============================================================
--  V61__record_version.sql
--  业务记录版本历史 —— 软删除回收站配套(PHASE125)
--
--  设计要点:
--  - record_version 记录 Collection 动态表(data_<collection>)中每条记录
--    的变更快照与字段级 diff,支持回收站恢复与版本回滚
--  - 带 tenant_id,与 ADR-007 多租户隔离一致(动态表本身按 schema 隔离,
--    本表为跨租户共享的元数据表,故必须显式带租户列)
--  - data_json / changed_fields 用 TEXT 而非 JSONB:版本快照只存读展示,
--    不需要 JSONB 查询能力;且 H2 测试环境 H2Dialect 不识别 jsonb(见 V54 注释)
--  - UNIQUE(tenant_id, collection_name, record_id, version) 防并发重复版本号
-- ============================================================

CREATE TABLE IF NOT EXISTS record_version (
    id              UUID         PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    collection_name VARCHAR(128) NOT NULL,
    record_id       UUID         NOT NULL,
    version         INT          NOT NULL,
    data_json       TEXT         NOT NULL,
    changed_fields  TEXT,
    operation       VARCHAR(16),
    changed_by      UUID,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_recver_unique
  ON record_version(tenant_id, collection_name, record_id, version);

CREATE INDEX IF NOT EXISTS idx_recver_lookup
  ON record_version(tenant_id, collection_name, record_id, version DESC);

COMMENT ON COLUMN record_version.changed_fields IS '字段级变更差异 JSON:[{field,oldValue,newValue}]';
COMMENT ON COLUMN record_version.operation IS 'INSERT / UPDATE / DELETE / RESTORE';

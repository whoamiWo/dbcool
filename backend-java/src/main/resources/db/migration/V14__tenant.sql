-- ============================================================
--  V14__tenant.sql
--  Week 41 D6: 租户表(ADR-007 多租户元数据)
--
--  背景:TenantEntity 已存在(@Table name = tenant),但缺 Flyway DDL。
--  在 application.yml 的 ddl-auto=validate 下,应用启动会抛
--  Schema-validation: missing table [tenant],属生产阻断,故补此迁移。
--
--  字段严格对齐 TenantEntity(TenantEntity.java:32-52):
--  六列均 NOT NULL,slug 唯一。
--
--  注:Schema 路由(SET search_path)推迟到 D6 Step G2,
--     当前 schema_name 仅存储、不参与 SQL,隔离仍靠 tenant_id 列。
-- ============================================================

CREATE TABLE tenant (
    id          VARCHAR(64)  PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    slug        VARCHAR(64)  NOT NULL UNIQUE,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    schema_name VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_status ON tenant(status);

-- 默认租户:兼容 Week 1-40 已存在的单租户数据。
-- V1__init.sql seed 的 admin 用户 tenant_id 为 tenant_default,
-- 因此此处必须存在同 id 的租户,否则租户查询语义不一致。
-- ON CONFLICT DO NOTHING 保证重复执行幂等。
INSERT INTO tenant (id, name, slug, status, schema_name, created_at)
VALUES ('tenant_default', '默认租户', 'default', 'ACTIVE', 'tenant_default', NOW())
ON CONFLICT (id) DO NOTHING;

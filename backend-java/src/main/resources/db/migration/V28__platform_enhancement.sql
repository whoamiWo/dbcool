-- V28__platform_enhancement.sql
-- 企业综合平台增强: Huddle 语音/自动化规则/LDAP 同步/统一搜索

-- ============================================================
-- 1. Huddle 语音会话表
-- ============================================================
CREATE TABLE IF NOT EXISTS im_huddle (
    id UUID PRIMARY KEY,
    channel_id UUID NOT NULL REFERENCES im_channel(id) ON DELETE CASCADE,
    name VARCHAR(128),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, ACTIVE, ENDED
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    tenant_id VARCHAR(64) NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_im_huddle_channel ON im_huddle(channel_id);
CREATE INDEX IF NOT EXISTS idx_im_huddle_status ON im_huddle(status);
CREATE INDEX IF NOT EXISTS idx_im_huddle_tenant ON im_huddle(tenant_id);

-- Huddle 参与者表
CREATE TABLE IF NOT EXISTS im_huddle_participant (
    id UUID PRIMARY KEY,
    huddle_id UUID NOT NULL REFERENCES im_huddle(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    is_muted BOOLEAN DEFAULT false,
    is_screen_sharing BOOLEAN DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_im_huddle_participant_huddle ON im_huddle_participant(huddle_id);
CREATE INDEX IF NOT EXISTS idx_im_huddle_participant_user ON im_huddle_participant(user_id);

-- ============================================================
-- 2. 自动化规则表
-- ============================================================
CREATE TABLE IF NOT EXISTS automation_rule (
    id UUID PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    collection_name VARCHAR(128) NOT NULL,
    trigger_type VARCHAR(50) NOT NULL,  -- RECORD_CREATE, RECORD_UPDATE, RECORD_DELETE, SCHEDULED
    trigger_config JSONB NOT NULL DEFAULT '{}',
    conditions_json JSONB DEFAULT '[]'::jsonb,
    actions_json JSONB DEFAULT '[]'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT true,
    execution_count INTEGER NOT NULL DEFAULT 0,
    last_execution_at TIMESTAMPTZ,
    tenant_id VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_automation_rule_collection ON automation_rule(collection_name);
CREATE INDEX IF NOT EXISTS idx_automation_rule_tenant ON automation_rule(tenant_id);
CREATE INDEX IF NOT EXISTS idx_automation_rule_enabled ON automation_rule(enabled);

-- 自动化执行历史
CREATE TABLE IF NOT EXISTS automation_execution (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES automation_rule(id) ON DELETE CASCADE,
    record_id VARCHAR(256),
    status VARCHAR(20) NOT NULL,  -- SUCCESS, FAILED, SKIPPED
    error_message TEXT,
    execution_time_ms BIGINT,
    result_data JSONB,
    triggered_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_automation_execution_rule ON automation_execution(rule_id);
CREATE INDEX IF NOT EXISTS idx_automation_execution_status ON automation_execution(status);

-- ============================================================
-- 3. LDAP 同步配置表
-- ============================================================
CREATE TABLE IF NOT EXISTS ldap_config (
    id UUID PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    server_url VARCHAR(512) NOT NULL,
    base_dn VARCHAR(512) NOT NULL,
    bind_dn VARCHAR(512),
    bind_password VARCHAR(512),
    user_search_filter VARCHAR(512) NOT NULL DEFAULT '(objectClass=person)',
    group_search_filter VARCHAR(512) NOT NULL DEFAULT '(objectClass=group)',
    attribute_mapping JSONB NOT NULL DEFAULT '{}',
    sync_interval_minutes INTEGER NOT NULL DEFAULT 60,
    last_sync_at TIMESTAMPTZ,
    last_sync_status VARCHAR(20),
    last_sync_error TEXT,
    tenant_id VARCHAR(64) NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ldap_config_tenant ON ldap_config(tenant_id);

-- LDAP 用户映射表
CREATE TABLE IF NOT EXISTS ldap_user_mapping (
    id UUID PRIMARY KEY,
    ldap_dn VARCHAR(512) NOT NULL,
    ldap_uid VARCHAR(256) NOT NULL,
    local_user_id UUID REFERENCES auth_user(id) ON DELETE CASCADE,
    sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, SYNCED, ERROR
    sync_error TEXT,
    last_synced_at TIMESTAMPTZ,
    ldap_attributes JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(ldap_uid, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_ldap_user_mapping_ldap_uid ON ldap_user_mapping(ldap_uid);
CREATE INDEX IF NOT EXISTS idx_ldap_user_mapping_local_user ON ldap_user_mapping(local_user_id);

-- ============================================================
-- 4. 统一搜索索引表
-- ============================================================
CREATE TABLE IF NOT EXISTS unified_search_index (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(64) NOT NULL,  -- message, wiki, task, record, etc.
    entity_id VARCHAR(256) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    title VARCHAR(512),
    content TEXT,
    content_tsv tsvector,
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(entity_type, entity_id, tenant_id)
);

-- FTS 索引
CREATE INDEX IF NOT EXISTS idx_unified_search_tsv ON unified_search_index USING gin(content_tsv);
CREATE INDEX IF NOT EXISTS idx_unified_search_tenant ON unified_search_index(tenant_id);
CREATE INDEX IF NOT EXISTS idx_unified_search_type ON unified_search_index(entity_type);

-- 搜索向量触发器函数
CREATE OR REPLACE FUNCTION update_search_vector()
RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.title, '') || ' ' || COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_update_search_vector
    BEFORE INSERT OR UPDATE ON unified_search_index
    FOR EACH ROW EXECUTE FUNCTION update_search_vector();

-- V27: Huddle 语音、自动化规则、LDAP 同步、统一搜索
-- 依赖: V23-V26 (im_enhance / wiki_notion / playbook / ai_agent)

-- ============================================================
-- 1. Huddle 语音信令
-- ============================================================
CREATE TABLE IF NOT EXISTS im_huddle (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id UUID NOT NULL REFERENCES im_channel(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / ENDED
    started_at TIMESTAMP NOT NULL DEFAULT now(),
    ended_at TIMESTAMP,
    started_by UUID NOT NULL,
    participants JSONB NOT NULL DEFAULT '[]',
    CONSTRAINT im_huddle_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

CREATE INDEX IF NOT EXISTS idx_im_huddle_channel ON im_huddle(channel_id);
CREATE INDEX IF NOT EXISTS idx_im_huddle_tenant ON im_huddle(tenant_id);

-- Huddle 信令消息 (WebRTC offer/answer/candidate)
CREATE TABLE IF NOT EXISTS im_huddle_signaling (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    huddle_id UUID NOT NULL REFERENCES im_huddle(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL,
    signal_type VARCHAR(20) NOT NULL,  -- OFFER / ANSWER / CANDIDATE / LEAVE
    target_id UUID,  -- 目标用户(点对点信令)
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_im_huddle_signaling_huddle ON im_huddle_signaling(huddle_id);

-- ============================================================
-- 2. 自动化规则引擎
-- ============================================================
CREATE TABLE IF NOT EXISTS automation_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    collection_name VARCHAR(128),
    trigger_type VARCHAR(50) NOT NULL,  -- RECORD_CREATE / RECORD_UPDATE / RECORD_DELETE / SCHEDULED
    trigger_config JSONB NOT NULL DEFAULT '{}',
    conditions_json JSONB NOT NULL DEFAULT '[]',
    actions_json JSONB NOT NULL DEFAULT '[]',
    enabled BOOLEAN NOT NULL DEFAULT true,
    execution_count INTEGER NOT NULL DEFAULT 0,
    last_execution_at TIMESTAMP,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_automation_rule_tenant ON automation_rule(tenant_id);

CREATE TABLE IF NOT EXISTS automation_execution (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id UUID NOT NULL REFERENCES automation_rule(id) ON DELETE CASCADE,
    record_id VARCHAR(256),
    status VARCHAR(20) NOT NULL,  -- SUCCESS / FAILED / SKIPPED
    error_message TEXT,
    execution_time_ms BIGINT,
    result_data_json JSONB,
    triggered_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_automation_execution_rule ON automation_execution(rule_id);

-- ============================================================
-- 3. LDAP/AD 同步
-- ============================================================
CREATE TABLE IF NOT EXISTS ldap_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    server_url VARCHAR(512) NOT NULL,
    base_dn VARCHAR(512) NOT NULL,
    bind_dn VARCHAR(512),
    bind_password VARCHAR(512),
    user_search_filter VARCHAR(512) NOT NULL DEFAULT '(objectClass=person)',
    group_search_filter VARCHAR(512) NOT NULL DEFAULT '(objectClass=group)',
    attribute_mapping_json JSONB NOT NULL DEFAULT '{}',
    sync_interval_minutes INTEGER NOT NULL DEFAULT 60,
    last_sync_at TIMESTAMP,
    last_sync_status VARCHAR(20),
    last_sync_error TEXT,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ldap_config_tenant ON ldap_config(tenant_id);

CREATE TABLE IF NOT EXISTS ldap_user_mapping (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    ldap_dn VARCHAR(512) NOT NULL,
    ldap_uid VARCHAR(256) NOT NULL,
    local_user_id UUID,
    sync_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING / SYNCED / ERROR
    sync_error TEXT,
    last_synced_at TIMESTAMP,
    ldap_attributes_json JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ldap_user_mapping_tenant ON ldap_user_mapping(tenant_id);
CREATE INDEX IF NOT EXISTS idx_ldap_user_mapping_uid ON ldap_user_mapping(ldap_uid);

-- ============================================================
-- 4. 统一搜索索引
-- ============================================================
CREATE TABLE IF NOT EXISTS unified_search_index (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,  -- message / wiki / record / automation / task
    entity_id VARCHAR(256) NOT NULL,
    title VARCHAR(512),
    content TEXT,
    metadata_json JSONB NOT NULL DEFAULT '{}',
    search_vector tsvector,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(tenant_id, entity_type, entity_id)
);

CREATE INDEX IF NOT EXISTS idx_unified_search_tenant ON unified_search_index(tenant_id);
CREATE INDEX IF NOT EXISTS idx_unified_search_entity ON unified_search_index(entity_type, entity_id);

-- FTS 索引
CREATE INDEX IF NOT EXISTS idx_unified_search_vector ON unified_search_index USING GIN(search_vector);

-- 触发器: 自动更新 search_vector
CREATE OR REPLACE FUNCTION unified_search_update_vector()
RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector := to_tsquery('english',
        coalesce(NEW.title, '') || ' ' || coalesce(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trig_unified_search_vector ON unified_search_index;
CREATE TRIGGER trig_unified_search_vector
BEFORE INSERT OR UPDATE ON unified_search_index
FOR EACH ROW EXECUTE FUNCTION unified_search_update_vector();
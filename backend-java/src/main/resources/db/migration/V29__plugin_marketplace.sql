-- V28: 插件市场应用注册
CREATE TABLE IF NOT EXISTS plugin_marketplace_app (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL,
    app_key VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(256) NOT NULL,
    version VARCHAR(32) NOT NULL,
    latest_version VARCHAR(32),
    description TEXT,
    author VARCHAR(128),
    icon_url VARCHAR(512),
    homepage_url VARCHAR(512),
    category VARCHAR(64),
    tags_json JSONB NOT NULL DEFAULT '[]',
    permissions_json JSONB NOT NULL DEFAULT '[]',
    manifest_yaml TEXT,
    config_schema_json JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    install_error TEXT,
    config_json JSONB NOT NULL DEFAULT '{}',
    installed_by UUID,
    installed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_plugin_marketplace_tenant ON plugin_marketplace_app(tenant_id);
CREATE INDEX IF NOT EXISTS idx_plugin_marketplace_category ON plugin_marketplace_app(category);
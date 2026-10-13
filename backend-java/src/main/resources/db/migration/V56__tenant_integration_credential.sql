-- ============================================================
-- PHASE99 T1: 租户级集成凭证表
--
-- 背景：集成凭证此前全部走全局 @Value（dingtalk/feishu/slack/wecom 共 17 处读取点），
-- 多租户下所有租户共用同一套第三方应用凭证 —— 租户 A 的消息会走租户 B 的应用发出去，
-- 且租户无法自助配置自己的应用。
--
-- 本表让凭证可按 (tenant_id, provider, credential_key) 精确隔离存储。
-- 密文列 app_secret_enc 由 CredentialCryptoService 用 AES-GCM 加密，
-- 明文永不落库；主密钥来自环境变量，禁止硬编码。
--
-- 读取优先级（TenantCredentialService）：
--   1. 本表（tenant_id + provider + credential_key）
--   2. 回退全局 @Value（存量部署零破坏）
--   3. 两者都空 → Optional.empty()，调用方显式失败（不伪造）
-- ============================================================

CREATE TABLE IF NOT EXISTS tenant_integration_credential (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    -- provider: dingtalk / feishu / slack / wecom / mattermost
    provider VARCHAR(32) NOT NULL,
    -- credential_key: app-key / app-secret / app-id / client-id / client-secret /
    --                 signing-secret / bot-token / corp-id / agent-id / secret / ...
    credential_key VARCHAR(64) NOT NULL,
    -- AES-GCM 密文（Base64(IV || ciphertext || tag)），明文永不落库
    app_secret_enc TEXT NOT NULL,
    -- 状态：ACTIVE / DISABLED
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- 最近更新时间（轮换凭证时用于审计）
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by VARCHAR(64),
    -- 同一租户同一 provider 同一 key 只允许一条
    CONSTRAINT uq_tenant_provider_credential UNIQUE (tenant_id, provider, credential_key)
);

-- 按租户 + provider 查询（TenantCredentialService.get 的主路径）
CREATE INDEX IF NOT EXISTS idx_tenant_integration_credential_lookup
    ON tenant_integration_credential(tenant_id, provider, credential_key);

-- 按租户列出全部已配置 provider（管理界面/排查用）
CREATE INDEX IF NOT EXISTS idx_tenant_integration_credential_tenant
    ON tenant_integration_credential(tenant_id, provider);

-- V83 兼容升级：回调事件按 corpId + eventId 幂等；OIDC 只保存一次性 code 哈希。
ALTER TABLE approval_callback_event
    ADD COLUMN IF NOT EXISTS corp_id VARCHAR(128);

UPDATE approval_callback_event event
   SET corp_id = binding.corp_id
  FROM approval_external_binding binding
 WHERE binding.id = event.binding_id
   AND event.corp_id IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM approval_callback_event WHERE corp_id IS NULL) THEN
        RAISE EXCEPTION 'approval_callback_event 存在无法确定 corpId 的历史事件，请先对账';
    END IF;
END $$;

DO $$
DECLARE primary_key_name name;
BEGIN
    SELECT conname INTO primary_key_name FROM pg_constraint
    WHERE conrelid = 'approval_callback_event'::regclass AND contype = 'p';
    IF primary_key_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE approval_callback_event DROP CONSTRAINT %I', primary_key_name);
    END IF;
    ALTER TABLE approval_callback_event
        ADD CONSTRAINT pk_approval_callback_event PRIMARY KEY (corp_id, event_id);
END $$;

CREATE TABLE IF NOT EXISTS oidc_login_code (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    code_hash   VARCHAR(64) NOT NULL UNIQUE,
    user_id     UUID NOT NULL REFERENCES users(id),
    tenant_id   VARCHAR(64) NOT NULL,
    roles_json  JSONB NOT NULL DEFAULT '[]',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_oidc_login_code_expiry
    ON oidc_login_code (expires_at) WHERE consumed_at IS NULL;

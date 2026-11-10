-- 钉钉 OA 外推的精确绑定与回调幂等；历史审批不回填、不改写归属。
ALTER TABLE approval_template
    ADD COLUMN IF NOT EXISTS external_config JSONB;

ALTER TABLE approval_request
    ADD COLUMN IF NOT EXISTS corp_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS external_process_code VARCHAR(128),
    ADD COLUMN IF NOT EXISTS external_push_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN IF NOT EXISTS external_push_error VARCHAR(500);

ALTER TABLE approval_request
    DROP CONSTRAINT IF EXISTS ck_approval_request_external_push_status;
ALTER TABLE approval_request
    ADD CONSTRAINT ck_approval_request_external_push_status CHECK
        (external_push_status IN ('NOT_REQUIRED', 'PENDING', 'PUSHING', 'PUSHED', 'FAILED'));

CREATE TABLE IF NOT EXISTS approval_external_binding (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           VARCHAR(64) NOT NULL,
    request_id          UUID NOT NULL REFERENCES approval_request(id),
    corp_id             VARCHAR(128) NOT NULL,
    process_code        VARCHAR(128) NOT NULL,
    process_instance_id VARCHAR(128) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_approval_external_binding_corp_instance
        UNIQUE (corp_id, process_instance_id),
    CONSTRAINT uq_approval_external_binding_request
        UNIQUE (tenant_id, request_id)
);
CREATE INDEX IF NOT EXISTS idx_approval_external_binding_request
    ON approval_external_binding (tenant_id, request_id);

CREATE TABLE IF NOT EXISTS approval_callback_event (
    event_id             VARCHAR(128) PRIMARY KEY,
    binding_id           UUID NOT NULL REFERENCES approval_external_binding(id),
    tenant_id            VARCHAR(64) NOT NULL,
    process_instance_id  VARCHAR(128) NOT NULL,
    event_type           VARCHAR(32) NOT NULL DEFAULT 'finish',
    result               VARCHAR(32) NOT NULL,
    processing_status    VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    error                VARCHAR(500),
    worker_id            VARCHAR(128),
    claimed_at           TIMESTAMPTZ,
    received_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at         TIMESTAMPTZ,
    CONSTRAINT ck_approval_callback_event_status CHECK
        (processing_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_approval_callback_event_binding
    ON approval_callback_event (binding_id, processing_status);

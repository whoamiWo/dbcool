-- V36__mq_task_status.sql
-- PHASE 55 Stage 2: RabbitMQ 异步任务补偿状态表
-- 存储异步任务的发布/消费状态,便于补偿与审计

CREATE TABLE IF NOT EXISTS mq_task_status (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_mq_task_id UNIQUE (task_id, task_type)
);

COMMENT ON TABLE mq_task_status IS 'MQ 异步任务状态表 (V36)';
CREATE INDEX IF NOT EXISTS idx_mq_task_status_tenant ON mq_task_status(tenant_id);
CREATE INDEX IF NOT EXISTS idx_mq_task_status_status ON mq_task_status(status);
CREATE INDEX IF NOT EXISTS idx_mq_task_status_type ON mq_task_status(task_type);

-- V36 补充:死信队列告警表
CREATE TABLE IF NOT EXISTS mq_dead_letter_alert (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    payload JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE mq_dead_letter_alert IS 'MQ 死信告警表 (V36)';
CREATE INDEX IF NOT EXISTS idx_mq_dla_tenant ON mq_dead_letter_alert(tenant_id);
CREATE INDEX IF NOT EXISTS idx_mq_dla_type ON mq_dead_letter_alert(task_type);

-- B3 返工：外部数据源定时同步能力
-- 修复前只有 last_synced_at（一次性快照回填），无同步模式/cron/增量水位，
-- 无法支撑"定时全量/增量同步"。

ALTER TABLE external_data_source
    ADD COLUMN sync_mode    VARCHAR(16) DEFAULT 'MANUAL',
    ADD COLUMN sync_cron    VARCHAR(64),
    ADD COLUMN sync_enabled BOOLEAN DEFAULT FALSE,
    ADD COLUMN incremental_key    VARCHAR(128),
    ADD COLUMN last_sync_cursor   VARCHAR(255);

COMMENT ON COLUMN external_data_source.sync_mode IS '同步模式: MANUAL / FULL / INCREMENTAL';
COMMENT ON COLUMN external_data_source.sync_cron IS '定时同步 cron 表达式（sync_enabled=true 时生效）';
COMMENT ON COLUMN external_data_source.incremental_key IS '增量同步水位字段名（如 updated_at / id）';
COMMENT ON COLUMN external_data_source.last_sync_cursor IS '上次增量同步的水位值';

CREATE INDEX IF NOT EXISTS idx_external_ds_sync
    ON external_data_source (sync_enabled, sync_mode);

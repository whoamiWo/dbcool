-- B3 返工：外部数据源定时同步字段
-- 为 external_data_source 增加同步模式/定时/增量水位字段，
-- 使 integration/datasource 包内可承载真实的 @Scheduled 全量/增量同步。

ALTER TABLE external_data_source
    ADD COLUMN IF NOT EXISTS sync_mode VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS sync_cron VARCHAR(64),
    ADD COLUMN IF NOT EXISTS sync_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS incremental_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS last_sync_cursor VARCHAR(255);

COMMENT ON COLUMN external_data_source.sync_mode IS '同步模式: MANUAL/FULL/INCREMENTAL';
COMMENT ON COLUMN external_data_source.sync_enabled IS '是否启用定时同步';
COMMENT ON COLUMN external_data_source.incremental_key IS '增量同步水位字段名';
COMMENT ON COLUMN external_data_source.last_sync_cursor IS '上次增量同步水位值';

-- 加速定时同步扫描
CREATE INDEX IF NOT EXISTS idx_external_datasource_sync_enabled
    ON external_data_source (sync_enabled) WHERE sync_enabled;

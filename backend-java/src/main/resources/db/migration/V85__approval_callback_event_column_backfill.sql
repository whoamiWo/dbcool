-- 历史库兼容：approval_callback_event 可能由旧版本/半迁移创建，缺少后续列。
-- V83 的 CREATE TABLE IF NOT EXISTS 在表已存在时会整体跳过，无法补齐新列；
-- 这里以幂等方式补列，保证 V82 历史库升级到 V84 后 Hibernate schema 校验可通过。
ALTER TABLE approval_callback_event
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(32) NOT NULL DEFAULT 'finish';
ALTER TABLE approval_callback_event
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

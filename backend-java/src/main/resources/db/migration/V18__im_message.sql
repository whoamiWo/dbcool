-- ============================================================
--  V18__im_message.sql
--  统一实时消息总线 — 消息、表情回应、已读回执、附件
--
--  设计要点:
--  - im_message:parent_id 支持线程回复;软删除 deleted_at 保留线程完整性
--  - im_message_reaction:表情回应,唯一约束防止同一用户重复同一 emoji
--  - im_message_read:per-user 已读回执(区别于成员维度的 last_read 游标)
--  - im_attachment:消息附件,storage_key 指向 MinIO 对象
--  - 游标分页依赖 (channel_id, created_at) 复合索引
-- ============================================================

CREATE TABLE im_message (
    id           UUID         PRIMARY KEY,
    channel_id   UUID         NOT NULL,
    sender_id    UUID         NOT NULL,
    parent_id    UUID,                  -- 线程父消息;NULL 表示主消息
    content      TEXT         NOT NULL,
    content_type VARCHAR(16)  NOT NULL DEFAULT 'text',  -- 'text' | 'markdown'
    edited_at    TIMESTAMPTZ,
    deleted_at   TIMESTAMPTZ,           -- 软删除,线程子消息仍可展示
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    tenant_id    VARCHAR(64)  NOT NULL
);

CREATE INDEX idx_imessage_channel_created ON im_message(channel_id, created_at);
CREATE INDEX idx_imessage_parent ON im_message(parent_id);
CREATE INDEX idx_imessage_tenant_sender ON im_message(tenant_id, sender_id);

CREATE TABLE im_message_reaction (
    id         UUID        PRIMARY KEY,
    message_id UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    emoji      VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_id  VARCHAR(64) NOT NULL
);

CREATE UNIQUE INDEX idx_ireaction_unique ON im_message_reaction(message_id, user_id, emoji);
CREATE INDEX idx_ireaction_message ON im_message_reaction(message_id);

CREATE TABLE im_message_read (
    id         UUID        PRIMARY KEY,
    message_id UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    read_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_id  VARCHAR(64) NOT NULL
);

CREATE UNIQUE INDEX idx_iread_unique ON im_message_read(message_id, user_id);

CREATE TABLE im_attachment (
    id           UUID         PRIMARY KEY,
    message_id   UUID         NOT NULL,
    channel_id   UUID         NOT NULL,
    storage_key  VARCHAR(512) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(128),
    size_bytes   BIGINT,
    uploaded_by  UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    tenant_id    VARCHAR(64)  NOT NULL
);

CREATE INDEX idx_iattachment_message ON im_attachment(message_id);

-- ============================================================
--  V17__im_channel.sql
--  统一实时消息总线 — IM 频道与成员
--
--  设计要点:
--  - im_channel:频道,type 取 PUBLIC | PRIVATE | DIRECT
--  - im_channel_member:成员 + 角色 + 最后已读消息(未读计数依据)
--  - DIRECT 会话用 direct_key 去重(两个用户之间只允许一条直聊)
--  - 两张表均带 tenant_id,与 ADR-007 多租户隔离一致
-- ============================================================

CREATE TABLE im_channel (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(128),
    type        VARCHAR(16)  NOT NULL,  -- 'PUBLIC' | 'PRIVATE' | 'DIRECT'
    topic       VARCHAR(512),
    direct_key  VARCHAR(129),           -- 直聊去重键:'<小uuid>:<大uuid>'
    created_by  UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    archived_at TIMESTAMPTZ,
    tenant_id   VARCHAR(64)  NOT NULL
);

CREATE INDEX idx_imchannel_tenant_updated ON im_channel(tenant_id, updated_at);
CREATE INDEX idx_imchannel_tenant_type ON im_channel(tenant_id, type);
CREATE INDEX idx_imchannel_direct_key ON im_channel(tenant_id, direct_key);

CREATE TABLE im_channel_member (
    id                   UUID        PRIMARY KEY,
    channel_id           UUID        NOT NULL,
    user_id              UUID        NOT NULL,
    role                 VARCHAR(16) NOT NULL DEFAULT 'MEMBER',  -- 'OWNER' | 'ADMIN' | 'MEMBER'
    last_read_message_id UUID,
    last_read_at         TIMESTAMPTZ,
    joined_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    muted                BOOLEAN     NOT NULL DEFAULT FALSE,
    tenant_id            VARCHAR(64) NOT NULL
);

CREATE UNIQUE INDEX idx_imember_unique ON im_channel_member(channel_id, user_id);
CREATE INDEX idx_imember_tenant_user ON im_channel_member(tenant_id, user_id);

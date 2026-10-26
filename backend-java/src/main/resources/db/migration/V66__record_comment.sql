-- V66: 记录级评论（H1，对标 Notion/Airtable 行内讨论）
-- 设计对齐 im_message：parentId 线程回复 + deleted_at 软删除占位。
CREATE TABLE record_comment (
    id              UUID PRIMARY KEY,
    collection_name VARCHAR(255) NOT NULL,
    record_id       VARCHAR(255) NOT NULL,
    parent_id       UUID REFERENCES record_comment(id),
    author_id       UUID NOT NULL,
    content         TEXT NOT NULL,
    content_type    VARCHAR(16) NOT NULL DEFAULT 'text',
    mentions        TEXT,
    attachment_id   UUID,
    edited_at       TIMESTAMP,
    deleted_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL
);

-- 游标分页主索引：(collection, record, 时间倒序)，tenant_id 前置保证跨租户隔离下的选择性
CREATE INDEX idx_record_comment_list
    ON record_comment (tenant_id, collection_name, record_id, created_at DESC);
CREATE INDEX idx_record_comment_parent
    ON record_comment (tenant_id, parent_id);
CREATE INDEX idx_record_comment_author
    ON record_comment (tenant_id, author_id, created_at DESC);

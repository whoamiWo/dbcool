-- V32__wiki_backlink.sql
-- Wiki 双向链接关系表（替换现有文本 [[slug]] 解析）

CREATE TABLE IF NOT EXISTS wiki_backlink (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    target_page_id UUID REFERENCES wiki_page(id) ON DELETE CASCADE,
    target_slug VARCHAR(256),  -- 兼容目标页已删除的场景
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(source_page_id, target_page_id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_wiki_backlink_source ON wiki_backlink(source_page_id);
CREATE INDEX IF NOT EXISTS idx_wiki_backlink_target ON wiki_backlink(target_page_id);
CREATE INDEX IF NOT EXISTS idx_wiki_backlink_tenant ON wiki_backlink(tenant_id);

COMMENT ON TABLE wiki_backlink IS 'Wiki 双向链接关系（source → target）';

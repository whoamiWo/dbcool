-- V31__wiki_block.sql
-- Wiki Block 模型（对标 Notion）: 页面内容由整块 TEXT 改为 Block 树

CREATE TABLE IF NOT EXISTS wiki_block (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES wiki_block(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,  -- paragraph/heading/bullet/numbered/code/quote/image/embed/todo/divider
    content JSONB NOT NULL DEFAULT '{}',
    sort_order INTEGER NOT NULL DEFAULT 0,
    tenant_id VARCHAR(64) NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wiki_block_page ON wiki_block(page_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_wiki_block_parent ON wiki_block(parent_id);
CREATE INDEX IF NOT EXISTS idx_wiki_block_tenant ON wiki_block(tenant_id);

COMMENT ON TABLE wiki_block IS 'Notion 式 Block 知识库块';

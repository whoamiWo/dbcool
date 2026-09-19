-- ============================================================
--  V24__wiki_notion.sql
--  Notion 剩余能力：模板库 + 回收站 + 只读分享链接
--  依赖: V20__wiki.sql
-- ============================================================

ALTER TABLE wiki_page
    ADD COLUMN IF NOT EXISTS is_template BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS share_token VARCHAR(64) UNIQUE;

CREATE INDEX IF NOT EXISTS idx_wikipage_template ON wiki_page(tenant_id, is_template) WHERE is_template = TRUE;
CREATE INDEX IF NOT EXISTS idx_wikipage_deleted ON wiki_page(tenant_id, deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_wikipage_share_token ON wiki_page(share_token) WHERE share_token IS NOT NULL;

COMMENT ON COLUMN wiki_page.is_template IS '模板标记，从模板建页时复制';
COMMENT ON COLUMN wiki_page.deleted_at IS '软删除时间，30 天内可恢复';
COMMENT ON COLUMN wiki_page.share_token IS '只读分享链接令牌';

-- Wiki 知识库模块迁移脚本
-- 创建 4 张表: knowledge_base, wiki_category, wiki_page, wiki_version
-- 包含 FTS 全文检索支持

-- 知识库表
CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    slug VARCHAR(128) UNIQUE NOT NULL,
    icon VARCHAR(64),
    sort_order INT DEFAULT 0,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 索引
CREATE INDEX idx_knowledge_base_tenant ON knowledge_base (tenant_id);
CREATE INDEX idx_knowledge_base_slug ON knowledge_base (slug);

-- 分类/标签表(树形结构)
CREATE TABLE wiki_category (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES wiki_category(id) ON DELETE SET NULL,
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(knowledge_base_id, slug)
);

-- 索引
CREATE INDEX idx_wiki_category_tenant ON wiki_category (tenant_id);
CREATE INDEX idx_wiki_category_kb ON wiki_category (knowledge_base_id);
CREATE INDEX idx_wiki_category_parent ON wiki_category (parent_id);

-- 文档页面表
CREATE TABLE wiki_page (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES wiki_page(id) ON DELETE SET NULL,
    title VARCHAR(256) NOT NULL,
    slug VARCHAR(256) UNIQUE NOT NULL,
    content TEXT NOT NULL,
    content_html TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 索引
CREATE INDEX idx_wiki_page_tenant_kb ON wiki_page (tenant_id, knowledge_base_id);
CREATE INDEX idx_wiki_page_parent ON wiki_page (parent_id);
CREATE INDEX idx_wiki_page_slug ON wiki_page (slug);
CREATE INDEX idx_wiki_page_status ON wiki_page (status);

-- 版本历史表
CREATE TABLE wiki_version (
    id UUID PRIMARY KEY,
    wiki_page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    version INT NOT NULL,
    content TEXT NOT NULL,
    summary VARCHAR(512),
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE(wiki_page_id, version)
);

-- 索引
CREATE INDEX idx_wiki_version_page ON wiki_version (wiki_page_id, version DESC);

-- FTS 全文检索支持
ALTER TABLE wiki_page ADD COLUMN content_tsv tsvector;
CREATE INDEX idx_wiki_page_fts ON wiki_page USING GIN (content_tsv);

-- FTS 触发器函数
CREATE OR REPLACE FUNCTION wiki_page_tsv_trigger() RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- FTS 触发器
CREATE TRIGGER wiki_page_tsv_update
BEFORE INSERT OR UPDATE ON wiki_page
FOR EACH ROW EXECUTE FUNCTION wiki_page_tsv_trigger();
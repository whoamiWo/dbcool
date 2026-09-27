-- PHASE 56 P2-3 FTS 中文分词：应用层分词 + GIN 索引
-- Alpine 无 gcc/make/git，严禁编译 zhparser/pg_jieba
-- 由应用层（jieba/IK）生成 tsvector，避免数据库扩展依赖

ALTER TABLE wiki_page ADD COLUMN IF NOT EXISTS search_vector tsvector;

CREATE INDEX IF NOT EXISTS idx_wiki_page_search_vector
  ON wiki_page USING GIN (search_vector);

-- 备注：查询时由应用层分词后用 to_tsquery('simple', ...) 匹配
-- H2 测试环境仍走 ILIKE 回退（见 WikiPageRepository 兼容写法）
-- PHASE 57 P0: pgvector embedding column for semantic search
-- Requires the pgvector extension in PostgreSQL
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE wiki_page ADD COLUMN IF NOT EXISTS embedding vector(768);

-- 算子必须与查询一致：Java executeVectorSearch 用 <=>（余弦距离），
-- 故 opclass 用 vector_cosine_ops；若用 vector_l2_ops 索引将不被 <=> 查询命中（全表扫）。
CREATE INDEX IF NOT EXISTS idx_wiki_page_embedding
  ON wiki_page USING hnsw (embedding vector_cosine_ops);

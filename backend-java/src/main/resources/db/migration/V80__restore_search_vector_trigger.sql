-- ============================================================
--  V80__restore_search_vector_trigger.sql
--
--  缺陷（真实 PG 复现，2026-11-04）：
--    V50__ldap_mapping_tenant_and_search_cleanup.sql:11 执行了
--    `DROP TRIGGER IF EXISTS trg_update_search_vector ON unified_search_index;`
--    但没有重建 —— 从此 unified_search_index.content_tsv 永远是 NULL，
--    全文检索 `content_tsv @@ tsquery(...)` 恒为 false。
--    表现为「搜索永远为空」，与 A-6（写入器取错字段）叠加，双重失效。
--
--  本迁移：重建触发器函数与触发器，并回填存量行的 content_tsv。
--  全部幂等，可重复执行。
-- ============================================================

CREATE OR REPLACE FUNCTION update_search_vector()
RETURNS TRIGGER AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple',
        COALESCE(NEW.title, '') || ' ' || COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_update_search_vector ON unified_search_index;
CREATE TRIGGER trg_update_search_vector
    BEFORE INSERT OR UPDATE ON unified_search_index
    FOR EACH ROW EXECUTE FUNCTION update_search_vector();

-- 回填：存量行的 content_tsv 为 NULL（触发器缺失期间写入的记录）
UPDATE unified_search_index
SET content_tsv = to_tsvector('simple',
        COALESCE(title, '') || ' ' || COALESCE(content, ''))
WHERE content_tsv IS NULL;

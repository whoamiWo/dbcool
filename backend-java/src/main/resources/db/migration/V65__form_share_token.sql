-- ============================================================
--  V65__form_share_token.sql
--  公开表单分享 (PHASE10 企业门槛三件套)
--
--  设计要点:
--  - 复用 WikiPageEntity.share_token 的既有设计：UUID 前 32 位，
--    置 null 即撤销分享，无需删除列
--  - UNIQUE 索引保证 token 全局唯一，且 public/{token} 查询走索引
--  - 用 IF NOT EXISTS / ADD COLUMN IF NOT EXISTS 兼容 H2 测试环境
--    (参考 V61 / V64 的 H2 兼容约定)
-- ============================================================

ALTER TABLE forms ADD COLUMN IF NOT EXISTS share_token VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS idx_forms_share_token
  ON forms (share_token);

COMMENT ON COLUMN forms.share_token IS '公开填写分享 token(UUID 前 32 位)；null = 未分享/已撤销';

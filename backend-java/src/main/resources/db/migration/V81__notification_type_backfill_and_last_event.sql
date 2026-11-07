-- ============================================================
--  V81__notification_type_backfill_and_last_event.sql
--  R2：纠偏历史通知类型 + R7：通知聚合不重置已读。
--
--  D13：不改已发布的 V79，新增本迁移回填。
--  D13b：新增 WORKFLOW_NOTICE（流程通知），用于普通流程通知；
--         WORKFLOW_FAILED 只保留真实失败。
-- ============================================================

-- 1) 残留的旧类型（无论何时写入）统一为流程通知
UPDATE messages SET type = 'WORKFLOW_NOTICE' WHERE type = 'workflow';

-- 2) 仅回填 V79 之前创建、被 V79 误标为失败的行。
--    取不到 flyway 安装时间（手工初始化的库）→ 不动数据并记录警告（fail-safe）。
DO $$
DECLARE
  v79_installed_at TIMESTAMPTZ;
BEGIN
  -- 动态 SQL：表不存在时不能在 PL/pgSQL 里静态引用，否则函数编译即报错。
  IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
    BEGIN
      EXECUTE 'SELECT installed_on FROM flyway_schema_history WHERE version = ''79'''
              INTO v79_installed_at;
    EXCEPTION WHEN undefined_column OR undefined_table THEN
      v79_installed_at := NULL;
    END;
  END IF;

  IF v79_installed_at IS NOT NULL THEN
    UPDATE messages SET type = 'WORKFLOW_NOTICE'
    WHERE type = 'WORKFLOW_FAILED'
      AND created_at < v79_installed_at;
  ELSE
    RAISE NOTICE 'flyway_schema_history 或 V79 记录缺失，跳过失败类型回填（不改数据）';
  END IF;
END $$;

-- 3) R7 依赖：最近事件时间（列表仍按 created_at 排序，避免跳动）
ALTER TABLE messages ADD COLUMN IF NOT EXISTS last_event_at TIMESTAMPTZ;

COMMENT ON COLUMN messages.last_event_at IS '最近一次事件时间（聚合合并时更新；列表排序仍用 created_at）';

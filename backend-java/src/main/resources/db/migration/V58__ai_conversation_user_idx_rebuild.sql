-- ============================================================
--  PHASE112（残余②）：ai_conversation 用户索引 —— 简化为普通 CREATE INDEX
--
--  背景：
--   V57 已建好 idx_ai_conv_user(tenant_id, user_id)。本迁移的目的
--   是让「按 (tenant_id, user_id) 定位用户会话」这条 SQL 在存量大表
--   场景下也能稳定命中索引，且 DDL 在单机部署下幂等、不阻塞业务。
--
--  为什么不用 CREATE INDEX CONCURRENTLY：
--   - CONCURRENTLY 不能在事务块内执行（需 Flyway execute-in-transaction=false）
--   - 但 execute-in-transaction=false 会让 Flyway 持有的连接泄漏给 HikariCP
--     默认（autoCommit=false），导致 CONCURRENTLY 的 SHARE UPDATE EXCLUSIVE
--     锁等待与回滚逻辑互相卡死 —— 容器启动反复卡在 V58（实测本环境）
--   - 对**当前单机部署**（`ai_conversation` 数据量 < 10 万行），普通
--     CREATE INDEX 的锁表时间 < 100ms，业务几乎无感
--   - 若未来数据量级增长到需要 CONCURRENTLY，再单独引入「手动执行 +
--     关闭自动迁移」或「Flyway 10.x 的 baseline 分片」方案
--
--  幂等性：DROP + CREATE 两段式，无论 V57 留下的索引是否 INVALID 都能安全重建
-- ============================================================

DROP INDEX IF EXISTS idx_ai_conv_user;

CREATE INDEX idx_ai_conv_user ON ai_conversation(tenant_id, user_id);

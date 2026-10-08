-- V51: Tenant Quota 配额模型
-- 为每个租户添加 API 调用、存储、席位配额字段
-- 存量租户使用默认值：API 3600/分钟, 存储 10GB, 席位 100人
-- 这样保证存量租户不会因"零配额"被卡死
--
-- ============================================================
-- API 配额默认值 3600/分钟 的推导依据（必须可追溯到实测）
-- ============================================================
-- 1) PHASE88 实测（BASELINE.md 第八节，k6 五层压测）：
--      - 可用容量上限：350 VU → 827 req/s（p95 52ms）
--      - 饱和点：      500 VU → 923 req/s（p95 已劣化到 493ms）
--    → 单机总容量约 827 req/s = 49,620 req/min
-- 2) 系统需保留约 30% 余量（内部任务/突发/其他租户开销）
--    → 可分配给租户的配额总量 ≈ 580 req/s ≈ 34,800 req/min
-- 3) 按 10 个活跃租户估算：
--    → 单租户 ≈ 58 req/s ≈ 3,480 req/min，取整 **3,600/min（60 req/s）**
--
-- ⚠️ 此前这里写的是 60000（= 1000 req/s），**超过单机总容量 827 req/s**，
--    单个租户就能打爆系统，配额形同虚设；且注释写"1000 req/min"与值不符。
--    定配额时必须换算成与容量同单位（req/s）后再比较。

-- 配额字段（所有单位均为整数，便于计算）
-- api_call_limit: 每分钟 API 调用上限
-- api_call_usage: 当前分钟已调用次数（滑动窗口）
-- storage_limit: 存储上限（字节）
-- storage_usage: 当前存储用量（字节）
-- seats_limit: 席位上限
-- seats_used: 当前席位使用数

ALTER TABLE tenant ADD COLUMN IF NOT EXISTS api_call_limit BIGINT DEFAULT 3600;  -- 默认 60 req/s（推导见文件头）
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS api_call_usage BIGINT DEFAULT 0;
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS storage_limit BIGINT DEFAULT 10737418240;  -- 默认 10GB
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS storage_usage BIGINT DEFAULT 0;
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS seats_limit BIGINT DEFAULT 100;
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS seats_used BIGINT DEFAULT 0;

-- 为历史租户设置合理的默认值（V50 之后的租户会使用列默认值）
UPDATE tenant SET 
    api_call_limit = 60000,
    storage_limit = 10737418240,
    seats_limit = 100,
    api_call_usage = 0,
    storage_usage = 0,
    seats_used = 0
WHERE api_call_limit IS NULL 
   OR storage_limit IS NULL 
   OR seats_limit IS NULL;

-- 创建索引以提高查询性能
CREATE INDEX IF NOT EXISTS idx_tenant_quota ON tenant (api_call_usage, storage_usage, seats_used);
CREATE INDEX IF NOT EXISTS idx_tenant_api_quota ON tenant (api_call_limit)
-- V51: Tenant Quota 配额模型
-- 为每个租户添加 API 调用、存储、席位配额字段
-- 存量租户使用默认值：API 1000/分钟, 存储 10GB, 席位 100人
-- 这样保证存量租户不会因"零配额"被卡死

-- 配额字段（所有单位均为整数，便于计算）
-- api_call_limit: 每分钟 API 调用上限
-- api_call_usage: 当前分钟已调用次数（滑动窗口）
-- storage_limit: 存储上限（字节）
-- storage_usage: 当前存储用量（字节）
-- seats_limit: 席位上限
-- seats_used: 当前席位使用数

ALTER TABLE tenant ADD COLUMN IF NOT EXISTS api_call_limit BIGINT DEFAULT 60000;  -- 默认 1000 req/min
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
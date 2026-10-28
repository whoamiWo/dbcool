-- Billing: 套餐层级、订阅、账单、发票 (PHASE15 B4)
-- 编号：V74 (接 V73__ai_prompt_template.sql)

-- 1. 套餐层级表 (Plan Tier)
CREATE TABLE billing_plan_tier (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,           -- 套餐名称 (Free/Pro/Enterprise)
    slug VARCHAR(64) UNIQUE NOT NULL,     -- 标识符 (free/pro/enterprise)
    description TEXT,
    price_monthly DECIMAL(10,2) NOT NULL DEFAULT 0,  -- 月付价格
    price_yearly DECIMAL(10,2) NOT NULL DEFAULT 0,   -- 年付价格 (通常有折扣)
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    
    -- 配额限制
    api_call_limit BIGINT NOT NULL DEFAULT 0,      -- 每月 API 调用上限 (0=无限)
    storage_limit BIGINT NOT NULL DEFAULT 0,       -- 存储上限字节 (0=无限)
    seats_limit BIGINT NOT NULL DEFAULT 0,         -- 席位上限 (0=无限)
    features JSONB,                                -- 功能列表 (JSON 数组)
    
    is_active BOOLEAN NOT NULL DEFAULT true,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE billing_plan_tier IS '计费套餐层级定义';
COMMENT ON COLUMN billing_plan_tier.api_call_limit IS '0 表示无限配额';
COMMENT ON COLUMN billing_plan_tier.storage_limit IS '0 表示无限存储';
COMMENT ON COLUMN billing_plan_tier.seats_limit IS '0 表示无限席位';
COMMENT ON COLUMN billing_plan_tier.features IS 'JSON 数组，如 ["ai_assistant", "advanced_analytics", "custom_branding"]';

-- 2. 订阅表 (Subscription)
CREATE TABLE billing_subscription (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    plan_tier_id VARCHAR(64) NOT NULL REFERENCES billing_plan_tier(id) ON DELETE RESTRICT,
    
    status VARCHAR(32) NOT NULL DEFAULT 'active',  -- active/past_due/canceled/expired/trialing
    start_date DATE NOT NULL,
    current_period_start DATE NOT NULL,
    current_period_end DATE NOT NULL,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT false,
    canceled_at TIMESTAMPTZ,
    
    payment_method VARCHAR(32),                    -- alipay/wechat/stripe/card
    last_payment_amount DECIMAL(10,2),
    last_payment_at TIMESTAMPTZ,
    next_billing_date DATE,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- PostgreSQL 不支持带 WHERE 的部分唯一「约束」，只能建部分唯一「索引」：
-- 保证每个租户最多只有一个处于 active/trialing 的订阅。
CREATE UNIQUE INDEX unique_tenant_active_subscription
    ON billing_subscription (tenant_id)
    WHERE (status IN ('active', 'trialing'));

COMMENT ON TABLE billing_subscription IS '租户订阅记录';
COMMENT ON COLUMN billing_subscription.status IS 'active(有效)/past_due(逾期)/canceled(已取消)/expired(已过期)/trialing(试用中)';

-- 3. 账单表 (Invoice)
CREATE TABLE billing_invoice (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    subscription_id VARCHAR(64) REFERENCES billing_subscription(id) ON DELETE SET NULL,
    
    invoice_number VARCHAR(64) UNIQUE NOT NULL,    -- 发票编号 INV-YYYYMMDD-XXXX
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    total_amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    
    status VARCHAR(32) NOT NULL DEFAULT 'draft',   -- draft/open/paid/uncollectible/void
    paid_at TIMESTAMPTZ,
    due_date DATE,
    
    line_items JSONB NOT NULL,                     -- 明细数组 [{desc, qty, unit_price, amount}]
    payment_url VARCHAR(512),                      -- 支付链接
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE billing_invoice IS '账单/发票记录';
COMMENT ON COLUMN billing_invoice.status IS 'draft(草稿)/open(待支付)/paid(已支付)/uncollectible(无法收回)/void(作废)';

-- 4. 支付记录表 (Payment)
CREATE TABLE billing_payment (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    invoice_id VARCHAR(64) REFERENCES billing_invoice(id) ON DELETE SET NULL,
    subscription_id VARCHAR(64) REFERENCES billing_subscription(id) ON DELETE SET NULL,
    
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    payment_method VARCHAR(32) NOT NULL,           -- alipay/wechat/stripe/card
    transaction_id VARCHAR(256),                   -- 第三方交易 ID
    
    status VARCHAR(32) NOT NULL DEFAULT 'pending', -- pending/succeeded/failed/refunded
    failure_reason TEXT,
    
    paid_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    refund_amount DECIMAL(10,2),
    
    metadata JSONB,                                -- 支付网关返回的原始数据
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE billing_payment IS '支付记录';

-- 5. 索引优化
CREATE INDEX idx_subscription_tenant ON billing_subscription(tenant_id);
CREATE INDEX idx_subscription_status ON billing_subscription(status);
CREATE INDEX idx_invoice_tenant ON billing_invoice(tenant_id);
CREATE INDEX idx_invoice_status ON billing_invoice(status);
CREATE INDEX idx_invoice_due_date ON billing_invoice(due_date);
CREATE INDEX idx_payment_tenant ON billing_payment(tenant_id);
CREATE INDEX idx_payment_status ON billing_payment(status);

-- 6. 初始化默认套餐
INSERT INTO billing_plan_tier (id, name, slug, description, price_monthly, price_yearly, 
                               api_call_limit, storage_limit, seats_limit, features, sort_order)
VALUES 
    ('plan_free', '免费版', 'free', '适合个人和小团队体验', 0, 0,
     1000, 1073741824, 3, 
     '{"basic_features": true, "community_support": true}'::jsonb, 0),
     
    ('plan_pro', '专业版', 'pro', '适合成长型业务', 99, 990,
     100000, 10737418240, 10,
     '{"ai_assistant": true, "advanced_analytics": true, "priority_support": true}'::jsonb, 1),
     
    ('plan_enterprise', '企业版', 'enterprise', '适合大型组织', 499, 4990,
     0, 107374182400, 100,
     '{"ai_assistant": true, "advanced_analytics": true, "custom_branding": true, "dedicated_support": true, "sla": true}'::jsonb, 2);

-- ============================================================
--  V82__user_external_identity_and_disable_reason.sql
--  Round 3 审计 F2 / F4：
--   1. 外部身份显式化：users 增加 external_source / external_id，
--      唯一索引 (tenant_id, external_source, external_id)。
--      login username 仍是全局唯一（V1），因此外部账号在跨租户同名时
--      由 UserProvisioningService 追加稳定的租户后缀，绝不改写他人账号。
--   2. 人工禁用与同步离职分离：users 增加 disable_reason。
--      MANUAL = 管理员手工禁用（外部同步不得自动启用）；
--      RESIGNED = 同步判定的离职（重新出现时可自动恢复）。
--  兼容性：全部 ADD COLUMN 可空；历史数据按用户名前缀回填 external_*。
-- ============================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS external_source VARCHAR(32);
ALTER TABLE users ADD COLUMN IF NOT EXISTS external_id     VARCHAR(128);
ALTER TABLE users ADD COLUMN IF NOT EXISTS disable_reason  VARCHAR(32);

COMMENT ON COLUMN users.external_source IS '外部身份来源: FEISHU/DINGTALK/WECOM/WECHAT/LDAP/OIDC/IM';
COMMENT ON COLUMN users.external_id     IS '外部平台用户 id（open_id / userid / uid / sub）';
COMMENT ON COLUMN users.disable_reason  IS 'MANUAL=管理员手工禁用；RESIGNED=同步离职；NULL=正常';

-- 历史数据回填：只处理明确带平台前缀的托管账号，其它账号保持 NULL。
--
-- 注意（G4）：fs_ / dt_ 的用户名就是完整 open_id / userid，回填精确；
-- 但 wecom_ / wechat_ 的历史 username 只保存了 openid/unionid 尾部的 12 位摘要
-- （safeTail），因此它们回填出的 external_id 只是「近似值」，不是完整 id。
-- 下一次组织同步会通过 (tenant_id, source, external_id) 回退到租户内 username 命中，
-- 并用平台返回的完整 id 覆盖该字段；在首次同步之前，按 external_id 的审计查询
-- 可能看到摘要值。LDAP/OIDC 的历史账号没有用户名前缀，保持 NULL，需按来源单独纠偏。
UPDATE users SET external_source = 'FEISHU', external_id = substr(username, 4)
 WHERE external_source IS NULL AND left(username, 3) = 'fs_';

UPDATE users SET external_source = 'DINGTALK', external_id = substr(username, 4)
 WHERE external_source IS NULL AND left(username, 3) = 'dt_';

UPDATE users SET external_source = 'WECOM', external_id = substr(username, 7)
 WHERE external_source IS NULL AND left(username, 6) = 'wecom_';

UPDATE users SET external_source = 'WECHAT', external_id = substr(username, 8)
 WHERE external_source IS NULL AND left(username, 7) = 'wechat_';

-- 同一租户内 (source, external_id) 唯一；跨租户允许相同 open_id。
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_tenant_external
    ON users (tenant_id, external_source, external_id)
    WHERE external_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_tenant_username ON users (tenant_id, username);
CREATE INDEX IF NOT EXISTS idx_users_tenant_disable_reason ON users (tenant_id, disable_reason);

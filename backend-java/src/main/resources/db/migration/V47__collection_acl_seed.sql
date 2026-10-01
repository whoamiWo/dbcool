-- V47: Collection ACL seed - 为所有已有集合播种 admin 角色的 READ/CREATE/UPDATE/DELETE 策略
--
-- 问题: Collection 服务（如 verify_*/formtest_*/testcollection123 等）在线后，
--       acl_policies 中缺乏任何策略，导致 admin 用户访问时被 AclEnforcer 拒绝:
--       "ACL 拒绝: user=... 不能 READ collection=verify_1788939889"(HTTP 403)。
--       这与 PHASE61 Wiki 补种相同性质，后者以 V40 实现。
--
-- 修复: 为 admin 角色播种所有已有集合的 ACL 策略。
--       集合来源: collection_meta 表。
--       按角色名 admin 关联（而非硬编码 role uuid），并用 NOT EXISTS 保证幂等，
--       便于在多租户/重新初始化环境下重复执行。
--
-- 注意: 此迁移仅补播种，不覆盖既有策略；适用于 ROLE_API 同库的情况。

INSERT INTO acl_policies (role_id, type, subject, action, config, tenant_id)
SELECT r.id,
       'ACTION',
       c.name,
       a.action,
       '{}'::jsonb,
       r.tenant_id
FROM roles r
CROSS JOIN (SELECT name FROM collection_meta) AS c
CROSS JOIN (VALUES ('READ'), ('CREATE'), ('UPDATE'), ('DELETE')) AS a(action)
WHERE r.name = 'admin'
  AND NOT EXISTS (
        SELECT 1
        FROM acl_policies p
        WHERE p.role_id = r.id
          AND p.type = 'ACTION'
          AND p.subject = c.name
          AND p.action = a.action
  );
-- V40: Wiki / 知识库资源的默认 ACL 策略
--
-- 问题: Wiki 模块(V20/V24/V31~V33/V38)上线了实体与接口,但 acl_policies 中
--       从未为 wiki_page / knowledge_base / wiki_category 播种任何策略,
--       导致所有用户(含 admin)访问 Wiki 时被 AclEnforcer 拒绝:
--       "ACL 拒绝: user=... 不能 READ collection=wiki_page"(HTTP 403)。
--       实测: 补策略前 /api/wiki/search 与 /api/wiki/search/hybrid 均 403。
--
-- 修复: 为 admin 角色播种 wiki 三资源的 ACTION(READ/CREATE/UPDATE/DELETE)。
--       按角色名关联(而非硬编码 role uuid),并用 NOT EXISTS 保证幂等,
--       便于在多租户/重新初始化环境下重复执行。

INSERT INTO acl_policies (role_id, type, subject, action, config, tenant_id)
SELECT r.id,
       'ACTION',
       s.subject,
       a.action,
       '{}'::jsonb,
       r.tenant_id
FROM roles r
CROSS JOIN (VALUES ('wiki_page'), ('knowledge_base'), ('wiki_category')) AS s(subject)
CROSS JOIN (VALUES ('READ'), ('CREATE'), ('UPDATE'), ('DELETE')) AS a(action)
WHERE r.name = 'admin'
  AND NOT EXISTS (
        SELECT 1
        FROM acl_policies p
        WHERE p.role_id = r.id
          AND p.type = 'ACTION'
          AND p.subject = s.subject
          AND p.action = a.action
  );

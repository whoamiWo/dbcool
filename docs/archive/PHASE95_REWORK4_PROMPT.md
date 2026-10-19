# PHASE95 返工 4 提示词：T6 二次收尾 + T7 越权回归接线

> 使用方式：把下面 `BEGIN PROMPT` 与 `END PROMPT` 之间的**全文**投喂给 Kilo Code + GLM-5.3。
> 配套任务书：`PHASE95_REWORK4_TASKS.md`（同目录，可对照阅读，但提示词本身已自包含）。

---

BEGIN PROMPT

# 任务：PHASE95 返工 4 —— 让跨租户隔离测试真正存在，并锁住 T7 未接线项

## 0. 背景（先读，这决定你要交什么）

你在 `/home/who/multistack-project`（企业级协作平台 DBCool，目标对外多租户 SaaS）。
上一批（PHASE95 T6 收尾）你交付了"跨租户越权修复"，审计判定 **❌ 不通过**，理由：

- ✅ 代码改对了：`UserAdminService.java:108-130` 的 `assignRole`/`removeRole` 双向租户校验已落地，
  Controller 调用点 `UserAdminController.java:124,138` 也已补传 `user.tenantId()`。
- ❌ **`scripts/tenant-isolation-e2e-verify.py` 根本不存在**（三路核查全空：read_file 找不到、
  全仓 search 0 结果、`git status` 无该条目）。交付报告里的"交叉访问矩阵全 PASS"没有产出它的文件。
- ❌ "改回 `findById` 脚本会 FAIL"只写在注释里，**没有实际执行**。
- ❌ 十项门禁只报了四项，五项写"未重跑"。
- ❌ 声称"已添加红线第 10 条"，实测 `PHASE95_103_INDEX.md` 红线止于第 9 条（`:82-86`），
  `:88` 起已是 DoD 章节，第 10 条不存在。

**一句话：代码改对了，证据一件都没交。** 本批就是把证据变成真的。

> ⚠️ 本提示词里所有 `文件:行号` 是 2026-10-11 快照，**行号会漂移，开工前逐条核对，以实测为准**。
> 发现不符就按红线第 6 条回报差异，不要照抄硬改。

## 1. 你要交付的五项

### R4-1（P0）新建并跑通 `scripts/tenant-isolation-e2e-verify.py`

这是验收核心。**脚本不存在 = 整批不通过。**

**依赖**：HTTP 只用标准库 `urllib.request`/`urllib.error`（**不许引入 requests**，对齐
`scripts/admin-authz-e2e-verify.py`）。DB 访问优先 `psycopg2`，没装就 `subprocess` 调 `psql`
（本地或 `docker exec nocobase-postgres psql`），交付里说明实际走了哪条。
连接信息取自 `application.yml:9-11`（`jdbc:postgresql://localhost:5432/nocobase`，
user `nocobase` / `dev_password`），支持环境变量 `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD` 覆盖，
**禁止硬编码密码**。

**为什么租户 B 只能靠 DB 直插**（已核实，不用再找）：
后端没有租户切换端点、没有注册端点。JWT 的 `tid` 只来自 `users.tenant_id`
（`AuthController.java:83-86`）；`POST /api/admin/users` 的租户恒等于调用者租户
（`UserAdminController.java:66`）。所以租户 A 的 admin **无法通过任何 API** 造出租户 B 的用户。

四张表缺一不可：
```sql
-- 1) 租户（也可走 API: POST /api/admin/tenants，TenantController.java:60-70）
--    tenantId 格式 ^[a-z][a-z0-9_]{0,62}$（TenantService.java:23,75-80）
INSERT INTO tenant (id, name, slug, status, schema_name, created_at)
VALUES ('tenant_b', '租户B', 'tenantb', 'ACTIVE', 'tenant_b', NOW());

-- 2) 角色：缺这行 JWT 会回落 ROLE_USER，所有 hasRole('ADMIN') 端点返 403，
--    脚本会误判成"隔离生效"——这是最危险的假绿
INSERT INTO roles (id, name, description, tenant_id, created_at)
VALUES (<uuid>, 'admin', '租户B管理员', 'tenant_b', NOW());

-- 3) 用户：username 全局唯一（UserAdminService.java:61 findByUsername 不带租户）
--    password_hash 直接 SELECT password_hash FROM users WHERE username='admin' 复用，
--    避免 Python 侧依赖 bcrypt，也避免与后端哈希算法不一致（新用户密码即 admin123）
INSERT INTO users (id, username, password_hash, tenant_id, display_name, created_at)
VALUES (<uuid>, 'tenantb_admin', <复用 admin 的 hash>, 'tenant_b', 'AdminB', NOW());

-- 4) 绑定角色 → 登录后 JWT roles 含 admin
INSERT INTO user_roles (user_id, role_id) VALUES (<用户uuid>, <角色uuid>);
```

**凭证必须自证有效**：登录后 base64url 解 JWT payload（不需验签），断言 `tid == 'tenant_b'`
且 `roles` 含 `'admin'`。任一条不满足 → `sys.exit(1)`。**禁止 `None` 兜底**——
拿不到租户 B 的有效 admin 凭证，后面所有 403 都只是"没权限"而不是"被隔离"，
这与 R1 那次 `user_token=None` 导致 13 条断言全被跳过是同一个坑。

**交叉访问矩阵**（A = `admin`/`admin123`，B = `tenantb_admin`；两端各建真实资源：
automation 规则、用户、知识库、wiki 页面、分类、模板、huddle、pin、BI 报表。
建不了的要建就在交付里显式列出跳过了哪些，**禁止静默跳过**）：

| # | 请求 | 期望 | 依据 |
|---|---|---|---|
| 1 | A → `GET /api/automation/{A的ruleId}` | **200**（基线：证明端点本身可用） | `AutomationRuleController.java:128-140` |
| 2 | B → `GET /api/automation/{A的ruleId}` | **404** | 同上 |
| 3 | B → `GET /api/admin/users/{A的用户id}` | **404** | `UserAdminController.java:51-59` |
| 4 | B → `POST /api/admin/users/{A的用户}/roles/{B的角色}` | **404**（用户非当前租户） | `UserAdminController.java:117-129` |
| 5 | A → `POST /api/admin/users/{A的用户}/roles/{B的角色}` | **404**（角色非当前租户） | 同上，双向都要测 |
| 6 | B → `GET /api/wiki/kb/{A的kb}` | **403** | `WikiController.java:141-143` |
| 7 | B → `GET /api/wiki/pages/{A的page}` | **403** | `WikiController.java:244-255` |
| 8 | B → `GET /api/wiki/kb/{A的kb}/categories` | **200 + 空列表**（修复前会返回 A 的分类） | `WikiController.java:514` |
| 9 | B → `GET /api/wiki/kb/{A的kb}/templates` | **200 + 空列表**（同上） | `WikiController.java:638` |
| 10 | B → `GET /api/im/huddle/{A的huddle}` | **404** | `ImHuddleController.java:159-171` |
| 11 | B → `GET /api/im/messages/{A的msg}/thread` | **404** | `ImMessageController.java:192-202` |
| 12 | B → `GET /api/im/messages/pins?channelId={A的channel}` | **200 + 空列表** | `ImMessageController.java:57-65` |
| 13 | B → `GET /api/bi/reports?collectionName=...` | **200 + 空列表** | `BiReportController.java:85-93` |
| 14 | B → `GET /api/admin/users` | **200 且列表不含 A 的任何用户名** | `UserAdminController.java:43-49` |
| 15 | AI 会话路径 | B 的用户不得命中 A 的会话 | `AgentService.java:136-140` |

> ⚠️ **期望码会随 R4-5 变化，别写死错的值**：
> - 第 6 条修复前是 **403**（`WikiController.java:141-143` 手工比对抛 `FORBIDDEN`），
>   R4-5 第 5 项改成带租户查询后变成 **404**（`NOT_FOUND`）。脚本按**最终实现**断言**单一值**，
>   并在交付里说明这个值来自哪一行代码。
> - 第 8、9 条修复前会 **FAIL**（返回 A 的数据），R4-5 第 2、3 项修好后才是"200 + 空列表"。
> - 第 15 条同理，取决于第 1 项改用哪种查询方式。
>
> 决不允许用 `in (403, 404)` 两头都算赢来回避这个问题。

- ❌ **禁止把期望统一写成"403 或 404 都算通过"** —— 那会让断言退化为恒真（任何非 200 都算赢），
  连 500 冒充校验都拦不住。第 8/9/12/13 条是"200 + 空列表"，必须单独处理。
- ❌ 写操作返回 500 不算业务拒绝，判 FAIL（红线第 9 条）。
- 每条打印**两侧**真实状态码（自己能访问 + 对方被拒），只贴一侧视为无效证据。

**先红后绿（强制）**：先跑一次**未修复**版本，把第 8、9、15 条的 FAIL 输出原样留存
（这就是"第一版必须先红"），修完 R4-5 再跑 PASS。**两份输出都要交付。**
直接交一份全绿的脚本 = 把存量问题合法化 = 打回。

**清理**：`atexit`/`finally` 按外键顺序 `user_roles` → `users` → `roles` → `tenant` 删除，
每条 `WHERE tenant_id = 'tenant_b'`（禁止宽泛删除），并清理测试资源。
残留的 `tenant_b` 会污染后续门禁（`mvn -o test`、`smoke.py`）。

验收：`python3 scripts/tenant-isolation-e2e-verify.py` + `echo "exit=$?"`，贴完整输出。

### R4-2（P0）实测可失败性

至少两处对照（用户侧 + 资源侧）：
1. `UserAdminService.java:108` 的 `findByIdAndTenantId(userId, tenantId)` 改回 `findById(userId)`
2. automation 或 wiki 侧任一处 `findByIdAndTenantId` 改回 `findById`

每次改完重跑，**贴 FAIL 输出（哪条断言失败、实际状态码多少）**，再改回复跑 PASS，两个方向都交。
改回后仍全绿 = 脚本恒真 = 本批不通过。

### R4-3（P0）补全量门禁十项

十项**每一项都要实测数字**，不许写"未重跑"：

| # | 命令 | 基线（不得回退） |
|---|---|---|
| 1 | `cd backend-java && mvn -o test` | > 1411（上轮实测 1414） |
| 2 | `cd backend-java && mvn -o verify` | JaCoCo BUNDLE ≥ 82% |
| 3 | `cd frontend && npm run test:run` | > 382 |
| 4 | `cd frontend && npx tsc --noEmit` | 0 errors |
| 5 | `cd frontend && npx playwright test` | ≥ 135（当前实测 134，`collab-crdt` 为既有遗留，交付里单列说明） |
| 6 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed / 1 skipped |
| 7 | `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| 8 | `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 |
| 9 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（本批新增） |
| 10 | `python3 scripts/smoke.py` | 11 / 11 |

**提交约定**：当前工作区有 25+ 个 modified 后端文件 + `PHASE95_T6_FINAL_TASKS.md`、
`PHASE95_T6_FINAL_PROMPT.md` 两个 untracked 文件，均未 commit。
**不得自行 commit / push**，交付里写"commit & push：待授权"。
必须附 `git status --short` 全文与 `git log --oneline -3`。

### R4-4（P1）红线第 10 条真正写入索引

在 `PHASE95_103_INDEX.md` 第 9 条（`:82-86`）之后、"## 五、每批完成定义（DoD）"（`:88`）之前插入：

```markdown
10. **角色鉴权测试通过 ≠ 租户隔离有效** —— 必须存在**独立的跨租户测试**
    （两个租户的 token 交叉访问对方资源，断言 403/404 或空列表）。只测"同租户内
    admin vs user"的脚本测不出跨租户越权。
    反面案例（PHASE95 T6）：`admin-authz-e2e-verify` 18/18 全绿，
    但 `assignRole` 可跨租户赋角色无人发现。
```

并把门禁表第 58 行 `| 越权回归 | python3 scripts/tenant-isolation-e2e-verify.py | 全 PASS（PHASE95 T7） |`
更新为已接线 + 实测条数。

### R4-5（P0/P1）T7 未接线项收口（每项都要在矩阵里有对应断言行）

**1（P0）AI 会话可跨租户命中**：`AgentService.java:136-140` 的 `ensureConversation` 用了无租户的
`conversationRepo.findByAgentIdAndChannelIdAndUserId(...)`（定义 `AiConversationEntityRepository.java:24-27`，
JPQL 无 tenantId）。修法：Repository 新增带租户查询（或在 `@Query` 上加 `AND c.tenantId = :tenantId`），
`ensureConversation` 加 `tenantId` 参数 —— tenantId 已在调用链上：
`AgentService.java:89` 位于 `executeInChannel(String tenantId, ...)`（`:75-76`）。
**不要**给 `AgentService` 加 tenantId 字段（走参数传递，线程安全）。同步 `AgentServiceTest.java:90` 的 mock。

**2（P0）Wiki 分类树可跨租户枚举**：`WikiCategoryService.tree(UUID kbId)`（`:51-53`）无租户参数 →
`WikiController.java:514` 的 `GET /api/wiki/kb/{kbId}/categories` 用 B 的 token 能列出 A 的全部分类。
改为 `tree(UUID kbId, String tenantId)`，改用**已存在**的 `WikiCategoryRepository.java:18`
`findByKnowledgeBaseIdAndTenantIdOrderBySortOrderAscCreatedAtAsc`（别新增查询方法）。
调用点全改（漏一个就编译断裂）：`WikiController.java:514`、`WikiCategoryServiceTest.java:45`、
`WikiControllerTest.java:204`（mock 桩）。

**3（P0）Wiki 模板列表可跨租户枚举**：`WikiTemplateService.listTemplates(UUID kbId)`（`:60-62`）→
`WikiController.java:638`。改为 `listTemplates(UUID kbId, String tenantId)`，改用**已存在**的
`WikiTemplateRepository.java:28-29` `findByKbIdAndTenantIdOrderByUpdatedAtDesc`。
⚠️ 带租户版排序是 `updatedAt DESC`，原版无排序，**返回顺序会变**；若测试依赖顺序，
新增 `OrderByCreatedAtAsc` 变体或调整断言，**不要改断言去迁就实现**。

**4（P1）创建路径未校验知识库归属**：`WikiCategoryService.java:34` 与 `WikiTemplateService.java:42`
都调用无租户的 `knowledgeBaseService.get(kbId)` → 可在别人的知识库下建分类/模板。
改用 `KnowledgeBaseService.java:56-59` 的 `get(UUID id, String tenantId)`（两个调用方签名里已有 tenantId）。
正确范式：`WikiPageService.java:56`。

**5（P1）知识库读写仍在用无租户查询 + 手工比对**：`WikiController.java:140`（`getKb`）与 `:172`（`deleteKb`）
用无租户 `get(id)` 再靠 `:141-143`/`:173-175` 手工比对抛 403。功能上拦得住，但漏写一行就越权。
改用 `get(id, user.tenantId())` 后可删手工比对。同步 mock：`WikiControllerTest.java:136,146`。

**6（P1）清理无租户重载**：
`WikiTemplateService.getTemplate(UUID)`（`:75-78`）**全仓 0 调用**，直接删；
`WikiCategoryService.get(UUID)`（`:63-66`）仍有 `WikiController.java:566` 与
`WikiCategoryServiceTest.java:78` 调用 → 改带租户版；
`WikiTemplateService.deleteTemplate`（`:85`）的 `findById` + 手工比对 → 改用**已存在**的
`WikiTemplateRepository.java:21` `findByIdAndTenantId`。

## 2. 红线（违反即打回）

1. 跨租户测试必须独立于角色鉴权测试 —— 不许拿 18/18 代替，不许写成同租户 admin vs user 的换皮版。
2. 必须有真实 HTTP 证据 —— 矩阵逐条贴**两侧**状态码。
3. 禁止恒真断言 —— 必须做"改回 `findById` → FAIL"对照并贴输出；禁止"403 或 404 都算通过"。
4. 禁止 `None` 兜底 —— 拿不到租户 B 有效 admin 凭证就 `sys.exit(1)`，且必须校验 JWT 的 `tid` 与 `roles`。
5. 禁止为跑绿删测试或放宽断言 —— 发现真实越权就报出来，先红后绿。
6. 文档与代码不符时以代码为准并回报差异。
7. 必须报全量门禁十项与 commit hash；**提交需用户授权，不得自行 commit/push**。

## 3. 交付清单（九项，缺一即打回）

1. `scripts/tenant-isolation-e2e-verify.py` **全文**（脚本必须真实存在于仓库）
2. 先红输出（修复前第 8/9/15 条 FAIL）+ 后绿输出（全 PASS）+ `exit=$?`
3. R4-2 两处可失败性对照的 FAIL/PASS 输出（含失败的断言行与实际状态码）
4. 租户 B 创建方式说明（DB 直插还是 API、密码哈希来源、清理顺序）
5. R4-5 六项修复的 `文件:行号` + 对应矩阵断言编号
6. 索引红线第 10 条原文 + 门禁表第 58 行更新后文本
7. 门禁十项实测数字（逐条命令 + 结果），不得出现"未重跑"
8. `git status --short` + `git log --oneline -3` + commit hash（若已授权提交）
9. **遗留项**（强制）：跳过了哪些资源/断言及原因；`collab-crdt` E2E 遗留状态；`scripts/test-auth.py` 处置建议

## 4. 可复用（别重造）

- 脚本风格照搬 `scripts/admin-authz-e2e-verify.py`：`login()`/`http()`/`fetch_json()`/`get_user_id()`/
  `atexit` 清理/`sys.exit(0 if passed == total else 1)`。
- 租户校验范式：`compliance/UserDataErasureService.java` 的 `requireUserInTenant`；
  端点范式 `AttachmentController.java:99,169`、`ProjectBoardController.java:131-135,396-400`。
- 已有带租户查询（优先复用）：`UserRepository.findByIdAndTenantId`、`WikiCategoryRepository.java:18`、
  `WikiTemplateRepository.java:21,28-29`、`AiConversationEntityRepository.java:30,32`、
  `KnowledgeBaseService.java:56-59`。
- 响应约定：成功统一 `{"code":0,"message":"success","data":{...}}`；错误走 Spring `ResponseStatusException`
  （真实 HTTP 403/404，body 为 Spring 默认 error JSON，非 code 包装）。
- 环境事实：JWT TTL 15 分钟（`JwtService.java:42`）；`TenantContext` 每请求 set/clear，无跨请求泄漏，
  脚本可在同一进程交替用两个 token；`user_tenant` 关联表存在但登录不查它，
  `POST /api/admin/users/{userId}/tenants` 会触发 `checkSeatsQuota`（可能 403），**不要**走这条路。

## 5. 本批不做什么

不改前端、不新增业务功能、不做大范围重构、不顺带处理 PHASE96+ 事项、不自行 commit/push。

END PROMPT

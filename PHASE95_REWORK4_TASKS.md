# PHASE95 返工 4 任务书：T6 二次收尾 + T7 越权回归接线（🔒-SaaS-P0）

> 依据：PHASE95 T6 收尾报告审计判定（2026-10-11）—— **❌ 不通过，需返工**
> 一句话：**代码改对了，证据一件都没交。** 跨租户回归脚本根本不存在，报告里的
> "交叉访问矩阵全 PASS"没有产出它的文件。本批要把它变成真的，并顺手锁住 T7 未接线项。

> ⚠️ **本文所有 `文件:行号` 均为 2026-10-11 快照，行号会漂移。开工前必须逐条核对，
> 以实测为准；发现不符按红线第 6 条回报差异，不要照抄。**

---

## §1 上一轮审计判定（本批的存在理由）

| 项 | 判定 | 证据 |
|---|---|---|
| F1 `assignRole` 跨租户越权修复 | ✅ **通过** | `UserAdminService.java:108-130` 双向校验 `userRepository.findByIdAndTenantId` + `roleRepository.findByIdAndTenantId`；Controller 调用点已接真：`UserAdminController.java:124` `assignRole(id, roleId, user.tenantId())`、`:138` `removeRole(id, roleId, user.tenantId())` |
| F2 跨租户回归脚本 | ❌ **不通过（致命）** | `scripts/tenant-isolation-e2e-verify.py` **文件不存在**。三路核查全空：① `read_file` → Could not find file；② 全仓 `search_file` 递归 → 0 结果；③ `git status --short` 只有 25 个 modified 后端文件 + `?? PHASE95_T6_FINAL_PROMPT.md`、`?? PHASE95_T6_FINAL_TASKS.md`，无该脚本条目；`ls scripts/ \| grep -i tenant` 为空 |
| F3 `update`/`delete` before 快照加租户 | ✅ 通过 | `UserAdminController.java:81`、`:108` 均改为 `userService.get(id, user.tenantId())` |
| F4 `listAll` 数据库层过滤 | ✅ 通过 | `UserAdminService.java:41-43` 改为 `userRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)` |
| F5 可失败性验证 | ❌ **不通过** | "改回 `findById` → 脚本 FAIL"只写在注释里，**未实际执行**。声称可失败 ≠ 验证可失败 |
| F6 全量门禁 | ❌ **不通过** | 十项门禁中 **5 项未重跑**（前端 `npm run test:run`、`tsc --noEmit`、playwright、pytest、backup 29 项），仅报 mvn 1414 / admin-authz 18/18 / smoke 11/11 |
| F7 红线第 10 条写入索引 | ❌ **不通过** | `PHASE95_103_INDEX.md` 红线止于第 9 条（`:82-86`），`:88` 起已是"## 五、每批完成定义（DoD）"。**第 10 条不存在** |

**根本问题**：F1 修掉了一个具体的洞，但没有任何东西能证明"跨租户访问确实被拒"。
这正是本项目已经吃过一次的亏——`admin-authz-e2e-verify.py` 18/18 全绿的那段日子里，
`assignRole` 照样能跨租户赋角色，无人发现。测试全绿而越权仍在，比测试红更危险。

---

## §2 本批五项任务

### R4-1（P0）真正产出并跑通 `scripts/tenant-isolation-e2e-verify.py`

**这是本批的验收核心。脚本不存在 = 整批不通过。**

#### 2.1 依赖约束

- HTTP 只用标准库 `urllib.request` / `urllib.error`（与 `scripts/admin-authz-e2e-verify.py` 完全一致），**不得引入 `requests`**。
- DB 访问：优先 `psycopg2`；未安装则退化为 `subprocess` 调 `psql`（本地或 `docker exec nocobase-postgres psql`）。两种方式都要在交付里说明实际走了哪条。
- 连接信息取自 `application.yml:9-11`（`jdbc:postgresql://localhost:5432/nocobase`，user `nocobase` / `dev_password`），并支持环境变量覆盖（`PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD`）。**禁止把密码硬编码进脚本。**

#### 2.2 造租户 B（脚本自包含，无需人工前置）

后端**没有**租户切换端点、**没有**注册端点（`AuthController.java:83-86`：JWT 的 `tid` 只来自 `users.tenant_id`；`UserAdminController.java:66`：新建用户的租户恒等于调用者租户），
所以租户 B 的用户**只能靠 DB 直插**。四张表缺一不可：

```sql
-- 1) 租户（也可走 API: POST /api/admin/tenants，TenantController.java:60-70）
INSERT INTO tenant (id, name, slug, status, schema_name, created_at)
VALUES ('tenant_b', '租户B', 'tenantb', 'ACTIVE', 'tenant_b', NOW());
--   tenantId 格式强约束 ^[a-z][a-z0-9_]{0,62}$（TenantService.java:23,75-80）

-- 2) 角色 —— 缺这一行 JWT 会回落 ROLE_USER，所有 hasRole('ADMIN') 端点返 403，
--    脚本会误判为"隔离生效"，这是最危险的假绿
INSERT INTO roles (id, name, description, tenant_id, created_at)
VALUES (<uuid>, 'admin', '租户B管理员', 'tenant_b', NOW());

-- 3) 用户 —— 注意 UserAdminService.java:61 findByUsername 不带租户，username 全局唯一
--    password_hash: SELECT password_hash FROM users WHERE username='admin' 复用之
--    （避免 Python 侧依赖 bcrypt，也避免与后端哈希算法不一致；新用户密码即 admin123）
INSERT INTO users (id, username, password_hash, tenant_id, display_name, created_at)
VALUES (<uuid>, 'tenantb_admin', <复用 admin 的 hash>, 'tenant_b', 'AdminB', NOW());

-- 4) 绑定角色 → 登录后 JWT roles 含 admin
INSERT INTO user_roles (user_id, role_id) VALUES (<用户uuid>, <角色uuid>);
```

#### 2.3 凭证必须自证有效（否则整批 FAIL）

登录后**解析 JWT payload**（base64url 解中段，不需要校验签名）并断言：

- `tid == 'tenant_b'`
- `roles` 包含 `'admin'`

任一条不满足 → `sys.exit(1)`。**禁止 `None` 兜底**——拿不到租户 B 的有效 admin 凭证，
后面所有 403/404 断言都会因为"根本没权限"而假绿，这与 R1 那次 `user_token=None` 是同一个坑。

#### 2.4 交叉访问矩阵

在租户 A（`admin`/`admin123`）与租户 B（`tenantb_admin`）下各创建**真实资源**：
automation 规则、用户、知识库、wiki 页面、分类、模板、huddle、pin、BI 报表等。
**能用 API 建的必须建；建不了的要建，就在交付里显式列出跳过了哪些并说明原因（禁止静默跳过）。**

每条断言按端点**分别声明期望码**：

| # | 请求 | 期望 | 依据 |
|---|---|---|---|
| 1 | A admin → `GET /api/automation/{A的ruleId}` | **200**（基线：证明端点本身可用） | `AutomationRuleController.java:128-140` |
| 2 | B admin → `GET /api/automation/{A的ruleId}` | **404** | 同上，`automationService.getRule(ruleId, user.tenantId())` |
| 3 | B admin → `GET /api/admin/users/{A的用户id}` | **404** | `UserAdminController.java:51-59` |
| 4 | B admin → `POST /api/admin/users/{A的用户}/roles/{B的角色}` | **404**（用户非当前租户） | `UserAdminController.java:117-129` + `UserAdminService.java:108-130` |
| 5 | A admin → `POST /api/admin/users/{A的用户}/roles/{B的角色}` | **404**（角色非当前租户） | 同上，双向都要测 |
| 6 | B admin → `GET /api/wiki/kb/{A的kb}` | **403** | `WikiController.java:141-143` 显式 tenantId 比对 |
| 7 | B admin → `GET /api/wiki/pages/{A的page}` | **403** | `WikiController.java:244-255` |
| 8 | B admin → `GET /api/wiki/kb/{A的kb}/categories` | **200 + 空列表**（修复前会返回 A 的分类） | `WikiController.java:514` → R4-5 第 2 项 |
| 9 | B admin → `GET /api/wiki/kb/{A的kb}/templates` | **200 + 空列表**（同上） | `WikiController.java:638` → R4-5 第 3 项 |
| 10 | B admin → `GET /api/im/huddle/{A的huddle}` | **404** | `ImHuddleController.java:159-171` |
| 11 | B admin → `GET /api/im/messages/{A的msg}/thread` | **404** | `ImMessageController.java:192-202` → `MessageService.mustGet` |
| 12 | B admin → `GET /api/im/messages/pins?channelId={A的channel}` | **200 + 空列表** | `ImMessageController.java:57-65` |
| 13 | B admin → `GET /api/bi/reports?collectionName=...` | **200 + 空列表** | `BiReportController.java:85-93` |
| 14 | B admin → `GET /api/admin/users` | **200，列表不得含 A 的任何用户名** | `UserAdminController.java:43-49` |
| 15 | A admin → AI 会话（`AgentService` 路径） | B 的用户不得命中 A 的会话 | R4-5 第 1 项 |

> ⚠️ **期望码会随 R4-5 变化，别写死错的值**：
> - 第 6 条修复前是 **403**（`WikiController.java:141-143` 手工比对抛 `FORBIDDEN`），
>   R4-5 第 5 项改成带租户查询后变成 **404**（`NOT_FOUND`）。脚本按**最终实现**断言**单一值**，
>   并在交付里说明这个值来自哪一行代码。
> - 第 8、9 条修复前会 **FAIL**（返回 A 的数据），R4-5 第 2、3 项修好后才是"200 + 空列表"。
> - 第 15 条同理，取决于第 1 项改用哪种查询方式。
>
> 决不允许用 `in (403, 404)` 两头都算赢来回避这个问题。

**红线**：
- ❌ **禁止把期望统一写成"403 或 404 都算通过"** —— 那会让断言退化为恒真（任何非 200 都算赢），
  连 500 冒充校验都拦不住。第 8、9、12、13 条是"200 + 空列表"，不是 403/404，必须单独处理。
- ❌ 写操作（POST/PUT/DELETE）返回 500 不算业务拒绝，按红线第 9 条判 FAIL。
- 每条都要打印 `A→自己的资源` 与 `B→A的资源` **两侧真实状态码**，只贴一侧视为无效证据。

#### 2.5 先红后绿（强制）

**先跑一次未修复版本，把第 8、9、15 条的 FAIL 输出原样留存** —— 这就是红线第 8 条要求的
"架构/回归测试第一版必须先红"。修完 R4-5 再跑 PASS。两份输出都要交付。
直接交一份全绿的脚本，会被判定为"把存量问题合法化"。

#### 2.6 清理

`atexit` / `finally` 中删除种子数据，**顺序遵守外键依赖**：
`user_roles` → `users` → `roles` → `tenant`，每条 `WHERE tenant_id = 'tenant_b'`（禁止宽泛删除），
并清理测试期间创建的资源。清理失败要打印警告。

**为什么必须清理**：残留的 `tenant_b` 会污染后续门禁（尤其 `mvn -o test` 与 `smoke.py`），
而且会让下一批的脚本以为"租户 B 已存在"，埋下假绿。

#### 2.7 验收命令

```bash
python3 scripts/tenant-isolation-e2e-verify.py    # 期望全 PASS，exit 0
echo "exit=$?"                                     # 必须贴
```

---

### R4-2（P0）实测可失败性 —— 不做这一步等于没验证

上一轮只在注释里写了"改回 `findById` 会 FAIL"，本轮必须**实际执行并贴输出**。

至少做**两处**对照（用户侧 + 资源侧）：

1. `UserAdminService.java:108` 的 `userRepository.findByIdAndTenantId(userId, tenantId)` 改回 `findById(userId)`
2. automation 或 wiki 侧任一处 `findByIdAndTenantId` 改回 `findById`

每次改完重跑脚本，**贴出 FAIL 输出（含具体失败的是哪一条断言、实际状态码是多少）**，
再改回并复跑 PASS。**两个方向的输出都要交付。**

> 这一步的意义：证明脚本真的在测隔离，而不是在测"HTTP 能连通"。
> 改回后仍然全绿 = 脚本是恒真的 = 本批不通过。

---

### R4-3（P0）补全量门禁十项

上一轮十项只报了四项。本批**每一项都要有实测数字**，不许写"未重跑"。

| # | 门禁 | 命令 | 基线（不得回退） |
|---|---|---|---|
| 1 | 后端单测 | `cd backend-java && mvn -o test` | **> 1411**（上轮实测 1414） |
| 2 | 后端覆盖率 | `cd backend-java && mvn -o verify` | JaCoCo BUNDLE ≥ 82% |
| 3 | 前端单测 | `cd frontend && npm run test:run` | **> 382** |
| 4 | 类型检查 | `cd frontend && npx tsc --noEmit` | 0 errors |
| 5 | E2E | `cd frontend && npx playwright test` | ≥ 135（当前实测 134，`collab-crdt` 为既有遗留，需在交付里单列说明） |
| 6 | Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed / 1 skipped |
| 7 | 备份演练 | `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| 8 | 管理员鉴权 | `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 |
| 9 | 越权回归 | `python3 scripts/tenant-isolation-e2e-verify.py` | **全 PASS（本批新增）** |
| 10 | 一键冒烟 | `python3 scripts/smoke.py` | 11 / 11 |

**提交约定（强制）**：
- 当前工作区已有 **25+ 个 modified 后端文件**（上一轮 T6 改动）+ `PHASE95_T6_FINAL_TASKS.md`、
  `PHASE95_T6_FINAL_PROMPT.md` 两个 untracked 文件，**均未 commit**。
- **不得自行 commit / push。** 交付报告里写明"commit & push：待授权"，由用户决定。
- 交付必须包含 `git status --short` 全文与 `git log --oneline -3`。

---

### R4-4（P1）把红线第 10 条真正写入索引

在 `PHASE95_103_INDEX.md` 第 9 条（`:82-86`）之后、"## 五、每批完成定义（DoD）"（`:88`）之前插入：

```markdown
10. **角色鉴权测试通过 ≠ 租户隔离有效** —— 必须存在**独立的跨租户测试**
    （两个租户的 token 交叉访问对方资源，断言 403/404 或空列表）。只测"同租户内
    admin vs user"的脚本测不出跨租户越权。
    反面案例（PHASE95 T6）：`admin-authz-e2e-verify` 18/18 全绿，
    但 `assignRole` 可跨租户赋角色无人发现。
```

同时把门禁表第 58 行 `| 越权回归 | python3 scripts/tenant-isolation-e2e-verify.py | 全 PASS（PHASE95 T7） |`
的标注更新为 **已接线 + 实测条数**（如"全 PASS（15/15，已接线）"）。

> 上一轮报告声称"已添加第 10 条"，实测索引里没有。这一条按红线第 6 条处理：
> 文档与代码不符时以代码为准并回报差异。

---

### R4-5（P0/P1）T7 未接线项收口

以下每一项都必须在 R4-1 的矩阵里**有对应断言行**，否则视为"改了但没人验证"。

#### 第 1 项（P0）AI 会话可跨租户命中

- `AgentService.java:136-140` 的 `ensureConversation` 调用无租户版本
  `conversationRepo.findByAgentIdAndChannelIdAndUserId(agent.getId(), channelId, userId)`
  （定义 `AiConversationEntityRepository.java:24-27`，JPQL 无 `tenantId`）→ **B 的用户可命中 A 的会话**。
- 修法：`AiConversationEntityRepository` 新增带租户查询（或在既有 `@Query` 上加
  `AND c.tenantId = :tenantId`）；`ensureConversation` 增加 `tenantId` 参数 —— tenantId 已在调用链上可取：
  `AgentService.java:89` 位于 `executeInChannel(String tenantId, UUID channelId, UUID userId, String prompt)`（`:75-76`）。
- **不要**给 `AgentService` 加 tenantId 字段（该类只有构造注入的 repo/service，tenantId 走参数传递是线程安全的）。
- 同步测试：`AgentServiceTest.java:90` 的 mock 桩。

#### 第 2 项（P0）Wiki 分类树可跨租户枚举

- `WikiCategoryService.tree(UUID kbId)`（`:51-53`）无租户参数 → `WikiController.java:514`
  `GET /api/wiki/kb/{kbId}/categories` 用 B 的 token 能列出 A 的全部分类。
- 修法：改为 `tree(UUID kbId, String tenantId)`，改用**已存在**的
  `WikiCategoryRepository.java:18` `findByKnowledgeBaseIdAndTenantIdOrderBySortOrderAscCreatedAtAsc`（无需新增查询方法）。
- 调用点（改签名必须全改，否则编译断裂）：
  `WikiController.java:514`（有 `user.tenantId()` 可用）、
  `WikiCategoryServiceTest.java:45`、`WikiControllerTest.java:204`（mock 桩）。

#### 第 3 项（P0）Wiki 模板列表可跨租户枚举

- `WikiTemplateService.listTemplates(UUID kbId)`（`:60-62`）无租户参数 → `WikiController.java:638`
  `GET /api/wiki/kb/{kbId}/templates` 同理。
- 修法：改为 `listTemplates(UUID kbId, String tenantId)`，改用**已存在**的
  `WikiTemplateRepository.java:28-29` `findByKbIdAndTenantIdOrderByUpdatedAtDesc`。
- ⚠️ 带租户版排序为 `updatedAt DESC`，原无租户版 `findByKbId`（`:17`）无排序 ——
  **切换后返回顺序会变**，若测试依赖顺序，新增 `OrderByCreatedAtAsc` 变体或调整断言，不要改断言去迁就实现。
- 调用点：`WikiController.java:638`（有 `user.tenantId()` 可用）。

#### 第 4 项（P1）创建路径未校验知识库归属

- `WikiCategoryService.java:34` 与 `WikiTemplateService.java:42` 都调用无租户版本
  `knowledgeBaseService.get(kbId)` → **可在别人的知识库下建分类/模板**。
- 修法：改用 `KnowledgeBaseService.java:56-59` 的 `get(UUID id, String tenantId)`；
  两个调用方方法签名里**已有** `tenantId` 参数，直接传即可。
- 正确范式参照：`WikiPageService.java:56` `knowledgeBaseService.get(kbId, tenantId)`。

#### 第 5 项（P1）知识库读写仍在用无租户查询 + 手工比对

- `WikiController.java:140`（`getKb`）与 `:172`（`deleteKb`）调用无租户 `get(id)`，
  再靠 `:141-143` / `:173-175` 手工比对 tenantId 抛 403。
- 功能上拦得住，但属明确坏模式（漏写一行比对就越权）。改用 `get(id, user.tenantId())` 后可删掉手工比对。
- 同步 mock 桩：`WikiControllerTest.java:136` 与 `:146`（后者是跨租户断言用例，`kb.setTenantId("other-tenant")`）。

#### 第 6 项（P1）清理无租户重载

- `WikiTemplateService.getTemplate(UUID)`（`:75-78`）：**全仓 0 调用点**，直接删除。
- `WikiCategoryService.get(UUID)`（`:63-66`）：仍有 `WikiController.java:566`（`deleteCategory`）
  与 `WikiCategoryServiceTest.java:78` 调用 → 改为带租户版 `get(id, tenantId)`。
- `WikiTemplateService.deleteTemplate`（`:85`）的 `findById(id)` + 手工比对 → 改用**已存在**的
  `WikiTemplateRepository.java:21` `findByIdAndTenantId(UUID id, String tenantId)`。

---

## §3 红线（违反即打回）

1. **跨租户测试必须独立于角色鉴权测试** —— 不许拿 `admin-authz-e2e-verify` 的 18/18 代替，
   也不许把新脚本写成"同租户 admin vs user"的换皮版。
2. **必须有真实 HTTP 证据** —— 交叉矩阵逐条贴**两侧**状态码（自己能访问 + 对方被拒）。
3. **禁止恒真断言** —— 必须做"改回 `findById` → 脚本 FAIL"的对照并贴输出；
   禁止"403 或 404 都算通过"这种任何非 200 都算赢的写法。
4. **禁止 `None` 兜底** —— 拿不到租户 B 的有效 admin 凭证就 `sys.exit(1)`，
   并且必须校验 JWT 里 `tid` 与 `roles` 真的对（否则 403 只是"没权限"，不是"被隔离"）。
5. **禁止为了跑绿删测试或放宽断言** —— 发现真实越权就报出来，先红后绿。
6. **文档与代码不符时以代码为准并回报差异** —— 上一轮"已添加第 10 条"就是反例。
7. **必须报全量门禁十项与 commit hash**；**提交需用户授权，不得自行 commit/push**。

---

## §4 交付清单（九项，缺一即打回）

1. R4-1 `scripts/tenant-isolation-e2e-verify.py` **全文**（脚本必须真实存在于仓库）
2. **先红输出**（修复前，第 8/9/15 条 FAIL 的原样输出）+ **后绿输出**（修复后全 PASS）+ `exit=$?`
3. R4-2 两处可失败性对照的 FAIL/PASS 输出（含具体失败的断言行与实际状态码）
4. 租户 B 的创建方式说明（走 DB 直插还是 API；密码哈希怎么来的；清理顺序）
5. R4-5 六项修复的 `文件:行号` + 每项对应的矩阵断言编号
6. R4-4 索引红线第 10 条原文 + 门禁表第 58 行更新后的文本
7. 门禁**十项**实测数字（逐条命令 + 结果），不得出现"未重跑"
8. `git status --short` + `git log --oneline -3` + commit hash（若已授权提交）
9. **遗留项**（强制，不得省略）：跳过了哪些资源/断言及原因；`collab-crdt` E2E 遗留状态；
   `scripts/test-auth.py` 的处置建议

---

## §5 可复用（别重造）

- **脚本风格**：`scripts/admin-authz-e2e-verify.py` 的 `login()` / `http()` / `fetch_json()` /
  `get_user_id()` / `atexit` 清理 / `sys.exit(0 if passed == total else 1)` 可直接照搬。
- **租户校验范式**：`compliance/UserDataErasureService.java` 的 `requireUserInTenant`；
  端点范式 `AttachmentController.java:99,169`（`storageKey.startsWith(tenantId + "/")`）、
  `ProjectBoardController.java:131-135,396-400`。
- **已有带租户查询**（优先复用，别新增）：`UserRepository.findByIdAndTenantId`、
  `WikiCategoryRepository.java:18`、`WikiTemplateRepository.java:21,28-29`、
  `AiConversationEntityRepository.java:30,32`、`KnowledgeBaseService.java:56-59`。
- **响应约定**：成功统一 `{"code":0,"message":"success","data":{...}}`；
  错误走 Spring `ResponseStatusException`（真实 HTTP 403/404，body 为 Spring 默认 error JSON，非 code 包装）。
- **环境事实**：JWT access TTL 15 分钟（`JwtService.java:42`）；`TenantContext` 每请求 set/clear，
  无跨请求泄漏，脚本可在同一进程交替用两个 token；`user_tenant` 关联表存在但登录不查它，
  `POST /api/admin/users/{userId}/tenants` 会触发 `checkSeatsQuota`（可能 403），脚本**不要**走这条路。

---

## §6 本批不做什么

- 不改前端，不新增业务功能，不做大范围重构。
- 不顺带处理 PHASE96+ 的事项。
- 不自行 commit / push。

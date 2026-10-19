# PHASE95 返工 5 任务书：跨租户脚本断言硬化（🔒-SaaS-P0）

> 依据：PHASE95 返工 4 交付审计（2026-10-11）—— **❌ 不通过**
> 一句话：**脚本终于存在了（上一轮最致命的问题已解决），但 11 条断言里只有约 5 条能证伪，
> 而且新查出一处会让两条断言彻底失效的假绿。本批只改脚本与报告，不动业务代码。**

> ⚠️ **本文所有 `文件:行号` 为 2026-10-11 快照，行号会漂移。开工前逐条核对，以实测为准；
> 发现不符按红线第 6 条回报差异，不要照抄。**

---

## §1 返工 4 审计判定

### 1.1 做对的（**这些不要再动**）

| 项 | 证据 |
|---|---|
| 脚本真实存在 | `scripts/tenant-isolation-e2e-verify.py`（261 行），租户 B 走 `docker exec nocobase-postgres psql` 自包含播种 + `atexit` 清理 |
| R4-5 六项**全部真改** | `AgentService.java:137-141` 带 tenantId；`AiConversationEntityRepository.java:24` 新方法（`@Query` 含 `AND c.tenantId = :tenantId`）；`WikiCategoryService.java:51-52`；`WikiTemplateService.java:60-61`；`:34`/`:42` 用 `get(kbId, tenantId)`；`WikiController.java:144,175` 用 `get(id, user.tenantId())`；第 6 项也做了（无租户 `getTemplate(UUID)` 已删，`:68,77` 用 `findByIdAndTenantId`，`WikiController.java:584` 改带租户） |
| R4-4 红线第 10 条真写入 | `PHASE95_103_INDEX.md:87-91`，门禁表 `:58` 同步更新 |
| `mvn -o test` | 1416 ✅（> 1411 基线） |

### 1.2 脚本的硬伤（本批要修的）

| 编号 | 硬伤 | 证据 |
|---|---|---|
| S1 | **第 15 条是凭空加分**：不发请求、不进 `results`、`passed += 1` 无条件执行 | `:245-248` |
| S2 | **8/9/12 只比状态码**：`check(..., 200, 200)`，B 拿到 A 的全部数据也 PASS | `:229,230,237` |
| S3 | **第 14 条 label 写 "B≠A" 却从不比较内容** | `:239` |
| S4 | **`exp_a` 从未参与判断**：`ok = b == exp_b`，"A 自己能访问"这条基线是死的 | `:212-221` |
| S5 | **资源创建失败静默消失**：`if xxx_id:` 守卫让整组断言不执行，`total` 变小但仍 `passed == total` → exit 0 | `:223,227,232,235` |
| S6 | **第 1、2 条完全重复**（同 method 同 path，仅 label 不同） | `:224,225` |
| S7 | **矩阵 15 条只做了 10 条**：缺第 3（admin 用户详情）、**第 4、5（assignRole 双向，最要命）**、第 10（huddle）、第 13（BI）；第 3 条还被 `DELETE /api/automation/{id}` 冒名顶替 | `:243` |
| S8 | **R4-2 没贴 FAIL 输出**；且"tree 改回无租户 → FAIL"在当前断言下**不可能发生**（第 8 条期望 B=200，改回后 B 仍 200） | 交付报告 |
| S9 | **门禁只报 3 项**，其余 7 项一个数字都没有（连续第二次） | 交付报告 |
| S10 | **JWT 只打印不断言** | `:162-163,175-176` |
| **S13** | **第 6/7 条（wiki kb/page 403）疑似假绿**：B 的 admin 角色**没有 ACL 策略**，`aclEnforcer` fail-closed 先抛 403，租户比对根本没执行到。详见 §2 R5-7 | `AclEnforcer.java:75-106`、`V40__wiki_acl_seed.sql:23` |

**S1、S2、S13 是本批的三个致命项。**

---

## §2 十项任务

### R5-1（P0）删掉第 15 条假断言

`:245-248` 的 `passed += 1` 必须删除。**已核实：AI 会话不存在可行的 HTTP 跨租户断言路径**——
`AgentController` 只有 `POST /api/ai/agent/execute`（`AgentController.java:28-42`），
它先查 `agentRepo.findByTenantIdAndChannelId(tenantId, channelId)`，而**没有任何创建 agent 的 HTTP 端点**
（迁移 `V26`/`V48` 也未插种子 agent → 库里默认 0 个），且**没有读取会话的 GET 端点**。

二选一（**推荐第 ① 种**）：

1. **用 Java 单测锁住**：新增 `AgentServiceTenantGuardTest`（对齐返工 4 已建的
   `AutomationRuleServiceTenantGuardTest.java` 风格），断言用**租户 B 的 tenantId** 调
   `findByAgentIdAndChannelIdAndUserIdAndTenantId` 查不到租户 A 的会话。单测要可失败（改回无租户查询会红）。
2. **显式报"未覆盖"**：脚本打印 `NOT COVERED: ai-conversation (no HTTP path)`，**不计入 passed，也不计入 total**，
   并在交付的遗留项里写明原因与推荐补法。

> 严禁保留任何形式的无条件加分。红线 1（禁止恒真断言）与红线 2（禁止静默成功）同时命中。

### R5-2（P0）8/9/12/14 四条改为内容断言

跨租户最隐蔽的形态就是"**200 但返回了对方数据**"。这四条必须解析响应体，**已核实的 data 形状**：

| 断言 | 端点 | `data` 形状 | 判据 |
|---|---|---|---|
| 8 | `GET /api/wiki/kb/{kbId}/categories` | **纯数组**（无 `total`） | `len(data) == 0` |
| 9 | `GET /api/wiki/kb/{kbId}/templates` | **纯数组**（另有顶层 `total`） | `len(data) == 0` |
| 12 | `GET /api/im/messages/pins?channelId=` | **对象** → `data.pins` | `len(data["pins"]) == 0` |
| 14 | `GET /api/admin/users` | **纯数组**，项含 `username`、`tenant_id` | 列表里**不含** A 的任何用户名；且每项 `tenant_id` 都等于 `tenant_b` |

写法要求：用 `fetch_json()` 取体，先断言 HTTP 200，再断言内容空/不含；**两者都要打印**
（如 `B→200, data=0 items`）。只比状态码的 `check(..., 200, 200)` 全部作废。

> ⚠️ **第 8/9 条的实际证伪对象要看清楚**：`categories`/`templates` 的控制器走的是
> "**先做 KB 归属检查 → 404 被 catch → 直接返回空列表**"（`WikiController.java:518-529`、`:654-664`），
> `aclEnforcer` 和 `tree()`/`listTemplates()` 的租户过滤**根本执行不到**。
> 所以这两条断言只能证明"**KB 归属检查生效**"，**证明不了 `tree()`/`listTemplates()` 的租户过滤**——
> 这也正是返工 4 声称"tree 改回无租户查询 → FAIL"却不可能发生的原因。
> 因此：`tree()` 与 `listTemplates()` 的租户过滤**必须各补一个 Service 层单测**来锁
> （用租户 B 的 tenantId 查租户 A 的 kbId，断言返回空），并做可失败性对照。
> 交付里要写清楚哪条断言锁的是哪一层，不许混为一谈。

### R5-3（P0）`check()` 启用 `exp_a`

```python
ok = (a == exp_a) and (b == exp_b)
```

A 访问自己的资源必须成功，这是证明"端点本身可用"的基线。若 A 侧都拿不到 200，B 侧的 403/404
可能只是"资源根本不存在"造成的假绿。

### R5-4（P0）资源创建失败即 `sys.exit(1)`

废除 `if rule_id_a:` / `if kb_id_a:` / `if page_id_a:` / `if channel_id_a and msg_id_a:` 这类守卫。
创建失败时打印**失败的请求路径、请求体、状态码、响应体**，然后 `sys.exit(1)`。

**已核实的三个会让你 exit 1 的坑（务必照做）**：

1. `POST /api/bi/reports` **必须带 `collectionName`**（`BiReportService.java:85`，实体 `nullable=false`）→
   缺失是 **500 不是 400**。建议体：`{"collectionName": "users", "name": "r-a", "type": "PIVOT", "config": {}}`。
2. `POST /api/im/huddle` body 需 `channelId`（**合法 UUID 字符串**，缺失 → `UUID.fromString("null")` → 500）
   与 `name`；且**必须用建频道那个用户的 token** 调（`HuddleService.java:62-67`：频道须属本租户 → 404，
   创建者须是频道成员 → 403）。`POST /api/im/channels` 已自动把创建者加为 OWNER，所以用 A 的 token 建频道再建 huddle 一定过。
3. `POST /api/admin/users` 的 `username` **全局唯一**（`UserAdminService.java:61-63` 跨租户也冲突 → 409）。
   给测试用户加随机后缀；租户 B 的种子用户名建议也带后缀，避免残留数据导致下次运行踩 `ON CONFLICT DO NOTHING` 静默不插。

### R5-5（P0）补齐第 3/4/5/10/13 条，删掉重复的第 1/2 条其一

| # | 请求 | 期望 | 依据 |
|---|---|---|---|
| 3 | B → `GET /api/admin/users/{A用户id}` | **404** | `UserAdminController.java:51-59` |
| 4 | B → `POST /api/admin/users/{A用户}/roles/{B角色}` | **404**（用户非当前租户） | `UserAdminService.java:108-130` |
| 5 | A → `POST /api/admin/users/{A用户}/roles/{B角色}` | **404**（角色非当前租户） | 同上，双向都要测 |
| 10 | B → `GET /api/im/huddle/{A的huddle}` | **404** | `ImHuddleController.java:160` → `huddleService.get(id, tenantId)` |
| 13 | B → `GET /api/bi/reports?collectionName=<A用的值>` | **200 + 空数组** | `BiReportController.java:86-93`，`listReports` 带租户过滤 |

- 第 4/5 条是**上轮唯一真修的 P0 越权点**（`assignRole` 跨租户赋角色），必须有回归断言锁住。
  `seed_tenant_b()` 已生成 `role_uuid`（`:130`）但当前被丢弃（`:167` `_, _ = seed_tenant_b()`），**接住复用**。
- 第 3 条要的是 `GET`，不是返工 4 里那个冒名顶替的 `DELETE /api/automation/{id}`。

### R5-6（P0）JWT 凭证硬断言

把 `:162-163`、`:175-176` 的"只打印"改成硬校验：

```python
if jwt_b.get("tid") != "tenant_b" or "admin" not in (b_roles or []):
    log("FATAL: tenant B credential invalid (tid/roles)"); sys.exit(1)
```

**为什么必须**：若角色行没插上，B 只有 `ROLE_USER`，此时所有 `hasRole('ADMIN')` 与 ACL 端点都会 403，
而脚本里若干条断言期望的正是 403 → **"没权限"被误当成"被隔离"**。

### R5-7（P0）根治第 6/7 条的 ACL 假绿 —— 本批新查出，优先级最高之一

**问题**：`AclEnforcer.assertCan`（`AclEnforcer.java:75-106`）只查"用户在本租户内的角色 → 该租户下的
`acl_policies`"，且默认 **fail-closed**（`app.acl.fail-open:false`; 无策略 → 抛 403）。
而租户 B 的 admin 角色是脚本运行时才插进 `roles` 表的，**`acl_policies` 里没有任何属于它的策略**
（`V40__wiki_acl_seed.sql:23` 只对迁移时已存在的 `admin` 角色播种）。

后果：B 访问 `/api/wiki/kb/{A的id}` 时，`aclEnforcer`（`WikiController.java:142-143`，在租户比对
`knowledgeBaseService.get(id, user.tenantId())`（`:144`）**之前**）直接抛 403。脚本第 6/7 条看到 403 就判 PASS
—— 但它证明的是"**B 没有 ACL 策略**"，不是"**B 被租户隔离拦住**"。
**B 去访问一个纯随机的、根本不存在的 UUID，拿到的也会是同一个 403。**

**修法（两步都要做）**：

1. **给 B 的 admin 角色补上等同 A 的 ACL 策略**：在 `seed_tenant_b()` 里，把 `tenant_default` 的
   admin 角色在 `acl_policies` 中的策略**复制**给 B 的角色（按 `V6__acl.sql` / `V40__wiki_acl_seed.sql`
   的表结构写 `INSERT ... SELECT`，字段以实测为准）；清理时一并删除。
2. **加一条"对照断言"自证**：B 访问**随机不存在的资源 ID** 时，结果**必须不同于**访问 A 的真实资源
   （例如 A 的真实资源 → 404/403，随机 ID → 另一个码），或直接断言"B 访问随机 ID 不是 403"
   以证明 403 不是 ACL 兜底产生的。若两者相同 → 该断言不具证伪力 → **判 FAIL**，不许靠它凑数。

### R5-8（P0）重做可失败性验证并贴输出

- 至少两处对照（用户侧 + 资源侧），**每处都要贴真实的 FAIL 输出**（哪条断言红、实际状态码多少），改回后复跑 PASS。
- **必须解释**返工 4 那个自相矛盾的说法：脚本第 8 条期望 B=200，把 `WikiCategoryService.tree`
  改回无租户查询后 B **仍然返回 200**，按当时断言不可能 FAIL。说清楚当时到底改了什么、看到了什么。
- 新增的内容断言（8/9/12/14）也要各做一次可失败性：人为让它们返回非空，确认脚本会红。

### R5-9（P0）补全量门禁十项

| # | 命令 | 基线（不得回退） |
|---|---|---|
| 1 | `cd backend-java && mvn -o test` | > 1411（返工 4 实测 **1416**） |
| 2 | `cd backend-java && mvn -o verify` | JaCoCo BUNDLE ≥ 82%（**当前未达标**，见下） |
| 3 | `cd frontend && npm run test:run` | > 382 |
| 4 | `cd frontend && npx tsc --noEmit` | 0 errors |
| 5 | `cd frontend && npx playwright test` | ≥ 135（实测 134，`collab-crdt` 为既有遗留，单列说明） |
| 6 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed / 1 skipped |
| 7 | `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| 8 | `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 |
| 9 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（**改造后条数会变，报真实数字**） |
| 10 | `python3 scripts/smoke.py` | 11 / 11 |

**JaCoCo**：`pom.xml` 未被修改（规则没被放宽），未达标包（notification / view）与本批改动无交集 →
倾向判为预存遗留。但按红线 3 必须给**证据**：在当前改动基础上 `git stash` 后跑一次 `mvn -o verify`
取改动前数字，与改动后对比，两份都贴。给不出就明确写"无基线证据"，不许只用"非本批引入"一句话带过。

### R5-10（P1）报告补全

返工 4 做了但**没在报告里点名**的两项，本批必须写进交付：
- R4-4：`PHASE95_103_INDEX.md:87-91` 红线第 10 条已写入，门禁表 `:58` 已更新。
- R4-5 第 6 项：无租户 `getTemplate(UUID)` 已删；`WikiTemplateService.java:68,77` 用 `findByIdAndTenantId`；
  `WikiController.java:584` 的 `categoryService.get(id, user.tenantId())` 已改带租户。

---

## §3 止停线：本批**不再改**的代码

以下已逐条核实到位，**除非发现新的真实越权，否则不要动**；若确需改动，先回报再改：

`AgentService.java:137-141`、`AiConversationEntityRepository.java:18-27`、
`WikiCategoryService.java:34,51-52`、`WikiTemplateService.java:42,60-61,68,77`、
`WikiController.java:144,175,520,584,656`、`UserAdminService.java:41-43,108-130`、
`UserAdminController.java:51-59,81,108,124,138`、`PHASE95_103_INDEX.md:87-91`。

**不改** `pom.xml`、**不放宽**任何断言、**不删除**任何测试、**不自行 commit/push**（当前 42 个文件未提交）。

---

## §4 红线

1. 禁止恒真断言（含无条件加分、只比状态码的"200 vs 200"、B 无 ACL 策略导致的全 403）
2. 禁止静默成功与静默跳过（资源创建失败必须 exit 1；覆盖不到必须显式报 NOT COVERED）
3. 数字可追溯（每个数字要能在交付物里找到产出它的代码行或命令输出）
4. 每批跑全量门禁十项
5. 变更落盘并汇报 commit hash（**提交需用户授权**）
6. 文档与代码不符以代码为准并回报差异
7. 安全改动必须有真实 HTTP 证据，两侧状态码都要贴
8. 禁止"自动生成基线即通过"
9. 不得 `None` 兜底；写操作 500 不算业务拒绝
10. 角色鉴权测试通过 ≠ 租户隔离有效

---

## §5 交付清单

1. 改造后的 `scripts/tenant-isolation-e2e-verify.py` 全文（**本批唯一应改的代码文件**）
2. 完整运行输出（每条断言的 A 侧 + B 侧状态码 + 内容判据）+ `exit=$?`
3. R5-8 的可失败性 FAIL/PASS 输出（含对返工 4 那条矛盾说法的解释）
4. R5-7 的 ACL 策略复制代码 + 对照断言输出（证明 403 不是 ACL 兜底产生的）
5. R5-1 第 15 条的处置：新单测文件 + 运行结果，或 NOT COVERED 的显式输出与说明
6. 门禁**十项**逐条命令与数字；JaCoCo 的 stash 前后对比（或明确写"无基线证据"）
7. R5-10 两项的补报说明
8. `git status --short` + `git log --oneline -3` + commit hash（若已授权提交）
9. **遗留项**（强制）：NOT COVERED 清单及原因；`collab-crdt` E2E 状态；JaCoCo 未达标包与后续计划；
   `scripts/test-auth.py` 处置建议

---

## §6 端点与请求体速查（已核实，开工前仍请扫一眼确认）

| 用途 | 端点 | 请求体 | 成功 | `data` 形状 |
|---|---|---|---|---|
| 建知识库 | `POST /api/wiki/kb` | `{"name","slug","description"}` | 200 | 对象 `data.id` |
| 建页面 | `POST /api/wiki/pages` | `{"knowledge_base_id","slug","title","content"}` | 200 | 对象 `data.id` |
| 建 automation | `POST /api/automation` | `{"name","collectionName","triggerType":"manual","triggerConfig":{},"conditions":[],"actions":[]}` | 201 | 对象 `data.id` |
| 建频道 | `POST /api/im/channels` | `{"name","type":"public"}` | — | 对象 `data.id` |
| 建消息 | `POST /api/im/messages` | `{"channelId","content","contentType":"text"}` | — | 对象 `data.id` |
| 建 huddle | `POST /api/im/huddle` | `{"channelId":"<uuid>","name"}`（**必须用建频道那个 token**） | 201 | 对象 `data.id` |
| 建 BI 报表 | `POST /api/bi/reports` | **必须含 `collectionName`**，可选 `name`/`type`/`config` | 200 | 对象 |
| 建用户 | `POST /api/admin/users` | `{"username","password","displayName"}`（username **全局唯一**） | 201 | 对象 `data.id` |
| 列用户 | `GET /api/admin/users` | — | 200 | **纯数组**（`username`、`tenant_id`） |
| 分类列表 | `GET /api/wiki/kb/{kbId}/categories` | — | 200 | **纯数组** |
| 模板列表 | `GET /api/wiki/kb/{kbId}/templates` | — | 200 | **纯数组**（另有顶层 `total`） |
| 置顶列表 | `GET /api/im/messages/pins?channelId=` | — | 200 | **对象** → `data.pins` |
| BI 列表 | `GET /api/bi/reports?collectionName=` | — | 200 | **纯数组** |

**跨租户真实行为（已核实）**：categories / templates 走的是"KB 归属检查 → 404 被 catch → 返回空列表"
（`WikiController.java:518-529`、`:654-664`），所以是 **200 + 空**，不是 403。

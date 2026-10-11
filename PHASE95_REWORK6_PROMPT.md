# PHASE95 返工 6 提示词：跨租户脚本收口

> 使用方式：把下面 `BEGIN PROMPT` 与 `END PROMPT` 之间的**全文**投喂给 Kilo Code + GLM-5.3。
> 配套任务书：`PHASE95_REWORK6_TASKS.md`（同目录，可对照阅读，但提示词本身已自包含）。

---

BEGIN PROMPT

# 任务：PHASE95 返工 6 —— 收口（挤干断言水分 + 补齐证据 + 回滚越权的状态宣告）

## 0. 背景

你在 `/home/who/multistack-project`（DBCool）。R5 你交付了 `scripts/tenant-isolation-e2e-verify.py`
并报"14/14 PASS"，审计判定 **❌ 不通过**。

**先说你做对的（不要回退）**：内容断言 8/9/12/14 真做了（`check_categories`/`check_templates`/`check_pins`
走 `data.pins`/`check_reports`/`check_users` 逐项比 `tenant_id`，`:357-407`）；`exp_a` 启用了（`:299-301`）；
七类资源创建失败即 `sys.exit(1)`（`:214-279`）；JWT 硬断言（`:199-201`）；ACL 策略复制（`:149-155`）；
假断言 `passed += 1` 已删除；`mvn -o test` 1418 ✅。**这些都是实打实的进步。**

**不通过的原因**（11 条，本批逐一收口）：14 条里有 2 条注水重复断言、1 条半截断言（只打印不判断）、
`assignRole` 缺正向基线、可失败性没做、门禁十项只报 4 项、JaCoCo 证据没贴、
以及你**在未审计且门禁 FAIL 的情况下自行把索引批次状态改成了"✅ 已完成"**。

> ⚠️ 所有 `文件:行号` 是 2026-10-11 快照，行号会漂移，开工前逐条核对，以实测为准。

## 1. 八项任务

### R6-1（P0）删掉两条注水断言

- 删第 1/2 条其一（`:332`、`:335` 是同 method 同 path 同期望，都是 `GET /api/automation/{rule_id_a}` A=200 B=404）。
- 删第 4/5 条其一（`:343`、`:347` 打的是同一个 URL `POST /api/admin/users/{user_id_a}/roles/{role_id_b}`，
  而 `check` 内部本就会用 A、B 两个 token 各打一次，所以这两条是同一件事）。
- **删掉 `:329` 那句注释** `"Items 1-2 use different HTTP methods to avoid data conflicts"` —— 代码里两个都是 GET，
  注释与代码矛盾。
- 矩阵序号重排，**报真实条数**；交付里说明"删了哪两条、为什么"。

### R6-2（P0）补 `assignRole` 正向基线

新增一条：**A 给 A 的用户赋 A 租户的角色 → 成功**。A 角色 id 用脚本已有的同类查询取
（`:130-136` 的 `SELECT id FROM roles WHERE name='admin' AND tenant_id='tenant_default' LIMIT 1`，接住复用）。

```python
check("role-assign baseline A→A / B→A", "POST",
      f"/api/admin/users/{user_id_a}/roles/{role_id_a}", 200, 404)
```

`assignRole` 成功返回 `Map.of("code",0,...)` → HTTP 200；B 侧期望 404（用户非 B 租户）。

**为什么必须**：没有这条，"A 赋 B 角色 → 404" 无法区分"租户校验拦住"与"URL 拼错/权限不足/参数错误"。

### R6-3（P0）对照断言升格为真断言（**方向已修正，按这个做**）

> 上一轮任务书要求"随机 ID 与真实资源结果相同 → 判 FAIL"。**这个方向是错的**：补了 ACL 策略后，
> B 访问 A 的真实 KB 是 **404**、访问随机 KB 也是 **404**，两者本就相同，这是正常且正确的，不该判 FAIL。
> 真正能证明隔离成立的是 **A 侧对照**：A 访问自己的真实 KB → 200、访问随机 KB → 404，
> 证明"资源确实存在于 A 租户"，从而让 B 的 404 归因于租户隔离而非"资源不存在"。

```python
random_kb_id = _uuid_mod.uuid4()
check("control random KB (A→404 both)", "GET",
      f"/api/wiki/kb/{random_kb_id}", 404, 404)
```

- 计入 `total`/`passed`，不再只 `log`。
- **删掉 `:429` 那行硬编码** `"different from A's KB 403)"`（第 6 条现在期望 404，文本也是错的）。
- B 侧随机 ID 的码可继续打印作诊断，但**不参与判定**。

### R6-4（P0）可失败性重做 + 单测改造

**脚本侧**（上一轮完全没做）：
1. 把 `UserAdminService.java:108`（或 automation/wiki 任一处）的 `findByIdAndTenantId` 改回 `findById`
2. 重跑 → **贴 FAIL 输出**（哪条红、实际状态码）
3. 改回 → 重跑 PASS，**贴输出**
4. 内容断言（8/9/12/14）各做一次：人为让它们返回非空，确认脚本会红

**单测侧**（`AgentServiceTenantGuardTest.java`）：
- 第 1 个测试（`verify` 确认 `tenantId` 作为参数传入，`:79-80`）**保留**。
- 第 2 个测试（`:85-116`）**是 mock 自证**：`when(conversationRepo.findByAgentIdAndChannelIdAndUserIdAndTenantId(
  ..., "tenant_b")).thenReturn(Optional.empty())` 决定了一切，改坏实现也不会红。三选一：
  1. **推荐**：手写 `AiConversationEntityRepository` 的**假实现**（不用 Mockito 的 `when`），内部存两条会话
     （tenant_a、tenant_b）并按 `tenantId` 真实过滤；调 `ensureConversation` 传 `tenant_b`，
     断言**不返回** tenant_a 的会话 —— 这样结果由被测代码决定。
  2. 改为 `@DataJpaTest` + 真实仓储（若项目测试已有可用 DB 配置）。
  3. 直接删除，并说明"该场景无法在不 mock 的前提下验证，故不假装覆盖"。
- 无论选哪种，**贴出 FAIL 输出**（把实现改回无租户查询，确认测试变红）。

### R6-5（P0）补跑剩余 7 项门禁（上一轮只报了 4 项，连续第三次）

| # | 命令 | 基线（不得回退） |
|---|---|---|
| 1 | `cd backend-java && mvn -o test` | > 1411（R5 实测 1418） |
| 2 | `cd backend-java && mvn -o verify` | JaCoCo ≥ 82%（当前 61% FAIL，见 R6-6） |
| 3 | `cd frontend && npm run test:run` | > 382 |
| 4 | `cd frontend && npx tsc --noEmit` | 0 errors |
| 5 | `cd frontend && npx playwright test` | ≥ 135（实测 134，`collab-crdt` 既有遗留，单列） |
| 6 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed / 1 skipped |
| 7 | `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| 8 | `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 |
| 9 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（报真实条数） |
| 10 | `python3 scripts/smoke.py` | 11 / 11 |

### R6-6（P0）JaCoCo 给证据（**不要求本批达标**）

阈值 82% 来自 Phase 55，与本批改动无交集 → 判为**遗留**，不要求拉到 82%。
但必须证明"非本批引入"：`git stash push` → `mvn -o verify` 记数字 → `git stash pop` → `mvn -o verify` 记数字，
**两份输出都贴**。给不出就明确写"无基线证据"，不许只用一句 "pre-existing issue" 带过。

### R6-7（P0）回滚索引的"✅ 已完成"

`PHASE95_103_INDEX.md:36` 当前是 `| **PHASE95** ✅**已完成** | ... | **Round 1 已返工 R5**：T1-14 全验收通过... |`。
改回**不带完成结论**的客观状态，例如：

```
| **PHASE95** ⚠️**返工 6 进行中** | 授权与多租户隔离基线 | P0-1、P0-2 | R5 已交付待审计：跨租户脚本已落地（内容断言、exp_a、创建失败退出、JWT 硬断言、ACL 策略复制）；审计发现 2 条重复断言待删、assignRole 缺正向基线、门禁十项仅报 4 项、`mvn -o verify` 因 JaCoCo 61% FAIL。返工 6 见 **`PHASE95_REWORK6_TASKS.md` / `PHASE95_REWORK6_PROMPT.md`** |
```

**红线第 10 条（`:87-91`）与门禁表第 58 行必须原样保留。** 以后不得在审计通过前把批次标成"已完成"。

### R6-8（P1）报告规范

- 只申报**本批真实改过**的文件（脚本、`AgentServiceTenantGuardTest.java`、索引）。
  **不得**再把返工 4 的 41 个存量 modified 当成 R5/R6 产出重报。
- **不得**描述不存在的改动（上一轮的 `removeItem 抛异常` 全仓 grep **0 结果**）。
- commit hash 必须是本批的；未提交就写"commit & push：**待授权**"，附 `git status --short` + `git log --oneline -3`。

## 2. 顺带修（P1）

脚本只清理租户 B，**不清理租户 A 创建的 7 类资源**（kb/page/rule/channel/msg/huddle/report/user），
每跑一次就在 `tenant_default` 留一批垃圾，会污染 `mvn -o test` 与 `smoke.py`。
补上 A 侧资源清理（走 DELETE 端点，失败打印警告），或显式说明"不清理及理由"。

## 3. 止停线

本批**只改这三个文件**：`scripts/tenant-isolation-e2e-verify.py`、
`backend-java/src/test/java/com/nocobase/ai/AgentServiceTenantGuardTest.java`、
`PHASE95_103_INDEX.md`（**仅第 36 行状态格**）。

**不改业务代码、不改 `pom.xml`、不放宽断言、不删有效测试。**
若发现必须改业务代码才能验证，先回报，不擅自改。

## 4. 红线

1. 禁止恒真断言（重复断言充数 / mock 自证 / 只打印不判断都算）
2. 禁止静默成功与静默跳过
3. 数字可追溯
4. 每批跑全量门禁十项
5. 变更落盘并汇报 commit hash（**提交需用户授权**）
6. 文档与代码不符以代码为准并回报差异（注释与代码矛盾也算）
7. 安全改动必须有真实 HTTP 证据，两侧状态码都贴
8. 禁止"自动生成基线即通过"
9. 不得 `None` 兜底；写操作 500 不算业务拒绝
10. 角色鉴权测试通过 ≠ 租户隔离有效

## 5. 交付清单

1. 改造后脚本全文 + 完整运行输出 + `exit=$?`（报真实条数）
2. R6-1 说明：删了哪两条、为什么
3. R6-2 正向基线输出（A→200 与 B→404 成对）
4. R6-3 对照断言输出（随机 ID 计入判定）；确认 `:429` 硬编码已删
5. R6-4 脚本侧 FAIL/PASS 输出 + 单测处置结论与 FAIL 输出
6. 门禁**十项**逐条命令与数字
7. R6-6 JaCoCo stash 前后两份输出（或写"无基线证据"）
8. R6-7 索引回滚后文本 + `git diff -- PHASE95_103_INDEX.md`（证明只动状态格）
9. `git status --short` + `git log --oneline -3` + 本批 commit hash（若已授权）
10. **遗留项**：A 侧资源清理结论；`collab-crdt` E2E 状态；JaCoCo 未达标包与计划；`scripts/test-auth.py` 处置建议

END PROMPT

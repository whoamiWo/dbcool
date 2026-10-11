# PHASE95 返工 6 任务书：跨租户脚本收口（🔒-SaaS-P0）

> 依据：PHASE95 R5 交付审计（2026-10-11）—— **❌ 不通过**
> 一句话：**脚本质量已经到位了（内容断言、exp_a、创建失败即退出、JWT 硬断言、ACL 复制全部真做），
> 剩下的是挤干水分、补齐证据、回滚越权的状态宣告。本批是收口批，不扩展覆盖面。**

> ⚠️ **本文所有 `文件:行号` 为 2026-10-11 快照，行号会漂移。开工前逐条核对，以实测为准；
> 发现不符按红线第 6 条回报差异，不要照抄。**

---

## §1 R5 审计判定

### 1.1 R5 做对的（**这些不得回退**）

| 项 | 证据（`scripts/tenant-isolation-e2e-verify.py`） |
|---|---|
| 内容断言 8/9/12/14 | `check_categories` / `check_templates` / `check_pins`（走 `data.pins`）/ `check_reports` / `check_users`（逐项比 `tenant_id`），`:357-407` |
| `exp_a` 启用 | `:299-301` `ok = ok_a and ok_b` |
| 创建失败即退出 | kb/page/rule/channel/msg/huddle/report/user 七类资源全有守卫 + 失败响应体打印，`:214-279` |
| 补齐 3/10/13 | `:338`、`:373`、`:393` |
| JWT 硬断言 | `:199-201` |
| R5-7 ACL 策略复制 | `:149-155` `INSERT ... SELECT` 从 `tenant_default` 的 admin 复制；清理加 `acl_policies`，`:165` |
| R5-1 假断言已删除 | 脚本中已无 `passed += 1` |
| `mvn -o test` | 1418 ✅ |

### 1.2 本批要修的

| 编号 | 问题 | 证据 |
|---|---|---|
| N1 | **第 1、2 条完全重复**（同 method 同 path 同期望），且 `:329` 注释写"use different HTTP methods"与代码矛盾（两个都是 GET） | `:332`、`:335`、`:329` |
| N2 | **第 4、5 条是同一条断言算两次**（同 URL，`check` 内部本就会用 A/B 两个 token 各打一次） | `:343`、`:347` |
| N3 | **`assignRole` 缺正向基线**：只断言 404，URL 拼错/权限/参数错误同样产生 404 | `:343,347` |
| N4 | **R5-7 对照断言只打印不判断**；`:429` 硬编码 `"A's KB 403)"` 而第 6 条现在期望 404 | `:411-413`、`:429` |
| N5 | **R5-8 脚本侧可失败性没做**；单测的"改错会 FAIL"没贴输出 | 交付报告 |
| N6 | **`AgentServiceTenantGuardTest` 第 2 个测试是 mock 自证**：结果由 `when(...).thenReturn(Optional.empty())` 决定，改坏代码也不会红 | `AgentServiceTenantGuardTest.java:95-97`、`:115` |
| N7 | **门禁只报 4 项、缺 7 项**（连续第三次） | 交付报告 |
| N8 | **`mvn -o verify` BUILD FAILURE**（JaCoCo 61%），stash 前后对比**没贴** | 交付报告 |
| N9 | **自行把索引批次状态改成"✅ 已完成"**（未审计、门禁 FAIL 时） | `PHASE95_103_INDEX.md:36` |
| N10 | **报告"变更文件"失真**：列的 Java 文件全是返工 4 的存量改动；`removeItem 抛异常` 全仓 grep **0 结果**，不存在 | `git status` / grep |
| N11 | **无新 commit**：给的 `51756dd` 是返工 4 的 hash，本批未提交且未写"待授权" | `git log` |

---

## §2 八项任务

### R6-1（P0）删掉两条注水断言

- 删第 1/2 条其一（保留一条 `GET /api/automation/{rule_id_a}` A=200 B=404 即可）。
- 删第 4/5 条其一（两条打的是同一个 URL `POST /api/admin/users/{user_id_a}/roles/{role_id_b}`）。
- **删掉 `:329` 那句注释** `"Items 1-2 use different HTTP methods to avoid data conflicts"` —— 代码里两者都是 GET，注释与代码矛盾（红线 6）。
- 矩阵序号重排，**报真实条数**（预期：12 条 + 1 条正向基线 + 1 条对照断言 = 14 条，但每条都是独立的）。
- 交付里必须说明"删了哪两条、为什么"，不许只报总数。

### R6-2（P0）补 `assignRole` 正向基线

新增一条断言：**A 给 A 的用户赋 A 租户的角色 → 成功**。

```python
# A 租户 admin 角色 id（脚本 :130-136 已有同类查询，接住复用即可）
_, a_role_id_str, _ = db_sql(
    "SELECT id FROM roles WHERE name='admin' AND tenant_id='tenant_default' LIMIT 1")
role_id_a = (a_role_id_str or "").strip()
if not role_id_a:
    log("FATAL: could not resolve tenant_default admin role"); sys.exit(1)

# 正向基线（A→成功）与跨租户（B→404）在同一条 check 里成对验证
check("role-assign baseline A→A / B→A", "POST",
      f"/api/admin/users/{user_id_a}/roles/{role_id_a}", 200, 404)
```

`UserAdminController.assignRole` 成功返回 `Map.of("code", 0, ...)` → **HTTP 200**。
B 侧期望 404（用户非 B 租户）。

**为什么必须**：没有这条，"A 赋 B 角色 → 404" 无法区分"租户校验拦住"与"URL 拼错/权限不足/参数错误"。
有了"A 赋 A 角色 → 200"，404 的归因才被锁定到租户校验。

### R6-3（P0）对照断言升格为真断言（**方向修正，见下**）

> **修正说明**：上一轮任务书要求"随机 ID 与真实资源结果相同 → 判 FAIL"。补了 ACL 策略后，
> B 访问 A 的真实 KB 是 **404**，访问随机 KB 也是 **404** —— 两者本就相同，这是**正常且正确**的，
> 不该判 FAIL。真正能证明隔离成立的是 **A 侧对照**：A 访问自己的真实 KB → 200，访问随机 KB → 404，
> 证明"资源确实存在于 A 租户"，从而让 B 的 404 归因于租户隔离而非"资源不存在"。

改为：

```python
random_kb_id = _uuid_mod.uuid4()
check("control random KB (A→404 both)", "GET",
      f"/api/wiki/kb/{random_kb_id}", 404, 404)
```

- 计入 `total`/`passed`，不再只是 `log`。
- **删掉 `:429` 那行硬编码的** `"different from A's KB 403)"`（第 6 条现在期望 404，文本也是错的）。
- B 侧随机 ID 的码可以继续打印作为诊断信息，但**不参与判定**。

### R6-4（P0）可失败性重做 + 单测改造

**脚本侧**（上一轮完全没做）：
1. 把 `UserAdminService.java:108`（或 automation/wiki 任一处）的 `findByIdAndTenantId` 改回 `findById`
2. 重跑脚本 → **贴 FAIL 输出**（哪条红、实际状态码）
3. 改回 → 重跑 PASS，**贴输出**
4. 内容断言（8/9/12/14）也要各做一次：人为让它们返回非空，确认脚本会红

**单测侧**（`AgentServiceTenantGuardTest.java`）：
- 第 1 个测试（`verify` 确认 `tenantId` 作为参数传入，`:79-80`）**保留**，它有价值。
- 第 2 个测试（`:85-116`）**是 mock 自证**：`when(conversationRepo.findByAgentIdAndChannelIdAndUserIdAndTenantId(..., "tenant_b")).thenReturn(Optional.empty())`
  决定了一切，改坏实现也不会红。三选一：
  1. **推荐**：手写 `AiConversationEntityRepository` 的**假实现**（不用 Mockito 的 `when`），
     内部存两条会话（tenant_a、tenant_b），按 `tenantId` 真实过滤；然后调 `ensureConversation`
     传 `tenant_b`，断言**不返回** tenant_a 的会话。这样结果由被测代码决定。
  2. 改为 `@DataJpaTest` + 真实仓储（若项目测试已有可用 DB 配置）。
  3. 直接删除，并在交付里说明"该场景无法在不 mock 的前提下验证，故不假装覆盖"。
- 无论选哪种，**贴出 FAIL 输出**（把实现改回无租户查询，确认测试变红）。

### R6-5（P0）补跑剩余 7 项门禁

上一轮只报了 mvn test / mvn verify / tenant 脚本 / 单测。本批**十项逐条报数**：

| # | 命令 | 基线（不得回退） |
|---|---|---|
| 1 | `cd backend-java && mvn -o test` | > 1411（R5 实测 **1418**） |
| 2 | `cd backend-java && mvn -o verify` | JaCoCo ≥ 82%（**当前 61% FAIL**，见 R6-6） |
| 3 | `cd frontend && npm run test:run` | > 382 |
| 4 | `cd frontend && npx tsc --noEmit` | 0 errors |
| 5 | `cd frontend && npx playwright test` | ≥ 135（实测 134，`collab-crdt` 既有遗留，单列说明） |
| 6 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed / 1 skipped |
| 7 | `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| 8 | `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 |
| 9 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（报真实条数） |
| 10 | `python3 scripts/smoke.py` | 11 / 11 |

### R6-6（P0）JaCoCo 给证据（**不要求本批达标**）

阈值 82% 来自 Phase 55，与本批改动无交集 —— 判为**遗留**，不要求本批拉到 82%。
但按红线 3 必须证明"非本批引入"：

```bash
# 1) 改动前基线
git stash push                      # 或 git stash -u 含未跟踪文件
cd backend-java && mvn -o verify    # 记录 JaCoCo BUNDLE 数字
git stash pop
# 2) 改动后
cd backend-java && mvn -o verify    # 记录 JaCoCo BUNDLE 数字
```

**两份输出都要贴**。给不出就明确写"无基线证据"，不许只用一句"pre-existing issue"带过。

### R6-7（P0）回滚索引的"✅ 已完成"

`PHASE95_103_INDEX.md:36` 当前是：

```
| **PHASE95** ✅**已完成** | ... | **Round 1 已返工 R5**：T1-14 全验收通过（14/14 E2E + 1418 单元测试）... |
```

改回**不带完成结论**的客观状态，例如：

```
| **PHASE95** ⚠️**返工 6 进行中** | 授权与多租户隔离基线 | P0-1、P0-2 | R5 已交付待审计：跨租户脚本 `scripts/tenant-isolation-e2e-verify.py` 已落地（内容断言、exp_a、创建失败退出、JWT 硬断言、ACL 策略复制）；审计发现 2 条重复断言待删、assignRole 缺正向基线、门禁十项仅报 4 项、`mvn -o verify` 因 JaCoCo 61% FAIL。返工 6 见 **`PHASE95_REWORK6_TASKS.md` / `PHASE95_REWORK6_PROMPT.md`** |
```

- **红线第 10 条（`:87-91`）与门禁表第 58 行必须原样保留**，不得误伤。
- 判定权不在执行方：以后**不得**在审计通过前把批次标成"已完成"。

### R6-8（P1）报告规范

- 只申报**本批真实改过**的文件（`scripts/tenant-isolation-e2e-verify.py`、
  `AgentServiceTenantGuardTest.java`、`PHASE95_103_INDEX.md`）。
  **不得**再把返工 4 的 41 个存量 modified 当成 R5/R6 产出重报。
- **不得**描述不存在的改动（上一轮的 `removeItem 抛异常` 全仓 grep 0 结果）。
- commit hash 必须是**本批的**；未提交就写"commit & push：**待授权**"，并附
  `git status --short` 与 `git log --oneline -3`。

---

## §3 顺带修（P1，非阻塞但请处理）

**脚本只清理租户 B，不清理租户 A 创建的 7 类资源**（kb/page/rule/channel/msg/huddle/report/user）。
每跑一次就在 `tenant_default` 里留一批垃圾，会污染 `mvn -o test` 与 `smoke.py`。
请补上 A 侧资源的清理（走 DELETE 端点，失败打印警告），或在交付里显式说明"不清理及理由"。

---

## §4 红线

1. 禁止恒真断言（含重复断言充数、mock 自证、只打印不判断）
2. 禁止静默成功与静默跳过
3. 数字可追溯（每个数字要有产出它的代码行或命令输出）
4. 每批跑全量门禁十项
5. 变更落盘并汇报 commit hash（**提交需用户授权**）
6. 文档与代码不符以代码为准并回报差异（注释与代码矛盾也算）
7. 安全改动必须有真实 HTTP 证据，两侧状态码都贴
8. 禁止"自动生成基线即通过"
9. 不得 `None` 兜底；写操作 500 不算业务拒绝
10. 角色鉴权测试通过 ≠ 租户隔离有效

---

## §5 交付清单

1. 改造后的 `scripts/tenant-isolation-e2e-verify.py` 全文 + 完整运行输出 + `exit=$?`（报真实条数）
2. R6-1 说明：删了哪两条、为什么
3. R6-2 正向基线断言的输出（A→200 与 B→404 成对出现）
4. R6-3 对照断言输出（随机 ID 计入判定），确认 `:429` 硬编码已删
5. R6-4 脚本侧 FAIL/PASS 输出 + `AgentServiceTenantGuardTest` 处置结论（改造方式或删除理由）+ 其 FAIL 输出
6. 门禁**十项**逐条命令与数字
7. R6-6 JaCoCo `git stash` 前后两份输出（或明确写"无基线证据"）
8. R6-7 索引回滚后的第 36 行文本 + `git diff -- PHASE95_103_INDEX.md`（证明只动了状态格）
9. `git status --short` + `git log --oneline -3` + 本批 commit hash（若已授权）
10. **遗留项**：A 侧资源清理结论；`collab-crdt` E2E 状态；JaCoCo 未达标包与后续计划；`scripts/test-auth.py` 处置建议

---

## §6 止停线

本批**只改这三个文件**：
`scripts/tenant-isolation-e2e-verify.py`、
`backend-java/src/test/java/com/nocobase/ai/AgentServiceTenantGuardTest.java`、
`PHASE95_103_INDEX.md`（**仅第 36 行状态格**）。

**不改业务代码**（R5 未新增任何业务代码改动，这是对的，请保持）、**不改 `pom.xml`**、
**不放宽任何断言**、**不删有效测试**。若发现必须改业务代码才能验证，先回报，不擅自改。

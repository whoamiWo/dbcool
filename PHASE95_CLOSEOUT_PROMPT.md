# PHASE95 收口提示词：补证据 + JaCoCo 遗留登记 + 转 PHASE96

> 使用方式：把下面 `BEGIN PROMPT` 与 `END PROMPT` 之间的**全文**投喂给 Kilo Code + GLM-5.3。
> 配套任务书：`PHASE95_CLOSEOUT_TASKS.md`（同目录，可对照阅读，但提示词本身已自包含）。

---

BEGIN PROMPT

# 任务：PHASE95 收口 —— 补齐证据、登记遗留、交接 PHASE96

## 0. 背景

你在 `/home/who/multistack-project`（DBCool）。返工 6 审计结论：**代码与脚本层面已经达标，缺的是证据。**

**已通过、不要动**：R6-1 删掉了两条注水断言（现矩阵 14 条、路径各不相同）；R6-2 补了 `assignRole`
正向基线（`:344`）；R6-3 对照断言已升格为真断言（`:419`）、硬编码文本已删；R6-4 mock 自证的单测已删除
（保留有价值的第 1 个）；R6-7 索引已回滚为"返工 6 进行中"。

**还缺三类证据**：① 门禁 8 项零数字（连续第四次，只报了 mvn 1418 和 mvn verify FAIL，
其余写"已验证可用"）；② 可失败性没贴输出且做法不符；③ JaCoCo 的 stash 前后两份输出没贴（连续第二次）。

**用户已裁决的 JaCoCo 方案（A）**：不动阈值、不改 `pom.xml`，61% 记为 PHASE95 遗留、转 PHASE102 立项，
PHASE95 在补证据通过后收口。

> ⚠️ 所有 `文件:行号` 是 2026-10-11 快照，行号会漂移，开工前逐条核对，以实测为准。

## 1. 最重要的一条：顺序约束

**C4（改索引收口）的前置条件是 C1、C2、C3 三份输出已附在交付里。**
证据不齐就改状态 = 又一次"先宣告完成、后补票"，会被直接打回。**先跑门禁贴输出，最后才动索引。**

## 2. 六项任务

### C1（P0）补齐 9 项门禁数字 + 归档输出

```bash
cd frontend && npm run test:run                                    # > 382
cd frontend && npx tsc --noEmit                                    # 0 errors
cd frontend && npx playwright test                                 # ≥ 135（collab-crdt 单列说明）
cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q    # 48 passed / 1 skipped
python3 scripts/backup-e2e-verify.py                               # 29 / 29
python3 scripts/admin-authz-e2e-verify.py                          # 18 / 18
python3 scripts/smoke.py                                           # 11 / 11
python3 scripts/tenant-isolation-e2e-verify.py                     # 报真实 N/N（脚本现为 14 条）
cd backend-java && mvn -o test                                     # > 1411（实测 1418）
```

**禁止**"已验证可用""均已通过""无异常"这类**无数字**表述——出现即判该条未交付。

**并把输出归档成文件**（新增要求，以后每批都这么做）：
```bash
mkdir -p reports
{ 上面每条命令与其输出 } > reports/gate-95-closeout.txt 2>&1
```
连续四批漏报的根因是"跑完凭记忆写报告"，落成文件后审计方能直接读原始输出。

### C2（P0）可失败性按指定做法重做并贴输出

```bash
# 1) 把 UserAdminService.java:108 的 userRepository.findByIdAndTenantId(userId, tenantId)
#    临时改回 userRepository.findById(userId)
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 FAIL —— 贴完整输出（哪条红、实际状态码）
# 2) 改回 findByIdAndTenantId
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 PASS —— 贴完整输出
```

上一轮"改 content assertion 后会 FAIL"**既无输出、也不是指定做法**，不能作为证据。
内容断言（8/9/12/14）也各做一次：人为让它们返回非空，确认脚本会红。

### C3（P0）JaCoCo 贴改动前后两份输出

```bash
git stash push                      # 含未跟踪文件时用 git stash -u
cd backend-java && mvn -o verify    # 记录 JaCoCo BUNDLE 数字（改动前基线）
cd .. && git stash pop
cd backend-java && mvn -o verify    # 记录 JaCoCo BUNDLE 数字（改动后当前值）
```

**两份输出都贴**。给不出就明确写"无基线证据"，不许只用一句 "pre-existing issue" 带过。

### C4（P0）索引登记（前置：C1/C2/C3 输出已附在交付中）

**不动阈值、不改 `pom.xml`**。只改三处（行号以实测为准）：

**① `PHASE95_103_INDEX.md:51`（JaCoCo 行）：**
```
| 后端覆盖率 | `cd backend-java && mvn -o verify` | JaCoCo BUNDLE ≥ 82%（**当前 61%，PHASE95 已按用户裁决登记为遗留，转 PHASE102**） |
```

**② `:36`（PHASE95 批次一览行）：**
```
| **PHASE95** ✅**已收口** | 授权与多租户隔离基线 | P0-1、P0-2 | R6 完成：跨租户隔离脚本 `scripts/tenant-isolation-e2e-verify.py` 14 条独立断言（含 assignRole 正向基线、随机 ID 对照断言、8/9/12/14 内容断言、JWT 硬断言、租户 B ACL 策略复制）；`AgentServiceTenantGuardTest` 锁住 AI 会话显式 tenantId。⚠️ **遗留**：`mvn -o verify` 因 JaCoCo 61% < 82% FAIL —— 已用 `git stash` 前后两次构建对比证明非 PHASE95 引入，用户裁决按遗留处理，转 **PHASE102** 立项（目标：notification 0.62→0.90、view 0.88→0.97，两包达标后再看 BUNDLE 是否回到 82%）。本批 DoD 第 2 条（全量门禁）在覆盖率一项上由用户明确豁免，其余九项全部 PASS。 |
```

**③ `:43`（PHASE102 行）交付物追加**：
```
| **PHASE102** | 遗留 P0 与容量基线 | P1 | 钉钉登录 405、Huddle 信令 Redis Pub/Sub 外置、去掉 `sleep` 的真实压测并测出拐点、**JaCoCo 覆盖率回归 82%（PHASE95 遗留：notification 0.62→0.90、view 0.88→0.97）** |
```

**红线**：
- **红线第 10 条（`:87-91`）与门禁表 `:58`（越权回归）必须原样保留**，不得误伤。
- 状态格必须是"已收口 **+ caveat**"，**不得**写成不带覆盖率说明的"已完成"。
- 覆盖率遗留要写**可验证的目标**（两个包的具体数值），不能只写"覆盖率回归 82%"——那是空头支票。
- 交付必须附 `git diff -- PHASE95_103_INDEX.md`，证明只动了这三处。

### C5（P1）清理脚本在 `tenant_default` 留下的资源

脚本每次运行会在默认租户下留 8 类资源，污染 `mvn -o test` 与 `smoke.py`。已核实的删除能力：

| 资源 | 清理方式 | 权限 | 成功码 |
|---|---|---|---|
| 知识库 | `DELETE /api/wiki/kb/{id}`（`WikiController.java:167-180`） | `isAuthenticated()` + ACL | 200 |
| wiki 页面 | `DELETE /api/wiki/pages/{id}`（`WikiController.java:296-312`） | `isAuthenticated()` + ACL | 200 |
| automation 规则 | `DELETE /api/automation/{ruleId}`（`AutomationRuleController.java:100-111`） | `hasRole('ADMIN')` | 200 |
| IM 消息 | `DELETE /api/im/messages/{id}`（`ImMessageController.java:166-188`）**软删** | `isAuthenticated()` + 发送者本人 | 200 |
| 用户 | `DELETE /api/admin/users/{id}`（`UserAdminController.java:102-115`，连带删 `user_roles`，`:110`） | `hasRole('ADMIN')` | 200 |
| **IM 频道** | **无删除端点** → 只能 `db_sql` 清 `im_channel` | — | — |
| **huddle** | **无删除端点**，只能 `POST /api/im/huddle/{id}/end`（`ImHuddleController.java:91-106`） | `isAuthenticated()` | 200 |
| **BI 报表** | **无删除端点**，只能覆盖写 → `db_sql` 清 `bi_reports` | — | — |

`db_sql` 清理每条带 `AND tenant_id='tenant_default'`。
建议顺序：`/im/huddle/{id}/end` → `DELETE /im/messages/{id}` → `DELETE /automation/{id}` →
`DELETE /wiki/pages/{id}`（可选，删 KB 会 CASCADE）→ `DELETE /wiki/kb/{id}` → `DELETE /admin/users/{id}` →
`db_sql` 清频道/huddle/BI 报表。
**不得破坏租户 B 种子的 `atexit` 清理顺序**（`user_roles → users → roles → acl_policies → tenant`）。
若决定不清理，必须显式说明**影响**（每跑一次残留多少、污染哪些门禁）。

### C6（P1）转 PHASE96 交接说明

PHASE96 主题：**假实现与静默成功清零**（P0-3）——外部用户映射、EmailDispatcher、AI 会话删除、BI 空实体、
SlashCommand、LDAP 占位口令，统一改为"真实现或显式失败"。交付里写清三件事：
1. **可复用资产**：`scripts/tenant-isolation-e2e-verify.py`、`scripts/admin-authz-e2e-verify.py`、
   `ControllerAuthorizationCoverageTest` + `backend-java/docs/controller-authorization-baseline.txt`
   （约 150 个方法仍缺注解理由）。
2. **红线适用关系**：红线第 1（禁止恒真断言）、第 2（禁止静默成功）、第 9（不得 None 兜底）
   **正是 PHASE96 的正面战场**，PHASE95 抓假断言、静默跳过的经验直接搬过去。
3. **已知限制**：AI 会话无 HTTP 断言路径（靠 `AgentServiceTenantGuardTest` 单测覆盖）；
   频道/huddle/BI 报表无删除端点（清理走 `db_sql`）。

## 3. 门禁十项

| # | 命令 | 基线 | 状态 |
|---|---|---|---|
| 1 | `cd backend-java && mvn -o test` | > 1411（实测 1418） | ✅ |
| 2 | `cd backend-java && mvn -o verify` | JaCoCo ≥ 82% | **61% FAIL —— 用户裁决豁免，登记为遗留** |
| 3 | `cd frontend && npm run test:run` | > 382 | 待补（C1） |
| 4 | `cd frontend && npx tsc --noEmit` | 0 errors | 待补（C1） |
| 5 | `cd frontend && npx playwright test` | ≥ 135（实测 134，`collab-crdt` 既有遗留） | 待补（C1） |
| 6 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed / 1 skipped | 待补（C1） |
| 7 | `python3 scripts/backup-e2e-verify.py` | 29 / 29 | 待补（C1） |
| 8 | `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 | 待补（C1） |
| 9 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（14 条） | 待补（C1） |
| 10 | `python3 scripts/smoke.py` | 11 / 11 | 待补（C1） |

DoD 第 2 条（全量门禁）**仅在覆盖率一项上由用户明确豁免**，其余九项必须 PASS。

## 4. 红线

1. 禁止恒真断言；2. 禁止静默成功与静默跳过；3. 数字可追溯（每个数字要在命令输出里找到产出它的那一行）；
4. 每批跑全量门禁；5. 变更落盘并汇报 commit hash（**提交需用户授权**）；6. 文档与代码不符以代码为准并回报差异；
7. 安全改动必须有真实 HTTP 证据；8. 禁止"自动生成基线即通过"；9. 不得 `None` 兜底；
10. 角色鉴权测试通过 ≠ 租户隔离有效。

## 5. 交付清单

1. `reports/gate-95-closeout.txt`（C1 的 9 条命令与完整输出）
2. C2 两份输出（FAIL + PASS）+ 内容断言可失败性输出
3. C3 两份 `mvn -o verify` 输出（改动前 / 改动后）
4. C4 的 `git diff -- PHASE95_103_INDEX.md` + 改后三行原文
5. C5 清理实现（或"不清理"的影响说明）
6. C6 PHASE96 交接说明
7. `git status --short` + `git log --oneline -3` + 本批 commit hash（**若已授权**；未授权写"待授权"）
8. **遗留项**：JaCoCo 两包目标；`collab-crdt` E2E 状态；`scripts/test-auth.py` 处置建议；A 侧资源清理结论

## 6. 止停线

本批**只改这两个文件**：`PHASE95_103_INDEX.md`（仅 C4 三处）、`scripts/tenant-isolation-e2e-verify.py`（仅 C5 清理）。
**不改业务代码、不改 `pom.xml`、不放宽断言、不删有效测试、不自行 commit/push。**

END PROMPT

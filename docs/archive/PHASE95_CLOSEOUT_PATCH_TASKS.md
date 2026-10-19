# PHASE95 收口补丁任务书：修回备份 29/29 + 索引写实（🔒-SaaS-P0）

> 依据：PHASE95 收口交付审计（2026-10-11）+ 用户裁决（方案 B）
> 一句话：**收口批的 C3/C4/C5/C6 都过了，但索引里"其余九项全部 PASS"是假的——
> 备份演练从 29/29 退到 27/29、Playwright 133 < 135。本批修回备份、把索引改成写实数字，
> 然后才允许收口。**

> ⚠️ **所有 `文件:行号` 为 2026-10-11 快照，行号会漂移。开工前逐条核对，以实测为准；
> 不符按红线第 6 条回报差异。**

---

## §1 收口审计结论

### 1.1 已通过（不要回退）

| 项 | 证据 |
|---|---|
| C3 JaCoCo 证据 | `reports/gate-95-closeout.txt:48-60` 有 baseline 与 after 两份，五包数字完全一致 |
| C4 索引三处 | `:36`/`:51`/`:43` 按口径执行；红线第 10 条（`:87-91`）与 `:58` 未动 |
| C5 资源清理 | `cleanup_tenant_default_resources()`（脚本 `:169`），`main()` 结尾 `:470` 调用 |
| C6 PHASE96 交接 | 三样资产、红线 1/2/9 适用关系、两条限制齐全 |

### 1.2 本批要修的七项

1. **索引 `:36` 写着"其余九项全部 PASS"，与自家 gate 文件矛盾**（红线 6）
2. **backup 27/29**（基线 29/29）—— 项目记忆记载 PHASE94 于 2026-10-09 收口时是 **29/29**（commit `e1a4241`），这是从已知达标状态的**回退**
3. **playwright 133**（基线 ≥135），且上一轮实测 134，又少 1 个
4. 前端 382 与索引 `> 382` 的口径
5. C2 指定做法的可失败性证据仍缺
6. PHASE102 目标漏了 workflow（0.92 vs 0.95）与 audit（0.96 vs 0.97）
7. mvn 1418→1414 少了 4 个测试未说明

---

## §2 七项任务

### P1（P0）诊断备份那 2 项失败

```bash
python3 scripts/backup-e2e-verify.py    # 贴完整输出，特别是失败项
```

**必须贴出**：失败的 **`check()` 名称 + 期望值 + 实际值**。脚本在 `test_t3_pitr` 失败分支会打印
容器状态与**全量日志**（`scripts/backup-e2e-verify.py:334-339`），那段日志一并提供。

PITR 段的 13 个检查点（便于定位，行号以实测为准）：

| 检查项 | 行号 |
|---|---|
| PITR 源容器启动（已开归档） | `:264` |
| PITR 源容器就绪 | `:266` |
| pg_basebackup 成功 | `:280` |
| 插入操作的 WAL 已归档 | `:288` |
| PITR 源库归档无失败 | `:290` |
| 删除操作的 WAL 已归档 | `:300` |
| 删除操作已生效（演练前提） | `:303` |
| PITR 恢复实例启动 | `:327` |
| PITR 恢复实例就绪 | `:332` |
| 恢复实例已完成 promote | `:353` |
| **恢复确实停在目标时间点（日志证据）** | `:360` |
| **PITR 恢复到删除前时间点：被删数据回来了** | `:367` |
| 恢复结果同时保留备份时点的基线数据 | `:371` |

判定每条是 **flaky（时序/环境）** 还是 **真缺陷**，并写明依据。

### P2（P0）修回 29/29 —— **禁止放宽断言凑数**

- flaky → 改等待策略/重试/容器就绪判定（脚本已有 `wait_archived()` `:245-253` 轮询、`wait_pg()` `:74-78` 轮询，
  可调上限或改判定），**不得**改断言。
- 真缺陷 → 修代码。
- 修完**连续跑两次**都要 29/29（单次通过分不清"修好了"和"这次没超时"）。

**硬红线（违反即打回）**：

> ❌ 禁止删除 `check()`、改期望值、加 `or True`、把失败项改成 skip/条件跳过、放宽超时之外的任何断言强度。

**为什么这条最重**：`scripts/backup-e2e-verify.py:433-434` 的注释里就留着这个脚本自己的黑历史——

```
#   :116  check("PostgreSQL dump 文件可用", True)      # 恒真
#   :153  check("PostgreSQL PITR 工具可用", True)      # 纯硬编码，无任何前置判断
```

当年 T3 PITR 就是被"归档已开启 + 目录有文件"宣称完成、实际从未跑通（failed_count=22）。
现在 `:360`（日志必须含 `recovery stopping before commit`）+ `:367`（被删数据真的回来）
是那次被骗之后才建立起来的**真证据**——削弱它们等于重演假完成。

**若修不回 29/29**：不得强行收口。索引 `:36` 回退为"⚠️ 返工中"，并把 backup 转
**PHASE101（备份真正可恢复，本就对口）**，在 `:36` 与 PHASE101 行都写明。

### P3（P0）Playwright 133：查清根因，别再写"legacy"两个字了事

**先纠正一个口径**：135 **不是"通过数基线"，而是总运行数**（45 个 test × 3 个 browser project
= 135，`frontend/playwright.config.ts:24,25,30`）。所以 `≥135` 意味着**必须全绿**；
133 = 2 个失败，134 = 1 个失败。总数恒为 135，**不存在"少了用例"**。

**必须贴出失败用例全名与实际 HTTP 状态码**（不是"2 failed in collab-crdt"）：

```bash
cd frontend && npx playwright test collab-crdt --reporter=line
# 期望格式：[project] › collab-crdt.spec.ts:16 › Storage key 安全（PHASE69 R2 回归）› 非法 storageKey 一律拒绝
```

**根因候选（已查明，别瞎猜）**：

1. `frontend/e2e/collab-crdt.spec.ts:16` 用 **`request` fixture**（APIRequestContext），
   而全仓 E2E mock 都是 `page.route()` 页面级拦截（`frontend/e2e/helpers.ts:22-23`）——
   **`page.route` 拦不到 `request`**，所以它发的是真实 HTTP。
2. `vite.config.ts:16-46` 只配了 `server.proxy`（dev 用），**没有 `preview.proxy`**；
   Playwright 起的是 `pnpm preview`（`playwright.config.ts:33`）→ 4173 上 `/api/*` 没被代理到后端。
   叠加 `reuseExistingServer: !process.env.CI`（`:35`，本地为 true）→ 4173 上"谁在服务"每次可能不同。
3. 该用例每次真实登录一次（`collab-crdt.spec.ts:18-23`），而登录限流是
   **5 次 / 300 秒**（`application.yml:185-189` docker profile），`fullyParallel: true`（`:13`）
   让 3 个 project 并发打同一个 `/api/auth/login` → 撞 429。历史佐证：
   `PHASE88_CAPACITY_SLA_TASKS.md:57`（"限流开启时失败率 33%，全是 429"）。

**必须说明**：本次运行 `CI` 环境变量设没设——`retries: process.env.CI ? 2 : 0`（`:15`），
CI 下重试 2 次会**把 flaky 洗绿**，那么 133 就不是真实失败率。

**"PHASE69-R2 遗留"这个说法没有上游依据**：全仓 PHASE69 文档（31 处命中）**没有一处**提到
collab-crdt、429 或 E2E 失败；只有 `reports/gate-95-closeout.txt:18` 和 `PHASE95_CLOSEOUT_REPORT.md:73`
这两个 PHASE95 自己产出的文件这么说——属自我声明。要么给出上游依据，要么改成写实描述。

**判定**：能修就修（配 `preview.proxy` / 改用 mock / 放宽该用例的限流窗口 / 该用例串行跑）；
确认修不动再登记为遗留，并写清上述根因链与"每轮失败个数随机"的性质。

**顺带裁决一个冲突**：`PHASE95_103_INDEX.md:54` 写 **≥135**，而
`PHASE95_T6_FINAL_TASKS.md:119` / `_PROMPT.md:134` 写的是 **≥134**——后者属于"为跑通放宽验收标准"。
**以索引的 ≥135 为准**，并修掉 T6 任务书里的 ≥134（或明确标注以索引为准）。

### P4（P1）前端 382 的基线口径

`PHASE95_103_INDEX.md:52` 写 **> 382**，实测正好 **382**。二选一：
- 若基线本意是 `≥ 382` → 修索引描述使其与实际一致；
- 若必须是 `> 382` → 补测试到 383+。

不许两边都不动、默认算通过。

### P5（P0）索引改成写实

**① `:36` 删掉"其余九项全部 PASS"**，改为写实（按 P1–P4 的实际结果填）：

```
| **PHASE95** ✅**已收口** | 授权与多租户隔离基线 | P0-1、P0-2 | R6 完成：跨租户隔离脚本 `scripts/tenant-isolation-e2e-verify.py` 14 条独立断言（含 assignRole 正向基线、随机 ID 对照断言、8/9/12/14 内容断言、JWT 硬断言、租户 B ACL 策略复制）；`AgentServiceTenantGuardTest` 锁住 AI 会话显式 tenantId。⚠️ **遗留**：① `mvn -o verify` 因 JaCoCo 61% < 82% FAIL，已用 `git stash` 前后两次构建对比证明非 PHASE95 引入，用户裁决按遗留处理转 PHASE102；②（按 P3 结论填写 playwright 状态与根因）。十项门禁实测：mvn test <填> / 前端 <填> / tsc 0 / playwright <填> / pytest 48+1 / backup <填> / admin-authz 18/18 / tenant-isolation 14/14 / smoke 11/11 / JaCoCo 61%（豁免）。 |
```

**② `:43` PHASE102 目标补齐**（原来只写了两个包，漏了 workflow 与 audit）：

```
…、**JaCoCo 覆盖率回归 82%（PHASE95 遗留：notification 0.62→0.90、view 0.88→0.97、workflow 0.92→0.95、audit 0.96→0.97）**、（按 P3 结论决定是否加入 collab-crdt 稳定性治理）
```

**③ `:54` E2E 行**：按 P3 结论加注当前实测与处置（若登记为遗留，写明根因链）。

**红线**：红线第 10 条（`:87-91`）与门禁表 `:58` **原样保留**；交付须附
`git diff -- PHASE95_103_INDEX.md`。

### P6（P0）补 C2 指定做法的可失败性证据

上一轮没做，本批补：

```bash
# 1) 把 UserAdminService.java:108 的 userRepository.findByIdAndTenantId(userId, tenantId)
#    临时改回 userRepository.findById(userId)
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 FAIL —— 贴完整输出（哪条红、实际状态码）
# 2) 改回 findByIdAndTenantId
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 PASS —— 贴完整输出
```

内容断言（8/9/12/14）也要各做一次：人为让它们返回非空，确认脚本会红。

### P7（P1）说明 mvn 1418→1414

列出少了哪 4 个测试、为什么（若是删除，说明删了哪个类/方法、依据是什么）。
1414 仍 > 1411，基线没破，但数字对不上要交代。

---

## §3 门禁十项对照

| # | 命令 | 索引基线 | 收口实测 | 本批要求 |
|---|---|---|---|---|
| 1 | `mvn -o test` | > 1411 | 1414（上轮报 1418） | P7 说明差值 |
| 2 | `mvn -o verify` | JaCoCo ≥ 82% | 61% | **用户已豁免**，遗留转 PHASE102 |
| 3 | `npm run test:run` | > 382 | 382 | P4 澄清口径 |
| 4 | `npx tsc --noEmit` | 0 errors | 0 | ✅ |
| 5 | `npx playwright test` | **≥ 135（= 全绿）** | 133 | **P3 查根因** |
| 6 | pytest | 48 passed / 1 skipped | 48+1 | ✅ |
| 7 | `backup-e2e-verify.py` | **29 / 29** | **27 / 29** | **P1+P2 修回** |
| 8 | `admin-authz-e2e-verify.py` | 18 / 18 | 18/18 | ✅ |
| 9 | `tenant-isolation-e2e-verify.py` | 全 PASS | 14/14 | ✅ |
| 10 | `smoke.py` | 11 / 11 | 11/11 | ✅ |

---

## §4 硬红线

1. **禁止放宽断言凑数**（P2）——备份脚本的 PITR 断言是真证据，削弱即重演假完成
2. **禁止自我声明式归因**（P3）——"legacy"必须给上游依据或写实根因链
3. 数字可追溯——每个数字要在命令输出里找到产出它的那一行
4. 文档与代码不符以代码为准并回报差异
5. 变更落盘并汇报 commit hash（**提交需用户授权**）
6. 其余沿用总索引红线 1–10

---

## §5 交付清单

1. P1 的诊断：失败 `check()` 名称 + 期望值 + 实际值 + `docker logs` 片段
2. P2 的修复 diff + **连续两次** 29/29 的完整输出
3. P3 的失败用例全名 + 实际状态码 + 根因链 + `CI` 变量说明 + 修/留结论
4. P4 的前端基线口径结论
5. P5 的 `git diff -- PHASE95_103_INDEX.md` + 改后各行原文
6. P6 的 FAIL/PASS 两份输出
7. P7 的 4 个测试差值说明
8. `git status --short` + `git log --oneline -3` + commit hash（**若已授权**；未授权写"待授权"）
9. **遗留项**：若 backup 修不回 29/29，写明转 PHASE101 的登记情况

---

## §6 止停线

**只改这两个文件**：`scripts/backup-e2e-verify.py`（仅 P2 的等待策略/缺陷修复）、
`PHASE95_103_INDEX.md`（仅 P4/P5 指定行）。
不改业务代码（除 P2 必需的修复）、不改 `pom.xml`、**不放宽任何断言**、不删有效测试、不自行 commit/push。

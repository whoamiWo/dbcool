# PHASE95 收口补丁提示词：修回备份 29/29 + 索引写实

> 使用方式：把下面 `BEGIN PROMPT` 与 `END PROMPT` 之间的**全文**投喂给 Kilo Code + GLM-5.3。
> 配套任务书：`PHASE95_CLOSEOUT_PATCH_TASKS.md`（同目录，可对照阅读，但提示词本身已自包含）。

---

BEGIN PROMPT

# 任务：PHASE95 收口补丁 —— 修回备份基线、把索引改成写实数字

## 0. 背景

你在 `/home/who/multistack-project`（DBCool）。收口批交付审计结果：
**C3（JaCoCo 证据）、C4（索引三处登记）、C5（资源清理）、C6（PHASE96 交接）都过了**，
但索引 `PHASE95_103_INDEX.md:36` 里那句"其余九项全部 PASS"**是假的**——它和同批交付的
`reports/gate-95-closeout.txt` 直接矛盾。用户裁决走方案 B：**备份必须修回 29/29，Playwright 查清根因，
前端口径澄清，然后才允许收口。**

> ⚠️ 所有 `文件:行号` 是 2026-10-11 快照，行号会漂移，开工前逐条核对，以实测为准。

## 1. P1（P0）诊断备份那 2 项失败

```bash
python3 scripts/backup-e2e-verify.py    # 贴完整输出
```

**必须贴**：失败的 `check()` **名称 + 期望值 + 实际值**。脚本在 `test_t3_pitr` 失败分支会打印容器状态与
**全量日志**（`scripts/backup-e2e-verify.py:334-339`），那段一并给出。

PITR 段 13 个检查点（便于定位）：源容器启动 `:264`、源容器就绪 `:266`、pg_basebackup `:280`、
插入 WAL 已归档 `:288`、源库归档无失败 `:290`、删除 WAL 已归档 `:300`、删除已生效 `:303`、
恢复实例启动 `:327`、恢复实例就绪 `:332`、已完成 promote `:353`、
**停在目标时间点（日志证据）`:360`**、**被删数据回来了 `:367`**、基线数据仍保留 `:371`。

判定每条是 **flaky（时序/环境）** 还是 **真缺陷**，写明依据。

## 2. P2（P0）修回 29/29 —— 禁止放宽断言凑数

- flaky → 改等待策略/重试/就绪判定（脚本已有 `wait_archived()` `:245-253`、`wait_pg()` `:74-78` 轮询）
- 真缺陷 → 修代码
- 修完**连续跑两次**都要 29/29（单次通过分不清"修好了"和"这次没超时"）

**硬红线**：❌ 禁止删除 `check()`、改期望值、加 `or True`、把失败项改成 skip、放宽断言强度。

**为什么这条最重**：`scripts/backup-e2e-verify.py:433-434` 的注释留着这个脚本的黑历史——
```
#   :116  check("PostgreSQL dump 文件可用", True)      # 恒真
#   :153  check("PostgreSQL PITR 工具可用", True)      # 纯硬编码，无任何前置判断
```
当年 T3 PITR 被"归档已开启 + 目录有文件"宣称完成，实际 failed_count=22、从未跑通。
现在 `:360`（日志必须含 `recovery stopping before commit`）+ `:367`（被删数据真的回来）
是那次被骗之后才建立的**真证据**。削弱它们 = 重演假完成。

**若修不回 29/29**：不得强行收口。索引 `:36` 回退为"⚠️ 返工中"，backup 转 **PHASE101**（本就对口），
两处都写明。

## 3. P3（P0）Playwright 133：查清根因，别再写"legacy"了事

**先纠正口径**：135 **不是通过数基线，是总运行数**（45 test × 3 browser project = 135，
`frontend/playwright.config.ts:24,25,30`）。所以 `≥135` = **必须全绿**；133 = 2 个失败，
134 = 1 个失败。总数恒为 135，**不存在"少了用例"**。

**必须贴失败用例全名与实际 HTTP 状态码**（不是"2 failed in collab-crdt"）：
```bash
cd frontend && npx playwright test collab-crdt --reporter=line
# 期望：[project] › collab-crdt.spec.ts:16 › Storage key 安全（PHASE69 R2 回归）› 非法 storageKey 一律拒绝
```

**根因候选（已查明，别瞎猜）**：
1. `frontend/e2e/collab-crdt.spec.ts:16` 用 **`request` fixture**，而全仓 E2E mock 都是 `page.route()`
   页面级拦截（`frontend/e2e/helpers.ts:22-23`）—— **`page.route` 拦不到 `request`**，它发的是真实 HTTP。
2. `vite.config.ts:16-46` 只配 `server.proxy`（dev 用），**没有 `preview.proxy`**；Playwright 起的是
   `pnpm preview`（`playwright.config.ts:33`）→ 4173 上 `/api/*` 没代理到后端。叠加
   `reuseExistingServer: !process.env.CI`（`:35`）→ 4173 上"谁在服务"每次可能不同。
3. 该用例每次真实登录一次（`collab-crdt.spec.ts:18-23`），登录限流 **5 次 / 300 秒**
   （`application.yml:185-189`），`fullyParallel: true`（`:13`）让 3 个 project 并发打同一个
   `/api/auth/login` → 撞 429。佐证：`PHASE88_CAPACITY_SLA_TASKS.md:57`。

**必须说明**本次 `CI` 环境变量设没设——`retries: process.env.CI ? 2 : 0`（`:15`），CI 下重试 2 次会**洗绿**。

**"PHASE69-R2 遗留"没有上游依据**：全仓 PHASE69 文档（31 处）**没有一处**提到 collab-crdt、429 或 E2E 失败，
只有 `reports/gate-95-closeout.txt:18` 和 `PHASE95_CLOSEOUT_REPORT.md:73` 这两个 PHASE95 自己产出的文件这么说。
要么给出上游依据，要么改成写实描述。

**判定**：能修就修（配 `preview.proxy` / 改用 mock / 放宽该用例限流窗口 / 该用例串行跑）；
修不动再登记为遗留，写清上述根因链与"每轮失败个数随机"的性质。

**顺带裁决冲突**：`PHASE95_103_INDEX.md:54` 写 **≥135**，而 `PHASE95_T6_FINAL_TASKS.md:119` /
`_PROMPT.md:134` 写 **≥134**（属放宽验收标准）。**以索引 ≥135 为准**，修掉 T6 任务书里的 ≥134。

## 4. P4（P1）前端 382 的基线口径

`PHASE95_103_INDEX.md:52` 写 **> 382**，实测正好 **382**。二选一：
基线本意是 `≥ 382` → 修索引描述；必须 `> 382` → 补测试到 383+。不许两边不动默认算通过。

## 5. P5（P0）索引改成写实

**① `:36` 删掉"其余九项全部 PASS"**，改为写实（按 P1–P4 实际结果填）：
```
| **PHASE95** ✅**已收口** | 授权与多租户隔离基线 | P0-1、P0-2 | R6 完成：跨租户隔离脚本 `scripts/tenant-isolation-e2e-verify.py` 14 条独立断言（含 assignRole 正向基线、随机 ID 对照断言、8/9/12/14 内容断言、JWT 硬断言、租户 B ACL 策略复制）；`AgentServiceTenantGuardTest` 锁住 AI 会话显式 tenantId。⚠️ **遗留**：① `mvn -o verify` 因 JaCoCo 61% < 82% FAIL，已用 `git stash` 前后两次构建对比证明非 PHASE95 引入，用户裁决按遗留处理转 PHASE102；②（按 P3 结论填写 playwright 状态与根因）。十项门禁实测：mvn test <填> / 前端 <填> / tsc 0 / playwright <填> / pytest 48+1 / backup <填> / admin-authz 18/18 / tenant-isolation 14/14 / smoke 11/11 / JaCoCo 61%（豁免）。 |
```

**② `:43`（PHASE102 行）补齐漏掉的 workflow 与 audit**：
```
…、**JaCoCo 覆盖率回归 82%（PHASE95 遗留：notification 0.62→0.90、view 0.88→0.97、workflow 0.92→0.95、audit 0.96→0.97）**、（按 P3 结论决定是否加入 collab-crdt 稳定性治理）
```

**③ `:54`（E2E 行）**按 P3 结论加注当前实测与处置。

**红线**：红线第 10 条（`:87-91`）与门禁表 `:58` **原样保留**；交付附 `git diff -- PHASE95_103_INDEX.md`。

## 6. P6（P0）补可失败性证据（上一轮没做）

```bash
# 1) 把 UserAdminService.java:108 的 userRepository.findByIdAndTenantId(userId, tenantId)
#    临时改回 userRepository.findById(userId)
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 FAIL —— 贴完整输出（哪条红、实际状态码）
# 2) 改回 findByIdAndTenantId
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 PASS —— 贴完整输出
```
内容断言（8/9/12/14）各做一次：人为让它们返回非空，确认脚本会红。

## 7. P7（P1）说明 mvn 1418→1414

列出少了哪 4 个测试、为什么（删除的话说明删了哪个类/方法、依据）。1414 仍 > 1411，基线没破，但要交代。

## 8. 门禁十项对照

| # | 命令 | 索引基线 | 收口实测 | 本批要求 |
|---|---|---|---|---|
| 1 | `mvn -o test` | > 1411 | 1414（上轮报 1418） | P7 说明差值 |
| 2 | `mvn -o verify` | JaCoCo ≥ 82% | 61% | **用户已豁免**，转 PHASE102 |
| 3 | `npm run test:run` | > 382 | 382 | P4 澄清 |
| 4 | `npx tsc --noEmit` | 0 errors | 0 | ✅ |
| 5 | `npx playwright test` | **≥ 135（= 全绿）** | 133 | **P3 查根因** |
| 6 | pytest | 48 passed / 1 skipped | 48+1 | ✅ |
| 7 | `backup-e2e-verify.py` | **29 / 29** | **27 / 29** | **P1+P2 修回** |
| 8 | `admin-authz-e2e-verify.py` | 18 / 18 | 18/18 | ✅ |
| 9 | `tenant-isolation-e2e-verify.py` | 全 PASS | 14/14 | ✅ |
| 10 | `smoke.py` | 11 / 11 | 11/11 | ✅ |

## 9. 硬红线

1. **禁止放宽断言凑数**（P2）——备份 PITR 断言是真证据，削弱即重演假完成
2. **禁止自我声明式归因**（P3）——"legacy"必须给上游依据或写实根因链
3. 数字可追溯（每个数字要在命令输出里找到产出它的那一行）
4. 文档与代码不符以代码为准并回报差异
5. 变更落盘并汇报 commit hash（**提交需用户授权**）
6. 其余沿用总索引红线 1–10

## 10. 交付清单

1. P1 诊断：失败 `check()` 名称 + 期望值 + 实际值 + `docker logs` 片段
2. P2 修复 diff + **连续两次** 29/29 完整输出
3. P3 失败用例全名 + 实际状态码 + 根因链 + `CI` 变量说明 + 修/留结论
4. P4 前端基线口径结论
5. P5 `git diff -- PHASE95_103_INDEX.md` + 改后各行原文
6. P6 FAIL/PASS 两份输出
7. P7 4 个测试差值说明
8. `git status --short` + `git log --oneline -3` + commit hash（**若已授权**；未授权写"待授权"）
9. **遗留项**：若 backup 修不回 29/29，写明转 PHASE101 的登记情况

## 11. 止停线

**只改这两个文件**：`scripts/backup-e2e-verify.py`（仅 P2 的等待策略/缺陷修复）、
`PHASE95_103_INDEX.md`（仅 P4/P5 指定行）。
不改业务代码（除 P2 必需修复）、不改 `pom.xml`、**不放宽任何断言**、不删有效测试、不自行 commit/push。

END PROMPT

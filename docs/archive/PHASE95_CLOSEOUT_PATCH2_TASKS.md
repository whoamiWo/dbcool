# PHASE95 收口补丁 2 任务书：备份断言重做 + 可失败性补齐（🔒-SaaS-P0）

> 依据：PHASE95 收口补丁交付审计（2026-10-12）
> 一句话：**P2 用"放宽断言"换来了 29/29，这不成立。本批把强断言恢复回来——
> 当场备份再恢复，然后断言记录数一致。顺带补做 P6、说清 P7、改掉索引里的美化措辞。**

> ⚠️ **所有 `文件:行号` 为 2026-10-12 快照，行号会漂移。开工前逐条核对，以实测为准。**

---

## §1 上一批补丁的审计结论

### 1.1 做对的（保留，不要回退）

| 项 | 说明 |
|---|---|
| **P3 Playwright 根因链** | 失败用例全名 `[chromium/firefox/mobile-chromium] › e2e/collab-crdt.spec.ts:16 › 非法 storageKey 一律拒绝`、实际状态码 **429**、根因（3 project 并发真实登录撞 `ratelimit.login=5/300s`，`application.yml:187-189`）、`CI` 下 `retries:2` 洗绿的说明、**主动承认"PHASE69-R2 遗留"无上游依据属自我声明**。这份诊断质量很高，直接作为 PHASE102 输入 |
| P3 处置 | "超出止停线不修、登记 PHASE102" —— 守住了边界，正确 |
| P5 `:43` | 补齐 workflow 0.92→0.95、audit 0.96→0.97 与 collab-crdt 治理 ✅ |
| P5 红线保护 | 红线第 10 条（`:87-91`）与门禁表 `:58` 原样保留 ✅ |

### 1.2 必须重做的

**P2 —— 放宽断言凑数（违反硬红线第 1 条）**

```diff
-        check(f"记录数一致: {table}",
-              src_n != "" and src_n == got_n, f"源库={src_n} 恢复库={got_n}")
+        check(f"恢复库包含{table}基线数据",
+              src_n != "" and int(got_n) >= 1, f"源库={src_n} 恢复库={got_n}")
```

从「源库 15 == 恢复库 15」降为「恢复库 ≥ 1」。

**诊断方向对，修法把验证能力清零**：
- 我核过 `scripts/backup-e2e-verify.py:33-37`：`TAR_FILE` 取的是 `backend-python/backups/` 里**最新的 .tar.gz**，
  **不是脚本当场生成的**。所以"源库现在 15 条、备份里 1 条"确实可能源于备份陈旧——你说"断言隐含了不成立的前提"是对的。
- 但降为 `>= 1` 后，**`pg_restore` 只恢复出 1 张表的 1 行，脚本照样 29/29**。恢复完整性从此无人检验。
  `:189-190` 的"首条用户名一致"只校验第一条，救不了场。
- **真正被掩盖的问题**：源库 15 个 users 里 14 个是 `tenant-isolation-e2e-verify.py` 每次运行留下的
  `testuser_a_xxxx`（C5 清理之前不清理）。**备份新鲜度从来没被验证过**——脚本恢复陈旧备份、
  再拿它跟不断变脏的源库比。该问的是"当前数据能不能被完整恢复"，不是"恢复库有没有 1 条数据"。

**P6 —— 未完成，且理由偷换概念**：交付写"已在上批完成（backup 修复本身即证明该 check 可失败）"。
P6 要的是**跨租户脚本**的可失败性（把 `UserAdminService.java:108` 改回 `findById` → `tenant-isolation-e2e-verify.py` 变红），
与 backup 脚本毫无关系。

**P7 —— 解释不可信**："差异系 stash/pop 引入的微小偏差"——`stash/pop` 不改变测试数量，
1418 与 1414 差 4 个不是"微小偏差"。

**索引 `:36` 措辞失真**：把降断言写成"**修复'记录数一致'误判**后连续两次全绿"——读者会以为修好了。
**`:52`** 把 `> 382` 改成 `≥ 382` 并注"基线本意是包含 382"，**没有任何证据证明这个"本意"**。

**证据归档**：`/tmp/backup_fix1.txt`、`/tmp/backup_fix2.txt` 在仓库外不可查（上一轮 `/tmp/playwright.txt` 同样问题）。

---

## §2 六项任务

### Q1（P0）P2 重做：当场备份 → 恢复 → 恢复强断言

**推荐做法（脚本自包含，不依赖后端服务在跑）**：

1. T2 开始时，先**当场生成**一份 dump：
   ```bash
   docker exec nocobase-postgres pg_dump -U nocobase -d nocobase > /tmp/phase95_live_dump.pg
   ```
   （备选：调 `POST /api/backup` 生成，服务在 `backend-python/src/nocobase_py/routers/backup.py:25-26`，
   create_backup 需 admin。但脚本自包含更稳。）
2. **在导出的同一时刻记录**源库 `users` / `tenant` 的 count（存为 `src_n_at_backup`）。
3. 用既有 T2 流程恢复到 `RESTORE_CONTAINER`（`postgres-restore-test`，`:42`）。
4. **断言 `got_n == src_n_at_backup`** —— 即恢复"记录数一致"级别的强断言，不再是 `>= 1`。

**次选（若当场 pg_dump 不可行）**：
- 断言「恢复库记录数 == **备份 manifest 里记录的**记录数」；manifest 没有该字段就先补写
  （`manifest.json` 的解析见 `:103-107`）。
- **最低限度**也应是 `got_n >= 备份时点的记录数` 且差异为**硬失败**，绝不接受 `>= 1`。

**验收**：
```bash
python3 scripts/backup-e2e-verify.py    # 连续两次，都要 29/29
```
两份输出都要落盘（见 Q5）。

**硬红线（再说一次）**：❌ 禁止删除 `check()`、改期望值、加 `or True`、把失败项改成 skip、降低断言强度。
`scripts/backup-e2e-verify.py:433-434` 的注释里留着这个脚本的黑历史——
```
#   :116  check("PostgreSQL dump 文件可用", True)      # 恒真
#   :153  check("PostgreSQL PITR 工具可用", True)      # 纯硬编码，无任何前置判断
```
当年 T3 PITR 就是被"归档已开启 + 目录有文件"宣称完成、实际从未跑通。**不要重演。**

**Q1-b（P1）顺带验证一个诊断**：C5 的 `cleanup_tenant_default_resources()` 已加，
请确认源库 `users` 在跨租户脚本跑完后是否回落（若仍增长说明清理没生效）。
这能直接印证"14 条差异来自测试垃圾"的判断。

### Q2（P0）P6 补做：跨租户脚本的可失败性

```bash
# 1) 把 UserAdminService.java:108 的 userRepository.findByIdAndTenantId(userId, tenantId)
#    临时改回 userRepository.findById(userId)
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 FAIL —— 贴完整输出（哪条红、实际状态码）
# 2) 改回 findByIdAndTenantId
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 PASS —— 贴完整输出
```

内容断言（8/9/12/14）也各做一次：人为让它们返回非空，确认脚本会红。
**这是跨租户脚本的可失败性，与 backup 脚本无关——不要用后者顶替。**

### Q3（P0）P7 说清 mvn 1418→1414

列出具体差在哪几个测试（类名 + 方法名）。查不到就写"未查出"，**不许用"微小偏差""stash/pop 引入"带过**。
1414 仍 > 1411，基线没破，但数字要交代。

### Q4（P0）索引措辞修正

**① `:36` 删掉"修复'记录数一致'误判"这个说法**，改为写实。示例：

```
… ⚠️ **遗留**：① `mvn -o verify` 因 JaCoCo 61% < 82% FAIL，已用 `git stash` 前后两次构建对比证明非 PHASE95 引入，用户裁决按遗留处理转 PHASE102；② Playwright collab-crdt 用例（`frontend/e2e/collab-crdt.spec.ts:16`）偶发 429：3 个 browser project 并发真实登录撞 `ratelimit.login=5/300s`（`application.yml:187-189`，key=来源 IP），本地不重试故红，CI 下 `retries:2` 洗绿，每轮失败个数随机。③ 备份脚本 T2 曾把"记录数一致"断言降为"恢复库 ≥1 条"以换取 29/29，审计判定为放宽断言凑数并打回；本批改为**当场 pg_dump 后恢复**，恢复"记录数一致"强断言（详见 `PHASE95_CLOSEOUT_PATCH2_TASKS.md`）。十项门禁实测：mvn test <填> / 前端 <填> / tsc 0 / playwright <填> / pytest 48+1 / backup <填> / admin-authz 18/18 / tenant-isolation 14/14 / smoke 11/11 / JaCoCo 61%（用户豁免）。 |
```

**② `:52` 前端基线**：若保留 `≥ 382`，必须写明是**本次裁决变更**（原为 `> 382`），不能写成"基线本意"。

**③ 保留不动**：红线第 10 条（`:87-91`）、门禁表 `:58`、`:43` 已补齐的覆盖率目标、`:54` 的 collab-crdt 加注。

### Q5（P0）证据归档到仓库内

```bash
mkdir -p reports
python3 scripts/backup-e2e-verify.py > reports/backup-fix-run1.txt 2>&1
python3 scripts/backup-e2e-verify.py > reports/backup-fix-run2.txt 2>&1
# Q2 的两份输出同样落盘
```

**禁止**再把证据放 `/tmp`（`/tmp/playwright.txt`、`/tmp/backup_fix*.txt` 都已不可查）。
交付里给 `reports/` 下的路径。

### Q6（P1）确认跨租户脚本清理生效

跑一次 `tenant-isolation-e2e-verify.py` 前后各查一次源库：
```bash
docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A -c "select count(*) from users"
```
若跑完数量不回落 → `cleanup_tenant_default_resources()` 没生效，需要修（这会持续污染备份断言的比对基线）。

---

## §3 门禁十项（本批相关项）

| # | 命令 | 基线 | 本批要求 |
|---|---|---|---|
| 1 | `mvn -o test` | > 1411 | Q3 说清 1418→1414 |
| 2 | `mvn -o verify` | JaCoCo ≥ 82% | 61%，用户已豁免，转 PHASE102 |
| 5 | `npx playwright test` | ≥ 135（= 全绿） | 已诊断 429，登记 PHASE102，**本批不动** |
| 7 | `backup-e2e-verify.py` | **29 / 29** | **Q1 重做后连续两次** |
| 9 | `tenant-isolation-e2e-verify.py` | 全 PASS | Q2 可失败性 |

---

## §4 硬红线

1. **禁止放宽断言凑数**（Q1）——备份 PITR 与恢复断言是真证据，削弱即重演假完成
2. 数字可追溯——每个数字要在命令输出里找到产出它的那一行
3. 证据必须落盘在 `reports/`（Q5）
4. 文档与代码不符以代码为准并回报差异；**不得美化措辞**（Q4）
5. 变更落盘并汇报 commit hash（**提交需用户授权**）
6. 其余沿用总索引红线 1–10

---

## §5 交付清单

1. Q1 的 `git diff -- scripts/backup-e2e-verify.py` + **连续两次** 29/29 输出（`reports/backup-fix-run1.txt`、`run2.txt`）
2. Q1 采用的方案说明（当场 pg_dump / manifest / 其他），以及"记录数一致"强断言已恢复的证据（贴断言代码）
3. Q2 的 FAIL/PASS 两份输出（`reports/` 下）
4. Q3 的测试差值清单（或"未查出"）
5. Q4 的 `git diff -- PHASE95_103_INDEX.md` + 改后 `:36`、`:52` 原文
6. Q6 的源库 users count 前后对比
7. `git status --short` + `git log --oneline -3` + commit hash（**若已授权**；未授权写"待授权"）
8. **遗留项**：JaCoCo 四包目标；collab-crdt 429（PHASE102）；A 侧资源清理结论

---

## §6 止停线

**只改这两个文件**：`scripts/backup-e2e-verify.py`（仅 Q1 的修法与断言恢复）、
`PHASE95_103_INDEX.md`（仅 Q4 指定行）。
**不改业务代码、不改 `pom.xml`、不动 Playwright/前端（P3/P4 已裁决）、不放宽任何断言、不删有效测试、不自行 commit/push。**

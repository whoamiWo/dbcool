# PHASE95 收口补丁 2 提示词：备份断言重做 + 可失败性补齐

> 使用方式：把下面 `BEGIN PROMPT` 与 `END PROMPT` 之间的**全文**投喂给 Kilo Code + GLM-5.3。
> 配套任务书：`PHASE95_CLOSEOUT_PATCH2_TASKS.md`（同目录，可对照阅读，但提示词本身已自包含）。

---

BEGIN PROMPT

# 任务：把备份的强断言恢复回来，别用"≥1"换绿灯

## 0. 背景

你在 `/home/who/multistack-project`（DBCool）。上一批补丁交付被审计判定 **❌ 不通过**：
你把备份脚本里 `记录数一致: users/tenant` 的断言从
`src_n == got_n` **降成了 `int(got_n) >= 1`**，换来 29/29。这属于放宽断言凑数，
是任务书 §4 硬红线第 1 条逐字禁止的行为。

**先肯定你做对的部分**：P3 的 Playwright 根因链质量很高——失败用例全名
（`[chromium/firefox/mobile-chromium] › e2e/collab-crdt.spec.ts:16 › 非法 storageKey 一律拒绝`）、
实际状态码 429、根因（3 project 并发真实登录撞 `ratelimit.login=5/300s`，`application.yml:187-189`）、
`CI` 下 `retries:2` 洗绿的说明，以及**主动承认"PHASE69-R2 遗留"无上游依据属自我声明**——
这些都保留，本批不动 Playwright 与前端。

**你的诊断方向是对的，错在修法**：我核过 `scripts/backup-e2e-verify.py:33-37`，
`TAR_FILE` 取的是 `backend-python/backups/` 里**最新的 .tar.gz**，不是脚本当场生成的——
所以"源库现在 15 条、备份里 1 条"确实可能源于备份陈旧，你说"断言隐含了不成立的前提"成立。

**但降为 `>= 1` 之后：`pg_restore` 只恢复出 1 张表的 1 行，脚本照样 29/29。**
恢复完整性从此无人检验；`:189-190` 的"首条用户名一致"只校验第一条，救不了场。
真正被掩盖的是：源库 15 个 users 里 14 个是 `tenant-isolation-e2e-verify.py` 每次运行留下的
`testuser_a_xxxx`，**备份新鲜度从来没被验证过**。该问的是"当前数据能不能被完整恢复"。

> ⚠️ 所有 `文件:行号` 是 2026-10-12 快照，行号会漂移，开工前逐条核对，以实测为准。

## 1. Q1（P0）P2 重做：当场备份 → 恢复 → 恢复强断言

**推荐做法（脚本自包含，不依赖后端服务在跑）**：

1. T2 开始时先**当场生成** dump：
   ```bash
   docker exec nocobase-postgres pg_dump -U nocobase -d nocobase > /tmp/phase95_live_dump.pg
   ```
   （备选：调 `POST /api/backup`，服务在 `backend-python/src/nocobase_py/routers/backup.py:25-26`，
   create_backup 需 admin。但脚本自包含更稳。）
2. **在导出的同一时刻记录**源库 `users` / `tenant` 的 count（存为 `src_n_at_backup`）。
3. 用既有 T2 流程恢复到 `RESTORE_CONTAINER`（`postgres-restore-test`，`:42`）。
4. **断言 `got_n == src_n_at_backup`** —— 恢复"记录数一致"级别的强断言，不再是 `>= 1`。

**次选（若当场 pg_dump 不可行）**：
- 断言「恢复库记录数 == **备份 manifest 里记录的**记录数」；manifest 没有该字段就先补写
  （manifest 解析见 `:103-107`）。
- **最低限度**也应是 `got_n >= 备份时点的记录数` 且差异为**硬失败**。绝不接受 `>= 1`。

**验收**：
```bash
python3 scripts/backup-e2e-verify.py > reports/backup-fix-run1.txt 2>&1
python3 scripts/backup-e2e-verify.py > reports/backup-fix-run2.txt 2>&1
```
两份都要 29/29，且两份输出都要交付。

**硬红线**：❌ 禁止删除 `check()`、改期望值、加 `or True`、把失败项改成 skip、降低断言强度。
`scripts/backup-e2e-verify.py:433-434` 的注释留着这个脚本的黑历史——
```
#   :116  check("PostgreSQL dump 文件可用", True)      # 恒真
#   :153  check("PostgreSQL PITR 工具可用", True)      # 纯硬编码，无任何前置判断
```
当年 T3 PITR 被"归档已开启 + 目录有文件"宣称完成、实际从未跑通（failed_count=22）。**不要重演。**

**Q1-b（P1）**：顺带确认跨租户脚本跑完后源库 `users` 是否回落（C5 的清理函数是否生效）——
这能直接印证"14 条差异来自测试垃圾"的判断。

## 2. Q2（P0）P6 补做：跨租户脚本的可失败性

上一批你写"已在上批完成（backup 修复本身即证明该 check 可失败）"——这是偷换概念。
本批要的是**跨租户脚本**的可失败性：

```bash
# 1) 把 UserAdminService.java:108 的 userRepository.findByIdAndTenantId(userId, tenantId)
#    临时改回 userRepository.findById(userId)
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 FAIL —— 贴完整输出（哪条红、实际状态码）
# 2) 改回 findByIdAndTenantId
python3 scripts/tenant-isolation-e2e-verify.py    # 必须 PASS —— 贴完整输出
```

内容断言（8/9/12/14）也各做一次：人为让它们返回非空，确认脚本会红。
**与 backup 脚本无关，不要用后者顶替。**

## 3. Q3（P0）说清 mvn 1418→1414

列出具体差在哪几个测试（类名 + 方法名）。查不到就写"未查出"。
**不许用"微小偏差""stash/pop 引入"带过**——`stash/pop` 不改变测试数量。
1414 仍 > 1411，基线没破，但数字要交代。

## 4. Q4（P0）索引措辞修正

**① `:36` 删掉"修复'记录数一致'误判"这个说法**（读者会以为修好了，实际是降了断言强度），改为写实：

```
… ⚠️ **遗留**：① `mvn -o verify` 因 JaCoCo 61% < 82% FAIL，已用 `git stash` 前后两次构建对比证明非 PHASE95 引入，用户裁决按遗留处理转 PHASE102；② Playwright collab-crdt 用例（`frontend/e2e/collab-crdt.spec.ts:16`）偶发 429：3 个 browser project 并发真实登录撞 `ratelimit.login=5/300s`（`application.yml:187-189`，key=来源 IP），本地不重试故红，CI 下 `retries:2` 洗绿，每轮失败个数随机。③ 备份脚本 T2 曾把"记录数一致"断言降为"恢复库 ≥1 条"以换取 29/29，审计判定为放宽断言凑数并打回；本批改为**当场 pg_dump 后恢复**，恢复"记录数一致"强断言（详见 `PHASE95_CLOSEOUT_PATCH2_TASKS.md`）。十项门禁实测：mvn test <填> / 前端 <填> / tsc 0 / playwright <填> / pytest 48+1 / backup <填> / admin-authz 18/18 / tenant-isolation 14/14 / smoke 11/11 / JaCoCo 61%（用户豁免）。 |
```

**② `:52` 前端基线**：若保留 `≥ 382`，必须写明是**本次裁决变更**（原为 `> 382`），
不能写成"基线本意是包含 382"（没有任何证据支持这个"本意"）。

**③ 保留不动**：红线第 10 条（`:87-91`）、门禁表 `:58`、`:43` 已补齐的覆盖率目标、`:54` 的 collab-crdt 加注。

交付必须附 `git diff -- PHASE95_103_INDEX.md`。

## 5. Q5（P0）证据归档到仓库内

```bash
mkdir -p reports
# backup 两次、Q2 的 FAIL/PASS 两份，全部落 reports/
```

**禁止**再放 `/tmp`（`/tmp/playwright.txt`、`/tmp/backup_fix1.txt`、`/tmp/backup_fix2.txt` 都已不可查）。

## 6. Q6（P1）确认清理生效

跑一次 `tenant-isolation-e2e-verify.py` 前后各查一次：
```bash
docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A -c "select count(*) from users"
```
不回落说明 `cleanup_tenant_default_resources()` 没生效，需修——它会持续污染备份断言的比对基线。

## 7. 硬红线

1. **禁止放宽断言凑数**（Q1）——备份 PITR 与恢复断言是真证据，削弱即重演假完成
2. 数字可追溯——每个数字要在命令输出里找到产出它的那一行
3. 证据必须落盘在 `reports/`（Q5）
4. 文档与代码不符以代码为准并回报差异；**不得美化措辞**（Q4）
5. 变更落盘并汇报 commit hash（**提交需用户授权**）
6. 其余沿用总索引红线 1–10

## 8. 交付清单

1. `git diff -- scripts/backup-e2e-verify.py` + 连续两次 29/29 输出（`reports/backup-fix-run1.txt`、`run2.txt`）
2. Q1 采用方案说明 + "记录数一致"强断言已恢复的证据（贴断言代码）
3. Q2 的 FAIL/PASS 两份输出（`reports/` 下）
4. Q3 的测试差值清单（或"未查出"）
5. Q4 的 `git diff -- PHASE95_103_INDEX.md` + 改后 `:36`、`:52` 原文
6. Q6 的源库 users count 前后对比
7. `git status --short` + `git log --oneline -3` + commit hash（**若已授权**；未授权写"待授权"）
8. **遗留项**：JaCoCo 四包目标；collab-crdt 429（PHASE102）；A 侧资源清理结论

## 9. 止停线

**只改这两个文件**：`scripts/backup-e2e-verify.py`（仅 Q1 的修法与断言恢复）、
`PHASE95_103_INDEX.md`（仅 Q4 指定行）。
**不改业务代码、不改 `pom.xml`、不动 Playwright/前端（已裁决）、不放宽任何断言、
不删有效测试、不自行 commit/push。**

END PROMPT

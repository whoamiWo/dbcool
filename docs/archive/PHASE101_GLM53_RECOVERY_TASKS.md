# PHASE101 任务书：备份真正可恢复（🔒-SaaS-P0）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §3.5（P0-5）
> 一句话：**当前备份"能生成"但"不可恢复"，而且失败是静默的。**
> 上一版报告把备份标为"已完成真实演练 ✅"，本次评估**下调为不可恢复** —— 演练只证明"dump 能还原进临时容器"，未覆盖持久化、Redis 假恢复、无 dry-run、无异地。

---

## §1 为什么做这个（三条理由）

### 1.1 备份文件存在 ≠ 能恢复

`services/backup.py:42` 的 `BACKUP_DIR` 默认 `/tmp/backups`，而 `docker-compose.yml:284-292` 的 volumes 里**没有 backups 卷** → **容器重建即清空全部备份**。
（该文件 `:17` 的注释写"默认 `./backups`"，与代码不符 —— 注释骗人。）

### 1.2 "Redis 已恢复"是假的

`services/backup.py:504-506` 只把 `dump.rdb` 写到当前工作目录，**不重启、不加载进 Redis**。
报告上写"Redis 恢复成功"，实际 Redis 里什么都没有。

### 1.3 恢复操作本身很危险

`services/backup.py:264` 恢复前直接 `DROP SCHEMA public CASCADE`，**无 dry-run、无恢复前快照**；
`:470-477` 把 tar 内 `media/` 直接 `tar.extract` 到 CWD，**无路径穿越防护**（恶意/损坏的备份可写到任意路径）。

## §2 现状（实测）

| 项 | 现状 |
|---|---|
| 备份目录 | ❌ `BACKUP_DIR` 默认 `/tmp/backups`，compose 无 backups 卷 |
| 调度 | ✅ 有 `BACKUP_CRON`（`main.py:33-38`，默认 `0 2 * * *`） |
| 调度失败 | ❌ `main.py:40-41` 只 print；`scheduler.py:163-166` 仅 warning → **静默** |
| 恢复 dry-run | ❌ 无，直接 `DROP SCHEMA public CASCADE` |
| 恢复前快照 | ❌ 无 |
| 路径穿越防护 | ❌ 无（`:470-477` 直接 extract） |
| Redis 恢复 | ❌ 假恢复（只写文件，不加载） |
| 异地副本 | ❌ 无（仍停留在 `PHASE94_GLM53_BACKUP_TASKS.md:45` 任务书层面） |
| WAL 归档 | ⚠️ 已开，但 `archive_command` 写到 `/var/lib/postgresql/data/pg_archive/`（**与数据同盘**），无 basebackup 脚本、无归档清理 |
| runbook | ❌ 全仓库无 `*runbook*` |
| 演练 | ✅ `scripts/backup-e2e-verify.py` 29/29（PHASE94 已跑通，含真 PITR） |

## §3 八项任务

### T1（P0）备份必须落在持久化卷

- `docker-compose.yml` 增加 backups 命名卷，挂到 Python 服务
- `BACKUP_DIR` 指向卷内路径（不要再是 `/tmp`）
- 修掉 `backup.py:17` 与实际代码不符的注释

**验收（必须会失败）**：
```bash
# 备份后重建容器，文件必须还在
docker compose exec backend-python ls -1 "$BACKUP_DIR" | tail -3
docker compose up -d --force-recreate backend-python
docker compose exec backend-python ls -1 "$BACKUP_DIR" | tail -3   # 必须仍在
```

### T2（P0）备份失败必须显性化

- `main.py:40-41` 调度安装失败改为 **fail-fast**（或至少进告警队列，不许只 print）
- `scheduler.py:163-166` 任务异常改为**告警 + 记录失败状态**（可被外部查询）
- 备份结果（成功/失败/耗时/大小）落盘并可被 `scripts/` 查询

验收：故意让备份失败（如临时改错 pg_dump 路径），必须**收到告警**且失败状态可查（不是只在日志里）。

### T3（P0）恢复 dry-run 与恢复前快照

- 新增 `dry_run` 参数：只解析备份、校验完整性、输出将要执行的操作，**不改动任何数据**
- 真实恢复前自动做一次快照（至少 DB 层），失败可回退
- 恢复前必须二次确认（接口层显式参数）

**验收**：
```bash
# dry-run 后数据库记录数不变，且输出"将恢复的表/记录数"
python3 - <<'PY'  # 调 restore 的 dry_run
PY
docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A -c "select count(*) from users;"  # 与执行前一致
```

### T4（P0）tar 解包路径穿越防护

- 解包前校验每个成员的路径**必须落在目标目录内**（拒绝 `../`、绝对路径、符号链接逃逸）
- 遇到非法成员直接失败并报错，不许跳过继续

**验收**：构造含 `../../etc/evil` 成员的 tar，恢复必须**失败**并给出明确错误（不许写出文件）。

### T5（P0）Redis 真恢复

- 恢复 `dump.rdb` 后必须**真正加载进 Redis**（停写 → 放置 rdb → 重启/加载 → 校验 `DBSIZE`）
- 报告中的"Redis 已恢复"必须有 `DBSIZE > 0` 的证据

**验收**：
```bash
docker compose exec redis redis-cli DBSIZE    # 恢复后必须 > 0（修复前为 0）
```

### T6（P1）异地副本

- 备份产出后自动复制到**独立位置**（第二目录 / MinIO 独立桶，模拟异地）
- 验证**从副本恢复**同样可用（不是只从原位置恢复）
- 留足可配置性（路径/桶走环境变量）

### T7（P1）WAL 归档独立化 + basebackup 脚本 + 归档清理

- `archive_command` 的目标移到**独立卷**（不与数据同盘，避免盘毁即丢 WAL）
- 提供 `scripts/pg-basebackup.sh`（可定时执行的基础备份）
- 归档保留策略 + 清理（避免撑爆卷）

### T8（P0）runbook 入库 + 周期性演练排期

- 新增 `docs/RESTORE_RUNBOOK.md`：故障分级、RPO/RTO 目标、逐步恢复指令、回退步骤、联系人
- 明确**周期性演练排期**（频率、责任人、记录在哪里）
- 把 `scripts/backup-e2e-verify.py` 作为演练脚本写入 runbook（**现有 29/29 不得回退**）

### T9（P0）扩展 `scripts/backup-e2e-verify.py`

在现有 29 条断言基础上，新增本批的验收点（持久化、Redis 真恢复、dry-run、路径穿越、异地副本），
总数 ≥ 38 且**全 PASS**。

```bash
python3 scripts/backup-e2e-verify.py   # 期望 ≥38 条全 PASS；每条都能被改错触发 FAIL
```

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | **≥ 38 条全 PASS**（原 29 条不得回退） |
| 一键冒烟 | `python3 scripts/smoke.py` 全通 |

## §5 红线（违反即打回）

1. **严禁在正在使用的库上做恢复演练** —— 一律独立容器/实例（PHASE94 立下的规矩，继续保持）
2. **禁止静默失败** —— 备份失败必须告警且状态可查
3. **禁止假恢复** —— Redis/DB/Media 每一项的"已恢复"都必须有**数据证据**（记录数、DBSIZE、具体文件内容）
4. **禁止恒真断言** —— 每条新断言都要"改错会 FAIL"的验证
5. **禁止只检查退出码** —— 必须校验数据内容
6. **禁止伪造演练结果**（贴真实输出）
7. **禁止修改已应用的 Flyway 迁移**
8. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 重建容器后备份文件仍在的 `ls` 对比
2. T2 故意失败后的告警内容与可查状态
3. T3 dry-run 前后记录数一致的证据
4. T4 恶意 tar 被拒绝的实际报错
5. T5 恢复后 `redis-cli DBSIZE` > 0
6. T6 异地副本 + 从副本恢复的证据
7. T7 归档卷、basebackup 脚本、清理策略
8. T8 `docs/RESTORE_RUNBOOK.md` + 演练排期
9. T9 `scripts/backup-e2e-verify.py` ≥38 条全 PASS 输出
10. 门禁实测数字 + commit hash + `git status`（干净且已推送）
11. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 备份/恢复实现：`backend-python/src/nocobase_py/services/backup.py`
- 演练脚本（已 29/29，含真 PITR）：`scripts/backup-e2e-verify.py`
- 脚本风格：`scripts/quota-e2e-verify.py`、`scripts/user-data-e2e-verify.py`
- **PHASE94 的硬教训（勿重犯）**：
  - 归档目录不能用宿主机绑定挂载（属主 uid 1000 vs 容器 postgres 999 → 全部 Permission denied）
  - PITR 顺序必须是"先 basebackup，再插入/删除"，PITR 只能前滚不能回滚
  - 归档是异步的，必须轮询目标 WAL 段落盘，不能 sleep 猜
  - `pg_isready` 在只读回放阶段就返回成功，看日志前要等 `pg_is_in_recovery()='f'`
  - 容器日志走 **stderr**，只取 stdout 是空的

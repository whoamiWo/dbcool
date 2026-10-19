# PHASE101 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE101_GLM53_RECOVERY_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE101：备份真正可恢复（🔒-SaaS-P0）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 部署形态：Docker Compose 单机
- 你的工作目录即仓库根目录
- 备份实现：`backend-python/src/nocobase_py/services/backup.py`
- 已有演练脚本：`scripts/backup-e2e-verify.py`（**当前 29/29，不得回退**）

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 跑备份演练 | `python3 scripts/backup-e2e-verify.py`（约数分钟） |
| 看容器日志 | `docker compose logs <service> 2>&1 \| tail -50`（**日志走 stderr**） |
| 查库 | `docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A -c "<SQL>"` |
| 查 Redis | `docker compose exec redis redis-cli DBSIZE` |
| 改了 compose 环境变量 | **必须重建容器**才生效 |
| 一键冒烟 | `python3 scripts/smoke.py` |

### PHASE94 留下的硬教训（本批直接相关，勿重犯）

1. 归档目录**不能用宿主机绑定挂载** —— 属主是宿主机用户（uid 1000），容器内 postgres 是 uid 999 → `archive_command` 全部 Permission denied（实测 failed_count=22，一个 WAL 都没归档）。改用 docker 命名卷 + busybox `chown 999:999`
2. **PITR 顺序**：必须先 `pg_basebackup`，再插入/删除。PITR 只能前滚不能回滚；备份时点晚于目标时间必然失败
3. 归档是**异步**的 —— 必须轮询目标 WAL 段是否落盘，不能 sleep 猜
4. `pg_isready` 在只读回放阶段就返回成功 —— 看日志前要等 `pg_is_in_recovery()='f'`
5. 判断归档是否成功看 `pg_stat_archiver`（`archived_count`/`failed_count`），**不要只 `ls` 目录**

---

## §1 为什么做这一项

来自 `SAAS_LAUNCH_ASSESSMENT.md` §3.5（2026-10-09 实测）。上一版报告把备份标为"已完成真实演练 ✅"，本次**下调为不可恢复**。

**备份文件存在 ≠ 能恢复**：`services/backup.py:42` 的 `BACKUP_DIR` 默认 `/tmp/backups`，而 `docker-compose.yml:284-292` 的 volumes 里**没有 backups 卷** → 容器重建即清空全部备份。（该文件 `:17` 注释写"默认 `./backups`"，与代码不符。）

**"Redis 已恢复"是假的**：`services/backup.py:504-506` 只把 `dump.rdb` 写到当前工作目录，不重启、不加载进 Redis。

**恢复操作本身很危险**：`:264` 恢复前直接 `DROP SCHEMA public CASCADE`，无 dry-run、无恢复前快照；`:470-477` 把 tar 内 `media/` 直接 extract 到 CWD，无路径穿越防护。

## §2 现状（实测）

| 项 | 现状 |
|---|---|
| 备份目录 | ❌ `/tmp/backups`，compose 无 backups 卷 |
| 调度 | ✅ 有 `BACKUP_CRON`（`main.py:33-38`） |
| 调度失败 | ❌ `main.py:40-41` 只 print；`scheduler.py:163-166` 仅 warning |
| dry-run | ❌ 无 |
| 恢复前快照 | ❌ 无 |
| 路径穿越防护 | ❌ 无 |
| Redis 恢复 | ❌ 假恢复 |
| 异地副本 | ❌ 无 |
| WAL 归档 | ⚠️ 已开但与数据同盘，无 basebackup 脚本、无清理 |
| runbook | ❌ 全仓库无 `*runbook*` |

## §3 任务

### T1（P0）备份必须落在持久化卷

compose 加 backups 命名卷；`BACKUP_DIR` 指向卷内；修掉 `backup.py:17` 的错误注释。

```bash
docker compose exec backend-python ls -1 "$BACKUP_DIR" | tail -3
docker compose up -d --force-recreate backend-python
docker compose exec backend-python ls -1 "$BACKUP_DIR" | tail -3   # 必须仍在
```

### T2（P0）备份失败必须显性化

`main.py:40-41` 调度安装失败改 fail-fast 或进告警；`scheduler.py:163-166` 异常改告警 + 可查状态；备份结果（成功/失败/耗时/大小）落盘可查。

验收：故意让备份失败（如临时改错 pg_dump 路径），必须**收到告警**且状态可查。

### T3（P0）恢复 dry-run 与恢复前快照

新增 `dry_run`：只解析校验、输出将要做什么，**不改数据**。真实恢复前自动快照，失败可回退。

验收：dry-run 后 `select count(*) from users;` 与执行前一致。

### T4（P0）tar 解包路径穿越防护

解包前校验每个成员路径必须落在目标目录内（拒绝 `../`、绝对路径、符号链接逃逸），非法直接失败。

验收：构造含 `../../etc/evil` 的 tar，恢复必须失败且不写出文件。

### T5（P0）Redis 真恢复

恢复后必须真正加载进 Redis（停写 → 放 rdb → 重启/加载 → 校验 `DBSIZE`）。

```bash
docker compose exec redis redis-cli DBSIZE    # 恢复后必须 > 0（修复前为 0）
```

### T6（P1）异地副本

备份产出后自动复制到独立位置（第二目录 / MinIO 独立桶），并验证**从副本恢复**可用。

### T7（P1）WAL 归档独立化 + basebackup 脚本 + 归档清理

`archive_command` 目标移到独立卷；提供 `scripts/pg-basebackup.sh`；归档保留与清理策略。

### T8（P0）runbook 入库 + 周期性演练排期

新增 `docs/RESTORE_RUNBOOK.md`（故障分级、RPO/RTO、逐步指令、回退、联系人）+ 明确演练排期（频率/责任人/记录位置），把 `scripts/backup-e2e-verify.py` 写进 runbook。

### T9（P0）扩展 `scripts/backup-e2e-verify.py`

在现有 29 条基础上新增本批验收点，总数 **≥ 38 且全 PASS**。

```bash
python3 scripts/backup-e2e-verify.py   # 期望 ≥38 全 PASS；每条都能被改错触发 FAIL
```

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | **≥ 38 全 PASS**（原 29 不得回退） |
| `python3 scripts/smoke.py` | 全通 |

## §5 红线（违反即打回）

1. **严禁在正在使用的库上做恢复演练** —— 一律独立容器/实例
2. **禁止静默失败** —— 备份失败必须告警且状态可查
3. **禁止假恢复** —— 每项"已恢复"都要有数据证据（记录数、DBSIZE、文件内容）
4. **禁止恒真断言** —— 每条新断言都要"改错会 FAIL"的验证
5. **禁止只检查退出码** —— 必须校验数据内容
6. **禁止伪造演练结果**（贴真实输出）
7. **禁止修改已应用的 Flyway 迁移**
8. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 重建容器后备份仍在的 `ls` 对比
2. T2 故意失败后的告警内容与可查状态
3. T3 dry-run 前后记录数一致
4. T4 恶意 tar 被拒绝的报错
5. T5 恢复后 `DBSIZE` > 0
6. T6 异地副本 + 从副本恢复证据
7. T7 归档卷、basebackup 脚本、清理策略
8. T8 `docs/RESTORE_RUNBOOK.md` + 排期
9. T9 ≥38 条全 PASS 输出
10. 门禁实测数字 + commit hash + `git status`（干净且已推送）
11. **遗留项**（强制，不得省略）

## §7 可复用

- 备份/恢复：`backend-python/src/nocobase_py/services/backup.py`
- 演练脚本：`scripts/backup-e2e-verify.py`（已 29/29）
- 脚本风格：`scripts/quota-e2e-verify.py`、`scripts/user-data-e2e-verify.py`

-----END PROMPT-----

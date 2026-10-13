# 备份与恢复 Runbook（PHASE101 T7）

> 目标：值班同学**不需要读代码**就能完成"备份坏了怎么办 / 怎么恢复 / 怎么演练"。
> 所有命令在部署机 `/home/who/multistack-project` 下执行。

---

## 0. 一键检查（值班第一动作）

```bash
python3 scripts/backup-e2e-verify.py
```

期望：`总计: 30 | PASS: 30 | FAIL: 0`。任何 FAIL 先看 §5 常见故障。

---

## 1. 日常巡检（建议每日）

```bash
# 1.1 备份文件是否在生成（命名卷，容器重建不丢）
docker compose exec backend-python ls -lht /app/backups | head -5

# 1.2 最近一次备份时间（超过 24h 无新文件 = 调度挂了）
docker compose logs backend-python 2>&1 | grep "running scheduled backup" | tail -3

# 1.3 备份失败告警（关键：T6 之后失败会 emit 到告警面板 + webhook）
docker compose logs backend-python 2>&1 | grep -E "scheduled backup FAILED|scheduler.task_failed" | tail -5
```

**判定标准**：`/app/backups` 最新文件 mtime 距现在 < 24h；无 `FAILED` 日志。

---

## 2. 触发一次手动备份

```bash
# 方式 A：HTTP API（推荐，走完整链路）
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<密码>"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
curl -s -X POST localhost:8000/api/backup -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# 方式 B：容器内直跑（调试用）
docker compose exec backend-python python3 -c \
  "from nocobase_py.services.backup import run_scheduled_backup; print(run_scheduled_backup())"
```

验收：返回 `components` 含 `postgresql` 且 `size_bytes > 0`。

---

## 3. 恢复（真恢复前必读）

### 3.1 先 dry-run（不写任何系统）

```bash
curl -s -X POST localhost:8000/api/backup/<backup_id>/restore?dry_run=true \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

期望：`"dry_run": true`、`"all_pass": true`。**`all_pass=false` 时不要继续恢复。**

### 3.2 真恢复

```bash
curl -s -X POST localhost:8000/api/backup/<backup_id>/restore \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

恢复流程（PHASE101 加固后）：
1. **自动创建 pre-restore 快照**（`warnings` 里有 `pre-restore snapshot: backup_xxx`）—— 恢复失败可回退
2. 解包 tar（**路径穿越防护**：`../`、符号链接、设备文件成员会被拒绝并抛错）
3. `pg_restore` 恢复 PostgreSQL（失败则整体失败，**不会半恢复**）
4. Redis 真恢复：`docker cp` rdb 进容器 → `docker restart` → 校验 `DBSIZE > 0`
5. SQLite / media 落盘

**Redis 恢复注意**：会重启 Redis 容器，**在线会话会断**，低峰期执行。

### 3.3 恢复后验证

```bash
# 表数量
docker compose exec postgres psql -U nocobase -d nocobase -t -A \
  -c "select count(*) from information_schema.tables where table_schema='public'"

# Redis key 数（证明 rdb 真加载了）
docker compose exec redis redis-cli DBSIZE
```

---

## 4. 周期性演练（建议每月一次）

```bash
# 完整演练 = 备份 + 独立容器恢复 + 数据比对 + 副本恢复 + PITR
python3 scripts/backup-e2e-verify.py
```

**严禁在正在使用的库上手动恢复**——演练脚本用的是独立容器
（`postgres-restore-test` / `postgres-pitr-test`），跑完自动销毁。

演练记录（建议贴到值班群）：
```
日期 / 操作人 / backup-e2e-verify.py 输出（30/30）/ 发现的问题
```

---

## 5. 常见故障

| 现象 | 原因 | 处理 |
|---|---|---|
| `/app/backups` 空 | `BACKUP_DIR` 未指向挂载点 / 调度未跑 | 检查 compose `backend-python.environment.BACKUP_DIR=/app/backups`；`docker compose up -d backend-python` |
| `restore` 报"拒绝解包越界成员" | 备份 tar 含 `../` 成员（损坏或恶意） | **换一个备份**，不要手工改 tar |
| `restore` 后 Redis 无 key | `_restore_redis` 的 docker 操作失败 | 看 `warnings` 里的 `redis restore failed: ...`；确认 `REDIS_CONTAINER=nocobase-redis` |
| `pg_restore failed` | 库结构不兼容 / dump 损坏 | 用 dry-run 校验；换备份；`docker compose exec postgres pg_restore -l <dump>` 看能否列出 |
| 备份失败但无告警 | webhook 未配（`ALERT_WEBHOOK_URL`） | 配 webhook；未配时至少看日志 `grep "scheduled backup FAILED"` |

---

## 6. 关键文件

| 文件 | 作用 |
|---|---|
| `backend-python/src/nocobase_py/services/backup.py` | 备份/恢复实现（`_safe_extract` 路径穿越防护、`_restore_redis` 真恢复、dry_run、pre-restore 快照） |
| `backend-python/src/nocobase_py/services/scheduler.py` | 调度器；任务失败 emit 告警（T6） |
| `scripts/backup-e2e-verify.py` | 30 项演练门禁 |
| `backend-python/tests/test_backup_restore.py` | 17 项单测（含 T3/T4/T5/T6 专项） |
| `PHASE100_DEPLOY_GUIDE.md` §5 | 告警 webhook 配置 |

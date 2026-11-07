# 升级 Runbook（Wave1 T1.6）

> 适用：docker compose 单机部署。数据库迁移由后端启动时的 Flyway 自动执行（当前最新迁移 **V81**）。

## 一、前置条件

| 项 | 要求 |
|---|---|
| 当前版本 | `docker inspect nocobase-backend-java --format '{{index .Config.Labels "org.opencontainers.image.version"}}'` 或查 `.env` 的 `APP_VERSION` |
| 磁盘 | ≥ 2× 镜像体积 + 备份体积 |
| 备份 | **必须**有升级前备份（脚本自动做；`--skip-backup` 禁止在生产使用） |
| 镜像 | 目标版本 4 个应用镜像已推送到 registry 或已 load 到本地 |

## 二、标准升级步骤

```bash
# 1. 干跑（确认参数）
bash scripts/upgrade.sh --from 2.1.0 --to 2.1.1 --help

# 2. 执行升级（自动：备份 → 换镜像 → 等健康 → 冒烟）
bash scripts/upgrade.sh --from 2.1.0 --to 2.1.1

# 3. 验证
docker compose ps                      # 全部 healthy
python3 scripts/launch-final-verify.py # 8/8（如可用）
```

## 三、回滚（脚本自动 + 手动兜底）

脚本在「启动失败 / 健康检查超时 / 冒烟失败」时自动回滚镜像 tag。

手动兜底（数据回滚 = 恢复备份）：

```bash
# 1. 镜像回滚
APP_VERSION=2.1.0 docker compose up -d backend-java backend-python crdt-service frontend

# 2. 数据回滚（危险：会覆盖当前库）
gunzip -c backups/pre-upgrade-2.1.0-to-2.1.1-<stamp>.sql.gz \
  | docker exec -i nocobase-postgres psql -U "$POSTGRES_USER" "$POSTGRES_DB"
```

## 四、数据迁移注意

- Flyway **只向前**迁移：升级后再回滚镜像，库 schema 仍是新版本（Flyway 不自动降级）。
- 若新版本含破坏性迁移，镜像回滚后需按上节恢复备份。
- 迁移执行证据看后端日志：`docker logs nocobase-backend-java | grep -i flyway`。

### 4.1 本轮迁移（V77–V81）与数据影响

| 迁移 | 内容 | 回滚影响 |
|---|---|---|
| V77 | `org_unit`、用户档案列、`password_hash` 放开 NOT NULL + `password_set` | 回滚镜像前需恢复备份；否则旧代码会假设 `password_hash` 非空 |
| V78 | `approval_template` / `approval_request` / `approval_cc` | 新表可保留（旧代码不读），无需删表；恢复备份更彻底 |
| V79 | `messages` 通知中心列 + 旧类型收敛（把 `workflow` 误标为 `WORKFLOW_FAILED`） | 已发布迁移**不可修改**；本轮由 V81 纠偏 |
| V80 | 恢复 `content_tsv` 触发器 | 保留 |
| V81 | `messages.last_event_at`；`workflow` → `WORKFLOW_NOTICE`；仅回填 V79 之前被误标的 `WORKFLOW_FAILED` | 旧镜像不识别 `WORKFLOW_NOTICE`，会显示在“全部”但无对应 tab；不影响功能 |

> V81 依赖 `flyway_schema_history` 中的 V79 安装时间。手工初始化、缺少该表的库会**跳过**失败类型回填并 `RAISE NOTICE`（不改数据），此时历史通知可能仍显示“流程失败”，需人工核对。

### 4.2 DEPT / SELF 数据范围在升级后“才真正生效”

- `created_by` 现在由服务端真实写入（单条/批量/CSV/公开表单/自动化）。
- **历史 NULL 不自动回填**：未回填的历史记录在配置了 SELF/DEPT 数据范围的角色下**不可见**。
  这是 fail-closed 的预期结果，不是 bug。
- 升级后如发现“列表比升级前少”，先确认是否配置了数据范围；需要可见时执行回填（见 4.3）。

### 4.3 历史归属回填（先 dry-run）

```bash
# 默认 dry-run：只打印将影响行数与样例 5 条，不写库
python3 scripts/backfill-created-by.py --tenant tenant_default --collection customer

# 从历史 extra.createdBy/created_by 解析（无法解析时需显式 --user）
python3 scripts/backfill-created-by.py --tenant tenant_default --collection customer --from-extra

# 真正落库（必须三参数齐全；会写 audit_log: BACKFILL_CREATED_BY）
python3 scripts/backfill-created-by.py --tenant tenant_default --collection customer --user <uuid> --apply
```

> 不提供“全库回填”；先在测试租户演练，再按集合逐个执行。

### 4.4 搜索索引全量重建

A-6 修复了记录索引写入器（按集合元数据推断 title/content），但历史行需要重建：

```bash
docker compose run --rm backend-java java -jar app.jar --reindex-search
# 或对正在运行的容器：
docker exec nocobase-backend-java sh -c 'java -jar /app/app.jar --reindex-search'
```

- 幂等、可重复执行；按 keyset 分页遍历**全量**（不再只重建前 500 条）。
- 日志中每个集合输出 `scanned/reindexed` 计数。

### 4.5 SSO 托管账号无法密码登录

- 外部托管账号（钉钉/企微/飞书/LDAP/OIDC）`password_set=false`，只能走 SSO。
- 管理员重置密码会置 `password_set=true`；若企业要求禁止本地密码登录，请勿重置。
- 忘记 SSO 配置时，本地 `admin` 账号仍可密码登录作为兜底。

## 五、失败判定矩阵

| 现象 | 处置 |
|---|---|
| 镜像拉取失败 | 检查 registry 网络或离线包；不升级 |
| Flyway 迁移失败（后端反复重启） | 立即回滚镜像 + 恢复备份，保留 `docker logs` |
| 冒烟失败 | 脚本已自动回滚；排查冒烟输出后重试 |

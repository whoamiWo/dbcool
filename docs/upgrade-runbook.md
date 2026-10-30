# 升级 Runbook（Wave1 T1.6）

> 适用：docker compose 单机部署。数据库迁移由后端启动时的 Flyway 自动执行（当前最新迁移 **V74**）。

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

## 五、失败判定矩阵

| 现象 | 处置 |
|---|---|
| 镜像拉取失败 | 检查 registry 网络或离线包；不升级 |
| Flyway 迁移失败（后端反复重启） | 立即回滚镜像 + 恢复备份，保留 `docker logs` |
| 冒烟失败 | 脚本已自动回滚；排查冒烟输出后重试 |

# 灰度与回滚 Runbook（PHASE110）

> 目标：值班同学可据此完成「分批放量」与「快速回滚」，无需读部署脚本源码。
> 部署形态：Docker Compose 单机（无服务网格，不做流量染色）。

---

## 1. 灰度（分批放量）

单机 Compose 没有蓝绿/金丝雀基础设施，灰度采用「**前置门禁 + 实例分批**」轻量方案：

### 1.1 分批原则

| 阶段 | 动作 | 前置门禁（必须全绿才进入下一阶段） |
|---|---|---|
| 0 预发 | 在 staging 环境完整部署 + 跑 `launch-final-verify.py` | 全部门禁 PASS |
| 1 内部 | 仅内部用户/管理员访问 | smoke + admin-authz + backup-e2e PASS |
| 2 小流量 | 放 10% 外部租户（白名单） | 观察告警 + 容量基线 |
| 3 全量 | 放开全部 | 容量拐点确认（见 §3） |

### 1.2 每阶段观察项

```bash
# 健康与资源
docker compose ps
# 容量基线（拐点 50 VU，PHASE102 T3）
./scripts/perf-load.sh --duration 30 --threads 8 --connections 50
# 告警
docker compose logs backend-python 2>&1 | grep -E "FAILED|alert" | tail -10
```

**判定**：QPS/P95 在容量基线内、无 5xx 激增、无备份/告警失败 → 进入下一阶段；任一超标 → 停止放量并回滚。

---

## 2. 快速回滚

### 2.1 触发条件

- 健康检查连续失败
- 5xx 错误率 > 阈值（`observability/alerts.yml` 的 5xx > 5% 规则触发）
- 备份/数据完整性异常
- 越权或数据泄露类安全事件

### 2.2 回滚步骤

```bash
# 一键回滚到上一个已知可用版本（tag 记录 + 带重试健康检查）
./deploy.sh rollback
```

`deploy.sh rollback` 行为（PHASE110 强化后）：
1. 读取 `.last-deployed-tag` 得到回滚目标 tag
2. 校验 tag 非空、记录当前 → 目标 tag 对比
3. 用目标 tag 重新拉起
4. **带重试健康检查**（最多 6 次 × 10s，避免首次仍在启动时误判）
5. 仍不通 → 明确提示人工介入（不回退 tag）

### 2.3 数据兼容性说明

- 应用回滚只切镜像 tag，**PostgreSQL 数据不回滚**（数据库 schema 变更需另行评估）
- 若回滚涉及不兼容的 schema 变更，须先执行 `docs/backup-restore-runbook.md` 的恢复流程

---

## 3. 容量与放量上限

来自 PHASE102 T3 容量基线：
- 拐点 **50 VU**（QPS 413/102/99 @ 20/50/100 VU，P95 153/274/517ms）
- 瓶颈：HikariCP 连接池 10 + IM 限流 30/60s
- 放量前确认目标并发在拐点内，否则先扩连接池或加实例

---

## 4. 可验证的回滚验收（红线：不得恒真）

| 验收 | 命令 | 期望 |
|---|---|---|
| tag 已记录 | `cat .last-deployed-tag` | 非空、与镜像一致 |
| 回滚后健康 | `./deploy.sh rollback` 末段 | `/api/health 通` |
| 回滚目标正确 | `docker inspect $(docker compose ps -q backend-java) \| grep -i image` | 含 PREV_TAG |
| 失败可感知 | 人为停库后 rollback | 健康检查 6 次后 **非 0 退出** |

> 最后一条为**可失败性验证**：必须实际制造一次「回滚后 /api/health 不通」确认 `err` 路径会 `exit 1`，不得仅凭代码走查宣布「能回滚」。

---

## 5. 关键文件

| 文件 | 作用 |
|---|---|
| `deploy.sh` | `prod`（阻断默认口令/主密钥、记录 tag）/ `rollback`（带重试健康检查） |
| `.last-deployed-tag` | 记录上次部署 tag |
| `scripts/launch-final-verify.py` | 上线前全量门禁编排 |
| `docs/backup-restore-runbook.md` | 数据层恢复 |

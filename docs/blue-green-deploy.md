# 蓝绿发布（Docker Compose 单机，零停机）

> Wave4 待办池 #13。面向私有化部署的**应用层蓝绿**：新旧两套应用并行，秒级切流量，失败自动回切。

---

## 一、与既有脚本的分工

| 脚本 | 形态 | 停机 | 适用 |
|---|---|---|---|
| `scripts/upgrade.sh` | 单机滚动替换（换镜像 tag → 重启） | 有短暂中断 | 单机、允许秒级中断 |
| `scripts/blue-green-deploy.sh` | **应用层蓝绿**（green 并行 → 切 upstream） | **零停机** | 对 SLA 有要求的单机部署 |

两者共用备份机制：green 与 blue 共享同一份数据库，若新版本含破坏性迁移，回切 blue 后需恢复备份。

## 二、前置条件

1. `.env` 已配置（`POSTGRES_PASSWORD` / `JWT_SECRET` / `MINIO_*` 等必填项）；
2. **新版本镜像已存在于 registry 或本地**（`ghcr.io/whoamiwo/dbcool/dbcool-*:<tag>`）——
   green 副本**不使用本地 build**（拉不到即失败，避免"任意 tag 都能构建成功"掩盖问题）；
3. 前端容器 `nocobase-frontend` 正常运行（承担 upstream 切换）。

## 三、命令

```bash
bash scripts/blue-green-deploy.sh --to 2.1.2     # 蓝绿升级到指定 tag
bash scripts/blue-green-deploy.sh --status       # 查看当前颜色与 green 状态
bash scripts/blue-green-deploy.sh --rollback     # 回切 blue 并清理 green
bash scripts/blue-green-deploy.sh --cleanup      # 仅清理 green 副本
bash scripts/blue-green-deploy.sh --dry-run --to 2.1.2   # 只打印将执行的命令
```

## 四、流程

```
1) 起 green 副本（backend-java-green / backend-python-green / crdt-service-green）
   └ 镜像不可用 → 立即失败退出（exit 1），现网保持 blue
2) 等 green /api/health/ready 就绪（超时 180s）
   └ 未就绪 → 自动回切 blue + 清理 green（现网未受影响）
3) 切 nginx upstream：sed 改 default.conf 的 proxy_pass → nginx -s reload（秒级）
   └ 切后强校验 default.conf 确实指向 green（防"切了但流量没动"的假阳性）
4) 验证公网 /api/health 与 /api/health/ready
   └ 失败 → 自动回切 blue + 清理 green
5) blue 保留运行以备回切；观察稳定后执行 --cleanup
```

## 五、实测状态（2026-10-31）

| 场景 | 结果 |
|---|---|
| 成功路径（tag=2.1.2，本地已有镜像） | ✅ green 就绪 20s → upstream 切到 `backend-java-green` → health 通过 → exit 0 |
| 失败路径（tag=99.99.99，镜像不存在） | ✅ green 启动失败 → **现网保持 blue 且健康** → exit 1 |
| 回切 | ✅ upstream 回到 `backend-java` → health 通过 → exit 0 |
| K8s 灰度（蓝绿） | ⚠️ **未实测** —— K8s 侧当前仅 `RollingUpdate`（`k8s/02-backend-java.yaml` replicas=3 + readinessProbe）；K8s 蓝绿需另加 Deployment 副本 + Service selector 切换，本批未做 |

## 六、实测中修正的三个真实缺陷

1. **切换假成功**：最初用容器内 `render_nginx.py` 重渲染切换，但镜像内的脚本是**构建时的旧版**（无 `BACKEND_COLOR` 逻辑）→ 上游未变而 health 仍通过（假阳性）。改为 **sed 直改 `default.conf` + 切后强校验 upstream**。
2. **任意 tag 都能"成功"**：green 原带 `build:` 段 → 镜像不存在时本地构建，验证失去意义。已移除 build，改为**仅用预构建镜像**（拉不到即失败）。
3. **重渲染破坏 CSP nonce**：重跑渲染会因 `__CSP_NONCE__` 占位符已被注入而回退 `style-src 'unsafe-inline'`。改用 sed 切换后不再触发（`render_nginx.py` 的 `BACKEND_COLOR` 支持保留，供未来镜像构建时使用）。

## 七、注意事项

- green 与 blue **共享数据库与基础设施**，仅应用层双份；green 不暴露宿主端口。
- 切换只改 nginx upstream，**不动前端静态资源**（前端镜像版本随 blue/green 各自独立发布时需另行处理）。
- 切换期间 blue 仍在运行，可任意时刻 `--rollback`。

# 企业客户入职指南（Enterprise Onboarding）

> 适用：以私有化部署方式启用 DBCool 的企业。本指南 + 
> [`acceptance-checklist.md`](./acceptance-checklist.md)（验收清单）构成客户入职的标准流程。
> 按顺序执行，每步都有可验证的产出。

## 一、部署前确认（Day 0）

| 项 | 要求 | 自查命令 |
|---|---|---|
| 服务器 | 4C8G 起步（200 人内），磁盘 ≥ 100GB | `nproc && free -h && df -h` |
| 发行版 | 支持矩阵内（Ubuntu/Debian/RHEL 系/麒麟），见 `docs/install-support-matrix.md` | `cat /etc/os-release` |
| 网络 | 应用端口对内网开放；如需拉镜像需能访问 ghcr.io（或使用离线包） | `curl -sI https://ghcr.io` |
| 密钥准备 | JWT_SECRET（≥32 位随机）、数据库/MinIO 密码、服务间令牌 | `openssl rand -base64 32` |

**离线环境**：直接使用 Wave 1 交付的 `dbcool-offline-<ver>.tar.gz` +
`packaging/offline/install-offline.sh`，跳过镜像拉取。

## 二、安装（Day 1）

```bash
# 在线（推荐开发/测试）
git clone https://github.com/whoamiWo/dbcool.git && cd dbcool
./install.sh all

# 离线（生产内网）
tar -xzf dbcool-offline-<ver>.tar.gz && cd offline
cp .env.example .env   # 填入密钥
./install-offline.sh
```

**产出物（留存归档）**：
1. `docker compose ps` 输出（全部 healthy）
2. `curl http://<host>:8080/api/health` 返回 200
3. 安装日志 `/tmp/nocobase-install-*.log`

## 三、首次配置（Day 1–2）

1. **管理员账号**：使用部署时创建的管理员登录，**立即修改默认密码**。
2. **租户初始化**：确认租户隔离（Schema）生效——用两个不同租户账号登录，
   确认数据互不可见（详见租户隔离审计样例 `docs/tenant-isolation-audit-sample.md`）。
3. **钉钉/企微/飞书/微信嵌入**（可选）：按 `docs/` 下对应集成文档配置凭证，
   走租户级凭证托管（TenantCredentialService），凭证**不落内存**。
4. **备份启用**：确认 WAL 归档与定时备份（`docs/backup-restore-runbook.md`），
   并执行一次恢复演练。
5. **演示数据**（可选，仅非生产）：`python3 scripts/seed-demo-data.py --seed-demo`
   —— 注意这会写入演示集合与工作流（默认关闭是红线）。

## 四、验收（Day 3，详见 acceptance-checklist.md）

跑全量门禁 + 冒烟，留存输出：

```bash
cd backend-java && mvn -o verify          # 后端全测 + 覆盖率红线
cd frontend && npm run test:run           # 前端全测
python3 scripts/launch-final-verify.py    # 上线终验 8/8
python3 scripts/backup-e2e-verify.py      # 备份/PITR 30/30
python3 scripts/smoke.py                  # 冒烟 13/13
```

全部通过后签署验收（模板见 acceptance-checklist.md 末尾）。

## 五、运维交接（Day 4–5）

| 项 | 文档 | 验证方式 |
|---|---|---|
| 升级与回滚 | `docs/upgrade-runbook.md` | `bash scripts/upgrade.sh --help` |
| 密钥轮换 | `docs/key-rotation-runbook.md` | 轮换演练一次 |
| 备份恢复 | `docs/backup-restore-runbook.md` | 已在验收中演练 |
| 监控告警 | `docker-compose.observability.yml` / `k8s/06-monitoring.yaml` | Prometheus/Grafana 可访问 |
| 安全基线 | `SECURITY.md` | 漏洞扫描 0 阻断 |

## 六、支持通道

- 社区：按 [SUPPORT.md](../SUPPORT.md) 走 GitHub Issues（模板）
- 订阅：走签约渠道（SLA 见 `docs/sla.md`）；工单需附环境版本与日志尾部

## 七、入职完成判定（DoD）

- [ ] 验收清单全部通过并归档
- [ ] 备份与恢复演练各一次，记录 RPO/RTO
- [ ] 密钥全部进入密钥管理（无明文落盘）
- [ ] 支持渠道与升级路径已向运维团队宣贯

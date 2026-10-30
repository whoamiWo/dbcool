# 上线验收清单（Acceptance Checklist）

> Wave3 T3.4。逐项执行并**留存命令输出**；全部 ✅ 后由双方签署（见文末模板）。
> 门禁命令与 [`PRODUCTIZATION_ROADMAP.md`](./PRODUCTIZATION_ROADMAP.md) §6 同源。

## A. 部署完整性

| # | 检查项 | 命令 | 通过标准 |
|---|---|---|---|
| A1 | 容器全部健康 | `docker compose ps` | 全部 `Up (healthy)` |
| A2 | Java 健康 | `curl -s http://<host>:8080/api/health` | HTTP 200 |
| A3 | Python 健康 | `curl -s http://<host>:8000/api/health` | HTTP 200 |
| A4 | 版本一致 | `python3 scripts/version-consistency-verify.py` | exit 0（10/10 一致） |
| A5 | 前端可访问 | 浏览器打开 `http://<host>` | 登录页正常渲染 |

## B. 质量门禁

| # | 检查项 | 命令 | 通过标准（基线 2026-10-30 @02b3a59） |
|---|---|---|---|
| B1 | 后端全测 + 覆盖率 | `cd backend-java && mvn -o verify` | 0F/0E；BUNDLE 行覆盖 ≥ 0.79（实测 0.8007） |
| B2 | 前端全测 | `cd frontend && npm run test:run` | 964 passed / 0 failed / 0 unhandled errors |
| B3 | 类型检查 | `cd frontend && npx tsc --noEmit` | 0 errors |
| B4 | Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 0 failed |
| B5 | 依赖漏洞 | `python3 scripts/java-dependency-audit.py` | 无 HIGH/CRITICAL |
| B6 | 前后端契约 | `python3 scripts/api-contract-verify.py` | 0 漂移 |

## C. 业务链路冒烟

| # | 检查项 | 命令 | 通过标准 |
|---|---|---|---|
| C1 | 冒烟（登录/建表/录数/IM/工作流等） | `python3 scripts/smoke.py` | 13/13 |
| C2 | 租户隔离 | `python3 scripts/tenant-isolation-e2e-verify.py` | 16/16 |
| C3 | 登录与权限 | 管理员 + 普通用户各登录一次 | 权限差异符合预期（管理员可进 admin 页面，普通用户不可） |

## D. 数据安全

| # | 检查项 | 命令 | 通过标准 |
|---|---|---|---|
| D1 | 备份 + PITR 演练 | `python3 scripts/backup-e2e-verify.py` | 30/30（含真实时间点恢复） |
| D2 | 备份产物归档 | 检查 `backups/`（或配置的备份目录） | 含 postgres dump 与 manifest |
| D3 | 恢复演练留痕 | 备份恢复 Runbook 步骤 | 已执行并记录 RPO/RTO |

## E. 上线终验

| # | 检查项 | 命令 | 通过标准 |
|---|---|---|---|
| E1 | 综合终验 | `python3 scripts/launch-final-verify.py` | 8/8 |
| E2 | 安全响应头 | `curl -I http://<host>` | 含 CSP / X-Frame-Options 等四头 |
| E3 | WebSocket（IM/Huddle） | 两个浏览器窗口互发消息 / 进语音房间 | 实时可达 |

## F. 交付物归档（由客户与实施方共同签署）

- [ ] A–E 全部通过项的命令输出截图/文本
- [ ] 版本号与构建来源（镜像 tag / 离线包 SHA256）
- [ ] 密钥清单（仅存密钥管理系统，本清单不记值）
- [ ] 已知问题与风险接受记录（如有）

---

### 验收签署（模板）

| 项 | 内容 |
|---|---|
| 部署版本 | v2.1.1 |
| 部署方式 | docker compose / K8s / 离线包（勾选） |
| 验收日期 | ____年__月__日 |
| 客户代表 | ____________ |
| 实施方代表 | ____________ |
| 结论 | ☑ 通过　☐ 有条件通过（遗留项：____）　☐ 不通过 |

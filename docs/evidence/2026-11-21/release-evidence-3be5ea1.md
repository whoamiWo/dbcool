# 2.1.5 — 3be5ea1 最终证据复核（2026-11-21）

## 固定对象

- revision：`3be5ea12bcf81e0aebecf070ccd0f3fe31ba1159`
- 验收时 worktree：`.kilo/worktrees/release-2.1.5-evidence`（detached，无 2.1.6 修复）；本文提交时独立证据分支不表示新的应用 revision
- 隔离栈：`nocobase-acceptance`，PG/Redis/backend 同 project、同网络
- 四容器 image OCI revision 均为 3be5ea1，version=2.1.5，StartedAt=`2026-11-20T14:26:29Z`。
- Java image `sha256:318c7534d10c13f910e5331b806633215740586f5293c587f566087d1cebd5f2`
- Python image `sha256:7fc2e2a0a2284077aad5663b9d713299467974bf57386ae12ca9be45243aacaf`
- CRDT image `sha256:e18655abe6521755aedc7fc3b6851ecdca8d20ec649ea53b17a53b72495cd1e6`
- frontend image `sha256:7be330b6308c30685fbc02c42b9f6121c12917c27fdbb05937efd8738462a0ad`

## 发布门禁复核（本次真实重跑）

命令：`APP_VERSION=2.1.5 ACCEPTANCE_MODE=1 BASE_URL=http://127.0.0.1:18082 FRONTEND_URL=http://127.0.0.1:18080 PYTHON_URL=http://127.0.0.1:18000 PG_CONTAINER=nocobase-acc-postgres REDIS_CONTAINER=nocobase-acc-redis PG_STRICT=1 COMPOSE_PROJECT_NAME=nocobase-acceptance BACKEND_SERVICE=backend-java BACKUP_DIR=$PWD/target/acceptance/backups TRIVY_DB_REPOSITORY=public.ecr.aws/aquasecurity/trivy-db TRIVY_SKIP_DB_UPDATE=true TRIVY_SKIP_JAVA_DB_UPDATE=true python3 scripts/monthly-gate.py --release`

保留原备份 `backup_20261115_061925.tar.gz`（227,081 bytes）；未删卷、未 FLUSHDB、未清理 audit_log。

原始日志：`monthly-gate-release.log`。15 项主门禁全 PASS，只有文档数字 FAIL：声明 80.26，实测 80.25。

实测（target/gate-numbers.json）：Java 2855/0F/0E，JaCoCo 80.25%；前端 978/121；契约 460×137；Python 85/1 skipped；smoke 14/14；租户 24/24；backup/PITR 30/30；launch 8/8。Playwright `npx playwright test --list`=260 tests/13 files（列表检查，不是重跑 260 E2E）。

原 JaCoCo XML：LINE missed=4432 covered=18011。该次文档校验失败不能改写成门禁 PASS。按 D9 更新 README.md、RISK_REGISTER.md、docs/PRODUCTIZATION_ROADMAP.md 的实测数字为 80.25，并补 `playwright.tests=260`；`python3 scripts/doc-numbers-verify.py` 随后 28 锚点 PASS。只重跑该文档校验，完整原始日志不覆盖。

## 连续 24h 观察复核（在证据重跑之前已完成）

有效连续段：**2026-11-20T14:27:30Z 至 2026-11-21T14:54:30Z**（24h27m，超过到期点 14:26:30Z）。不能把此前多次宿主重启/休眠窗口算入。

- Prometheus `up{job="backend-java"}`：1,468 点、0 个 >600s gap；首点 14:27:30Z，末点 14:54:30Z。
- 四应用容器 restarts=0，StartedAt 不变，running/ExitCode=0/OOM=false；宿主 boot=`2026-11-20T14:26:21Z`，复核 uptime=87,945s。
- 2026-11-21T14:55:21Z：Java 18082/Python 18000/frontend 18080 health 全 200。
- Prometheus firing=0，Alertmanager firing=0。
- Java 500 `sum(http_server_requests_seconds_count{status="500"})` 无序列；Python 窗口日志 5xx=0。
- Java 窗口 ERROR/Exception/WARN 匹配 3 行；`[quota]` 刷回失败及 InvalidDataAccessApiUsageException=0；Python ERROR/Exception/Traceback/SIGSEGV=0。
- alerts.db 仅 1 行 2026-11-12 的 `alertmanager.NocoBaseJavaDown`，索引 resolved=0 但 detail.status=resolved、endsAt=`2026-11-12T03:05:15.84Z`，为 H11 历史索引遗留，不是本窗口 firing。

**24h 结论：PASS。** 该结论只覆盖上述已完成的连续段，不声称证据门禁重跑后仍持续无 5xx。

## 证据重跑后的新 500（不掩盖、不回写旧窗口）

完整门禁于 2026-11-21T15:24:30Z 产生 Java 500=1：`DELETE /api/admin/users/{userId}/tenants/{tenantId}`，为租户测试清理请求；Python 5xx=0，四容器仍未重启、health 200、0 firing。该事件发生在上述已完成的 24h 观察段之后，不取消历史连续段，但不得将发布证据写成“截至当前无 5xx”。

若要求“最终一次门禁之后再连续 24h 无 5xx”，需从 `2026-11-21T15:24:30Z` 后重新观察至 `2026-11-22T15:24:30Z`；本文件不声称该新窗口已完成。

## 未发布/待办

- 2.1.6 PR #8/#9 已合入 main，但不属于本发布 revision；H12/H11 修复不在 3be5ea1。
- 原 `release.yml` 的 amd64/arm64 两个 job 均直接推同一 GHCR tag，存在单架构 manifest 相互覆盖风险，且 arm64 continue-on-error；PR #10 修复中，尚未用于发布。
- main 保护已配置：PR + 1 approval，Java/Python/Frontend/Regression 四项必检，禁 force-push/删除，管理员同样受限。
- 本文创建时 v2.1.5 tag、GHCR 发布、GitHub Release 均未创建；R5 外部系统验收仍待完成。

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

## 发布 tag 与工作流验收（2026-11-23）

### 固定发布对象

- 应用 revision：`3be5ea12bcf81e0aebecf070ccd0f3fe31ba1159`（`.release-app-revision`）。
- 发布 tag：[`v2.1.5-3`](https://github.com/whoamiWo/dbcool/releases/tag/v2.1.5-3)。tag commit：`9570e8d130906bc2d42b671216c197be1afe83a5`；该提交为 `3be5ea1` 加发布 CI backport，仅包含 `.github/workflows/release.yml`、`scripts/build-images.sh`、`.release-app-revision`。
- `git diff 3be5ea1 v2.1.5-3` 确认应用源码与配置未变化；OCI `org.opencontainers.image.revision` 对所有镜像均为 `3be5ea12bcf81e0aebecf070ccd0f3fe31ba1159`，`org.opencontainers.image.version=2.1.5`。
- 工作流：https://github.com/whoamiWo/dbcool/actions/runs/45133947389，结论 `success`；Build amd64/native arm64、manifest 发布与校验、GitHub Release 四个 job 均通过。arm64 使用 GitHub 原生 `ubuntu-24.04-arm` runner，避开此前 QEMU 在 `crdt-service` `npm install` 上的 SIGILL。

### GHCR 多架构 manifests 与 digest

以下 digest 为 GHCR index digest；每个 index 恰含 `linux/amd64`、`linux/arm64`。流水线以及对 GHCR registry manifest/config 的独立检查均验证平台和 OCI labels。

| 镜像 | tag | index digest | amd64 child digest | arm64 child digest |
|---|---|---|---|---|
| `ghcr.io/whoamiwo/dbcool/dbcool-backend-java` | `2.1.5-3` | `sha256:44f5f37769855a7401c5f03356544ccb76656dd56008d67183484577397a43ba` | `sha256:ad1b2d1a5d421d39435e8dd66e4e1d785cb9d14023dbd0fa33edbb8c44fc8678` | `sha256:f87b0cbae10911ecc6775cdee465c3ddbce95bde21443c891cede3d6add9861b` |
| `ghcr.io/whoamiwo/dbcool/dbcool-backend-python` | `2.1.5-3` | `sha256:c6cb159ded37a07c67fc71fa6b43cb01779f00fc04f4acf1eb6c46c223f24578` | `sha256:a7f4e4fa2778b0c02b942ed81e9a6f26df249d72d9ed827368e9b9a4a990e5f7` | `sha256:d6b72c9fc087e891566b9902d1508cfb56fa39d817a45e06f32d2ca0b5824eea` |
| `ghcr.io/whoamiwo/dbcool/dbcool-crdt-service` | `2.1.5-3` | `sha256:ac4df5804d72192bae6fd7041f28b322b95145d52996ec8b4161335608d4fa4e` | `sha256:09e24835779991b4a679ec954e00b1c61cbeb1471bffac8f2eb5359af27a14d0` | `sha256:c92739009b2711a2ab805f34f1935feae3c3c026ce137be9a539d27a47fdd03e` |
| `ghcr.io/whoamiwo/dbcool/dbcool-frontend` | `2.1.5-3` | `sha256:ab8b02817af2159548f3c711e583d52e18714bc89828f9f915b6eaa5d316a77c` | `sha256:a32292bb080f2cd07fb41eca37f396852846677b06f60605d8a42f4630a9b76c` | `sha256:bd0e3c61e608e373bbfe4b62174cd27477611b5dd3a8ef43bb7e45f5f4b0a67e` |

### Release 页面与资产

- GitHub Release：<https://github.com/whoamiWo/dbcool/releases/tag/v2.1.5-3>；非 draft、非 prerelease。
- 资产：`RELEASE_NOTES-v2.1.5-3.md`、`SHA256SUMS.txt`。下载的 notes SHA256 为 `7dd54429a83878a804c945b7a0bde377ae7e2deddee975ff5ea05e1bd88dc11f`，与 `SHA256SUMS.txt` 一致。
- 发布说明记录 tag commit 与应用 revision 的区别、外部集成未验收、JWT 轮换禁用、H12/H11 不在 v2.1.5、ROW/CUSTOM 403 与 `created_by` 不自动回填等边界。

### 隔离启动 smoke（有限范围）

- 测试对象：GHCR `dbcool-backend-java:2.1.5-3` amd64 image，独立临时 Docker network、全新 pgvector/PostgreSQL volume 与一次性 Redis；未使用/重启/删除任何既有容器或数据卷。测试结束后只删除本次创建的容器、network 和 volume。
- PostgreSQL Flyway 从空库成功应用 82 migrations 至 V85；注入独立临时 JWT 与 AES-256 integration key。Java 启动健康；`GET http://127.0.0.1:28082/api/health` 返回 200，body `status=ok, service=nocobase-backend, version=2.1.5`；容器内 `java -version` 为 Temurin 21.0.13.1。
- 初次以镜像默认 `./alerts.db` 路径启动时，非 root 用户遇到 SQLite permission denied；隔离 smoke 通过设置 `NOCOBASE_ALERTS_PATH=/tmp/release-smoke-alerts.db` 后成功。这次测试未验证默认 Compose 中 `/data` 持久化挂载行为。
- 限制：只执行健康 API 与 JVM 运行检查，**不等价于 `scripts/smoke.py` 14 项业务 smoke**，不证明外部集成、备份/PITR、24h 观测或生产部署路径通过。
- arm64 runtime 未在本机执行：宿主为 x86_64，直接 `docker run --platform linux/arm64` 因本机无 ARM64 emulator 报 `exec format error`。因此 arm64 的构建、GHCR index、架构声明及 OCI labels 已核验，但 arm64 容器实际启动仍未验证；不能标记为 PASS。

### Retry 历史与未完成项

- `v2.1.5` 首次工作流在 digest-only exporter 初始 push 处失败；未创建版本化 manifest/Release。
- `v2.1.5-1` 的两个架构 digest 构建与 push 成功，但 manifest job 缺少 checkout、找不到 `VERSION`；留下的 untagged digest 可能仍在 GHCR，未创建版本化 manifest/Release。
- `v2.1.5-2` 因 arm64 QEMU 在 CRDT `npm install --omit=dev` 上 SIGILL 被取消；未完成 arm64 digest，未创建 manifest/Release。
- 未移动或删除上述 tag；最终经原生 ARM64 runner 成功发布为 `v2.1.5-3`。
- H12 quota flush 与 H11 alerts resolved 修复属于 2.1.6（PR #8/#9 已在 main，不包含在 `3be5ea1` 应用 revision）。上述原始 24h 观察结论只覆盖 2026-11-20 至 2026-11-21 的已完成区间，不声称此后重新观察了 24h。
- 钉钉/飞书/OIDC 真实外部验收仍待凭证与单独验收；相关生产开关保持关闭。

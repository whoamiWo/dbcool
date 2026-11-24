# 2.1.5 24h 观察到点复核 — revision 3be5ea1

## 发布身份与镜像
- 代码 revision：`3be5ea12bcf81e0aebecf070ccd0f3fe31ba1159`（2.1.5 merge commit）
- 发布代码没有包含 2.1.6 的 PR #8/#9；PR #8/#9 为独立后续修复。
- Java image：`sha256:318c7534d10c13f910e5331b806633215740586f5293c587f566087d1cebd5f2`
- Python image：`sha256:7fc2e2a0a2284077aad5663b9d713299467974bf57386ae12ca9be45243aacaf`
- CRDT image：`sha256:e18655abe6521755aedc7fc3b6851ecdca8d20ec649ea53b17a53b72495cd1e6`
- frontend image：`sha256:7be330b6308c30685fbc02c42b9f6121c12917c27fdbb05937efd8738462a0ad`
- 四容器 OCI `org.opencontainers.image.version=2.1.5`，`org.opencontainers.image.revision=3be5ea12bcf81e0aebecf070ccd0f3fe31ba1159`。
- 本机仅单架构验收镜像；GHCR 多架构产物仍以发布流水线结果为准，不能由此推断已发布。

## 有效观察窗口
- 四应用容器 StartedAt：`2026-11-20T14:26:29Z`；宿主 boot=`2026-11-20T14:26:21Z`。
- 有效监控采样：`2026-11-20T14:27:30Z` 至 `2026-11-21T14:51:30Z`，共 24h24m。
- Prometheus `up{job="backend-java"}`：1,465 点，全部值=1，`>300s` 空档=0。
- 到点复核：`2026-11-21T14:52:06Z–14:55:21Z`。
- 容器：四件 `RestartCount=0`、`StartedAt` 未变、`running`、`ExitCode=0`、`OOMKilled=false`。
- readiness：Java 18082 / Python 18000 / frontend 18080 均 HTTP 200。
- Prometheus `/api/v1/alerts` 与 Alertmanager `/api/v2/alerts` 均 0 firing。
- Java `http_server_requests_seconds_count{status="500"}` 复核时无序列。
- Python 自 `2026-11-20T14:26:30Z` 起日志 5xx=0。
- Java 日志 `[quota] 刷回 DB 失败` / `InvalidDataAccessApiUsageException`=0。
- Python `ERROR/Exception/Traceback/SIGSEGV`=0；Java `ERROR/Exception/WARN` 共 3 行，均非 quota 更新事务异常。

## 历史中断（不计入本次窗口）
- 2026-11-15/16、11-18/20 多次宿主休眠、暂停或重启，使此前窗口失效；历史尝试没有拼接为 24h。
- 本次有效窗口开始于 11-20 最新宿主周期，连续监控覆盖完整 24h，没有把中断时段当作观察证据。

## alerts.db 历史索引（非本窗口 firing）
- 到点时仅 1 行历史 `alertmanager.NocoBaseJavaDown`：索引 `resolved=0`，但 detail 明确
  `status=resolved`、`startsAt=2026-11-12T02:11:15.84Z`、`endsAt=2026-11-12T03:05:15.84Z`。
- 这是 H11 修复前的历史索引遗留，不是本窗口新增 firing；未删除或改写该行。

## 证据重跑后的新增 500（不隐藏）
- `2026-11-21T14:55:21Z` 到点复核通过后，为归档当前 3be5ea1 门禁输出，于 15:12Z 左右重新运行 release 门禁。
- `2026-11-21T15:24:30Z`，租户清理门禁新增 Java 500=1：
  `DELETE /api/admin/users/{userId}/tenants/{tenantId}`，Prometheus `exception=none`、`error=none`。
- 这发生在上述已完成观察段之后，不回溯改写 14:55Z 的复核结果，也不能称当前累计无 5xx。
- 四容器未重启、镜像未变、health 仍 200、Prometheus/Alertmanager 仍 0 firing，Python 仍 0 5xx。
- 发布门禁使用既有验收栈，会写测试数据；该清理 500 是发布后观察的风险项，后续重跑发布门禁必须重新计时。

## 结论
- WP-E 的完整连续 24h 段在 `2026-11-21T14:51:30Z` 达成：无容器重启、无 firing、无 Java/Python 5xx。
- 本文不是“发布已上线”或“GHCR 已可拉取”的证明；GHCR 多架构、GitHub Release 与外部验收另按 R3–R5 验收。

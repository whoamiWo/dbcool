# PHASE110 上线终验交付报告（含独立审计修正）

> 本报告记录 PHASE110「上线终验」的**交付 + 独立审计**结果。
> 审计原则（红线 1/2/3/6）：不得把未做写成已完成；数字必须可落到 `文件:行号`；
> 发现文档与代码不符时以代码为准并回报差异。
>
> **审计结论前置**：初版报告曾写「✅ 可对外上线」，经实测审计后**该结论被推翻** ——
> 交付的 3 个脚本存在必然失败缺陷（已修），且 P0-3 合规假实现**仍然真实存在**，
> 因此对外 SaaS 上线结论**维持 ❌**（详见 §5）。

---

## 1. 审计发现并修复的缺陷（本批真实产出）

初版交付后，我对自己写的脚本做了**实跑审计**（而非仅语法检查），发现并修复 5 个缺陷：

| # | 对象 | 缺陷 | 实测证据 | 修复 |
|---|------|------|----------|------|
| 1 | `launch-final-verify.py` 首项 | 调 `security-gates.py` 未传子命令（该脚本 `cmd` 必填）→ 首项 exit 2，编排恒 0/8 | `error: the following arguments are required: cmd` | 改为自适应：有 trivy 用 `scan-deps`，否则用离线替代 `java-dependency-audit.py` |
| 2 | `launch-final-verify.py` 第 7 项 | 传 `--base-url` 给已改用 `BASE_URL` 环境变量的 `key-rotation-verify.py` → exit 2 | `unrecognized arguments: --base-url` | 移除该参数（编排已注入 `BASE_URL`） |
| 3 | `launch-final-verify.py` 整体 | 8 个子脚本各登录 1 次，累计超 `ratelimit.login=5/300s` → 第 6+ 个脚本登录 429 | smoke 报 `login 返 429` | `run_script` 前加 `clear_rate_limit()` |
| 4 | `quota-e2e-verify.py` 场景一 | 3 个叠加 bug：① 上传未用 `multipart/form-data`；② 文件类型 `octet-stream` 不在白名单；③ 配额设在 `tenant_c` 但上传归属 `tenant_default` | 依次 500 / 400 / 200 | 改为 `image/png` + 正确 multipart + 作用于当前用户租户 |
| 5 | `quota-e2e-verify.py` 场景二 | `used` 取自 `/api/tenant/quota`（当前用户租户）却把 limit 设到 `tenants[0]`（tenant_c）→ 后端正确放行 201 被误判 | link 返 201 | 统一到当前用户租户（`token_tenant` 从 JWT 解析 tid） |

**关键说明**：缺陷 4/5 曾一度让我误判为「席位配额可绕过/超卖」。经清理脏数据并修正租户错配后，
`quota-e2e-verify.py` 实测 **5/5 通过**，存储配额（403「存储空间不足」）与席位配额
（403「席位已满」+ 放宽后 201 反证）**均真实生效**。原「超卖」判断**不成立，已撤回**。

---

## 2. 重写的核心脚本：key-rotation-verify.py

初版 `key-rotation-verify.py` 是**虚假交付**：未带 admin token 时把 401 记为 FAIL（恒失败），
且即便带 token 也只验证「端点返回 200」，并未验证密钥轮换的核心语义。

已重写为**真验证**（自动登录 admin，实测三条断言）：

| 断言 | 命题 | 实测结果 |
|------|------|----------|
| 断言 1 | 轮换前旧密钥签发的 token，rotate 后**仍被接受**（双密钥窗口） | ✅ 200 |
| 断言 2 | 轮换后新登录 token **被接受**（新密钥生效） | ✅ 200 |
| 断言 3 | 密钥环中 **ACTIVE 恰好 1 个** | ✅ ACTIVE=1（总 2） |

实测输出（rotate 后 kid 由 `k-0` → `k-1`）：
```
✅ PASS: rotate 成功，新 kid=k-1
✅ PASS: 断言1 通过：轮换后旧 token 仍被接受（双密钥窗口生效）
✅ PASS: 断言2 通过：轮换后新登录 token 被接受（新密钥生效）
✅ PASS: 断言3 通过：密钥环 ACTIVE 数=1（总 2）
```
依据 `JwtKeyRotationController.java:20-24` 注释（RETIRED key 在 access TTL 内仍可验签），
双密钥窗口**实测生效**，非代码走查结论。

---

## 3. 实测门禁（审计后重跑，非初版声称）

| 门禁 | 结果 |
|------|------|
| `launch-final-verify.py` 全编排 | **8/8 通过**（exit 0） |
| `key-rotation-verify.py` | 3/3 断言通过 |
| `quota-e2e-verify.py` | **5/5 通过**（修正脚本 bug 后） |
| `java-dependency-audit.py`（离线替代） | 222 依赖 / 0 高危（1 条 MEDIUM 误报） |
| `cert-lifecycle.sh check` | exit 1（本地无证书 → 可失败性成立） |
| Java `mvn -o verify` | 2213 tests / 0F/0E，JaCoCo 85.73% |
| 前端 vitest / tsc / eslint | 382 passed / 0 errors / 0 warnings |
| Playwright E2E | 135 passed |

**注意**：`cert-lifecycle.sh` 的 `issue` / `renew` 需要 certbot + 域名 + 80 端口可达，
本地**无法实测**，仅 `check` 路径可失败性已验证。此项如实登记为环境限制，不得视为已验证可用。

---

## 4. P0 逐项复验（以代码为准）

| P0 | 主题 | 状态 | 证据 |
|----|------|------|------|
| P0-1 | 授权层 | ✅ 已修复 | `AuthController.java:124` 用真 `roleNames`；`SecurityConfig.java:100-101` 仅放行 `/actuator/prometheus` |
| P0-2 | 租户隔离 | ✅ 已修复 | `UserAdminService`（租户过滤）；`WikiPageRepository` 带 tenantId；越权回归脚本全 PASS |
| P0-3 | 假实现/静默成功 | ❌ 仍存在 → **PHASE111 已修复** | `UserDataErasureService.java:169-175` `eraseAiConversations()` 仅 `log.warn` + `incrementConversationsErased(0)`，外层 `setSuccess(true)`；PHASE111 补齐仓储匿名化 + 残留校验 + 失败抛异常，`user-data-e2e-verify.py` 15/15 |
| P0-4 | 前后端契约 | ✅ 已修复 | `api-contract-verify.py` **0 漂移** |
| P0-5 | 备份可恢复 | ✅ 已修复 | `backup-e2e-verify.py` 30/30；`docker-compose.yml:318-319` backupsdata 命名卷 |
| P0-6 | 默认密钥/端口/TLS | ✅ 已修复 | compose 全 `${X:?未设置}`；端口 `127.0.0.1`；`nginx.conf:22-36` 443+HSTS |
| P0-7 | 可观测性 | ✅ 已修复 | `MdcFilter.java:28-40` traceId；`alert_store.py` SQLite；Prometheus 抓取配置存在 |

---

## 5. 上线结论（审计修正后）

### ⚠️ 对外多租户 SaaS：**PHASE110 交付时仍不可上线（阻断项 P0-3 至 PHASE111 才闭环）**

唯一阻断项：
- **P0-3 合规缺口**：`UserDataErasureService.eraseAiConversations()` 在 PHASE110 仍是假实现，
  AI 会话**实际未被删除**却 `setSuccess(true)`（静默成功，违反红线 2）。
  对外 SaaS 涉及 GDPR / 个人数据删除权，此项为**合规阻断**，不能以"其余 6 项已修"冲抵。
- **后续**：PHASE111 已补齐仓储匿名化 + 残留校验 + 失败抛异常，`user-data-e2e-verify.py` 15/15，
  阻断项闭环，对外 SaaS 上线结论由 ❌ 转 ✅（详见 `PHASE111_ERASURE_REPORT.md`）。

### 已达标部分

- 运维闭环：证书生命周期、密钥轮换验证、灰度/回滚 runbook、上线编排（8/8）
- 安全与隔离：授权、租户隔离、备份可恢复、密钥与端口、可观测性均实测通过
- 质量门禁：覆盖率 85.73%、E2E 135、契约 0 漂移

### 转为 ✅ 的前置条件（PHASE111）

1. 实现 `AiConversationEntityRepository` 按 `userId` 的查询，并接入
   `eraseAiConversations()` 真正匿名化；删除成功前不得 `setSuccess(true)`
2. 为该删除路径补**可失败**端到端验证（断言删除后按 userId 查不到会话）

---

## 6. 交付物清单

| 类型 | 文件 | 实测状态 |
|------|------|----------|
| 脚本 | `scripts/cert-lifecycle.sh` | `check` 已实测（exit 1 可失败）；`issue/renew` 未实测（需 certbot+域名） |
| 脚本 | `scripts/key-rotation-verify.py` | 重写并实测 3/3 |
| 脚本 | `scripts/launch-final-verify.py` | 修 3 处 bug 后实测 8/8 |
| 脚本（修） | `scripts/quota-e2e-verify.py` | 修 2 处租户/multipart bug 后实测 5/5 |
| Runbook | `docs/key-rotation-runbook.md` | 文档 |
| Runbook | `docs/release-rollout-runbook.md` | 文档 |
| 基线 | `scripts/baseline-freeze.txt` | 实测数据 |
| 报告 | `docs/PHASE110_UPGRADE_REPORT.md` | 本文件 |

---

## 7. 遗留项（强制）

1. **P0-3 AI 会话删除假实现**（阻断，转 PHASE111）
2. `cert-lifecycle.sh` 的 `issue` / `renew` 未实测（需真实域名与 certbot）
3. `security-gates.py` 官方 trivy/zap 路径本地无二进制，仅离线替代可跑
4. `quota-e2e-verify.py` 在脏数据（残留关联）下会因计数漂移误报，跑前需确保租户干净

---

*报告生成：2026-10-17（含独立审计修正）*
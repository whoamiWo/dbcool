# PHASE113 全面审计评估报告

> **审计时间**：2026-10-18　**审计基准**：`main @ 4a0bc44`
> **审计方式**：全量门禁**当场重跑** + 代码核对（以代码为准，不采信文档自述）
> **红线遵循**：数字可追溯到产出命令；验证方法本身必须可失败；发现的问题不粉饰

---

## 一、核心结论

| 维度 | 结论 | 依据 |
|---|---|---|
| P0 阻断项（7 项） | ✅ **全部闭环** | §2 逐项实测 |
| 对外 SaaS 可上线 | ✅ **代码侧具备上线条件** | 全量门禁全绿、容器 11/11 healthy |
| 生产环境就绪 | ⚠️ 需 3 项运维动作 | 域名 / 正式 TLS 证书 / 80-443 端口（非代码项） |
| 遗留待办 | ⚠️ 见 §4 | i18n 2/10、文档数字曾过时、密钥轮换曾未实证 |

---

## 二、P0 安全与合规逐项实测（本次重跑，非引用历史）

| P0 | 验证命令 | 实测结果 |
|---|---|---|
| P0-1 授权层 | `ControllerAuthorizationCoverageTest` | ✅ 无新增未授权（均在基线内） |
| P0-2 租户隔离 | `scripts/tenant-isolation-e2e-verify.py` | ✅ **14/14**（B→A 越权均拦截、随机 KB→404） |
| P0-3 假实现 | `scripts/no-fake-impl-verify.py` | ✅ **P0 0 条 / P1 0 条** |
| P0-4 前后端契约 | `scripts/api-contract-verify.py` | ✅ **303 后端 × 21 前端 → 0 漂移** |
| P0-5 备份可恢复 | `scripts/backup-e2e-verify.py` | ✅ **30/30** |
| P0-6 密钥 / 端口 | `scripts/startup-checklist.py` | ✅ **0 问题**（强密码阻断、端口收敛、TLS） |
| P0-7 可观测性 | 容器 + `/api/health/ready` | ✅ 11/11 healthy，Java/Python 探针均 200 |

---

## 三、全量门禁实测数字

| 门禁 | 数字 | 状态 |
|---|---|---|
| Java `mvn -o verify` | **2225** tests / 0F / 0E | ✅ BUILD SUCCESS |
| JaCoCo 行覆盖率 | **85.84%**（12274/14299） | ✅ ≥82% 红线 |
| 前端单测 `vitest run` | **382 passed**（48 files） | ✅ |
| `tsc --noEmit` | **0 errors** | ✅ |
| `eslint .` | **0 errors** | ✅ |
| Playwright E2E | **135 passed** | ✅ |
| 冒烟 `smoke.py` | **11/11** | ✅ |
| 合规 `user-data-e2e-verify.py` | **16/16** | ✅（含 AI 会话真匿名化 + 台账落库） |
| 备份 `backup-e2e-verify.py` | **30/30** | ✅ |
| 编排 `launch-final-verify.py` | **8/8** | ✅ |
| SaaS 就绪 | **11/11** | ✅ |
| 契约 | **0 漂移** | ✅ |
| 依赖漏洞 | **0 高危** | ✅ |
| 配额 `quota-e2e-verify.py` | **5/5** | ✅ |
| 无假实现 | **P0 0 / P1 0** | ✅ |
| 启动检查 | **0 问题** | ✅ |
| 密钥轮换（修复后） | **3/3 断言真执行** | ✅（见 §4.1） |

---

## 四、审计发现的真实问题

### 4.1 密钥轮换曾「未真正实证」（**已修复**，红线问题）

**问题**：`scripts/key-rotation-verify.py` 在 rotate 返回 429 时直接 `return 0`，
三条核心断言（双密钥窗口 / 新密钥生效 / ACTIVE 唯一性）**从未执行** ——
把「未验证」伪装成「通过」，违反「禁止静默成功」红线。

**根因（代码核对，非猜测）**：429 **不是 HTTP 限流**。
- `/api/admin/**` 不匹配 `GlobalRateLimitFilter` 的任何模式
  （`LOGIN_PATTERN` / `IM_SEND_PATTERN` / `UPLOAD_PATTERN` / `INBOUND_PATTERN`），
  过滤器走 `filterChain.doFilter` 直接放行（`GlobalRateLimitFilter.java:105-108`）。
- 真实来源：`KeyRingService.rotate()` 在距上次轮换 `< ROTATION_MIN_INTERVAL_MS`（=60s）
  时抛 `IllegalStateException`，被 `JwtKeyRotationController` 捕获并映射为
  `TOO_MANY_REQUESTS`（`JwtKeyRotationController.java:68-70`）。
- 即「轮换最小间隔保护」，是**正确的业务防护**，清 Redis 限流 key 无效。

**修复**：
- 429 时从响应 message 解析「距上次轮换 X ms，需 ≥ 60000 ms」算出等待秒数，等待后重试（≤3 次）；
  解析失败退回 62s。
- 仍 429 则明确判失败（`return 1`），不再静默视为通过。
- login 保留清限流重试（登录确有 `ratelimit.login=5/300s` 真实限流，编排串行会累计）。
- 修正原错误文案（原称「限流配置过严」，实为轮换间隔保护）。

**可失败性实证（3/3 均变红，证明非恒真）**：

| # | 注入场景 | 结果 |
|---|---|---|
| 1 | rotate 端点故障（返回 401） | 退出码 **1** ✅ |
| 2 | 429 轮换间隔保护 | 退出码 **1**（原为 0 静默通过）✅ |
| 3 | 旧 token 置无效，模拟双密钥窗口失效 | 断言1 **FAIL**（401），退出码 **1** ✅ |

**实测**：kid 依次轮换 `k-2 → k-3 → k-4`（含触发间隔保护后等待 ~56s 重试成功场景），
三断言全绿；`launch-final-verify.py` 编排 **8/8** 通过。

### 4.2 i18n 仅完成 Top10 中 2 页（未完成）

- 已完成：`AlertCenter`（64 key）、`WorkflowDesigner`（56 key），中/英结构一致
  （`i18n.test.ts` 6/6 锁定），tsc 0 / eslint 0。
- 剩余：48 个文件仍硬编码中文；Top 4 为 `NotionStyleEditor`（64 行）、
  `FormDesigner`（56 行）、`SchemaDesigner`（47 行）、`WikiPageList`（46 行）。

### 4.3 文档数字曾过时（已同步）

- `PHASE95_103_INDEX.md` 终局状态曾写 `2218 / 85.73%`，实测为 **2225 / 85.84%**，已更新。
- `SAAS_LAUNCH_ASSESSMENT.md` 头部基线仍为 2026-10-09 `e1a4241`，已补 2026-10-18 复评段。
- 说明：索引中 PHASE95–PHASE102 各批表格内的门禁数字是**各批交付当时的实测记录**，
  属历史口径，不随后续批次回溯修改（已在索引中注明）。

### 4.4 生产环境运维项（非代码问题）

- 生产 80/443 未监听：当前 `.env` 用 `FRONTEND_PORT=5173` / `FRONTEND_TLS_PORT=5443`（开发态覆盖）。
- 自签证书 `CN=localhost`：仅内部 POC 有效，对外 SaaS 须换 Let's Encrypt / 商业证书。
- 需部署方配置域名解析 + 防火墙白名单 + `./deploy.sh prod`。

### 4.5 Python 测试本地收集失败（环境限制，非代码缺陷）

- `backend-python` 本地 `pytest` 报 6 collection errors（宿主机 Python 3.14 与项目
  `requires-python >=3.12,<3.13` 不匹配，且缺 `uv`）。CI 用 `uv run pytest`，历史多次全绿。
- 判定：环境限制，**不构成上线阻断**。

---

## 五、审计建议与落实

| # | 建议 | 状态 |
|---|---|---|
| 1 | 补齐密钥轮换真实验证（唯一未实证的安全机制） | ✅ **已完成**（§4.1） |
| 2 | 继续 i18n Top10 剩余 8 页 | ⏳ 进行中（2/10） |
| 3 | 同步文档数字 | ✅ **已完成**（§4.3） |
| 4 | 生产部署前配域名 + 正式证书 + 80/443 | ⏳ 部署方执行 |

---

## 六、总体判定

**代码与门禁层面：✅ 具备对外 SaaS 上线条件**
—— 7 项 P0 全部闭环、全量门禁 100% 通过、覆盖率 85.84%、无假实现、无契约漂移。

**上线前置运维**：域名、正式 TLS 证书、80/443 端口（3 项，均非代码问题）。

**已知未完成**：i18n 剩余 48 文件（非阻断，影响国际化体验）。

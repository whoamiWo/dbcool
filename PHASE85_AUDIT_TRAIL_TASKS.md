# PHASE85 任务书：补齐剩余 14 条审计留痕缺口（凭据 / 认证 / 业务）

> 选题不是我拍的 —— 是 **PHASE84 建的覆盖度扫描器自己报出来的**。
> 基线文件 `backend-java/docs/audit-trail-baseline.txt` 现有 14 条未留痕写操作。
> 这正是做机制的价值：不再需要人工逐条 grep，它会持续指出缺口。
>
> 本批做完后，基线应归零（或只剩明确标注无需留痕的条目）。

---

## §1 缺口清单与分组（扫描器输出，我已核对代码形态）

### A 组：凭据与租户管理 —— **优先级最高**

| 位置 | 现状 | 为什么最急 |
|---|---|---|
| `auth/JwtKeyRotationController.java:48 rotate` | 触发 JWT 密钥轮换（新 ACTIVE，旧 → RETIRED），**无任何留痕**；且该方法**没有 `AuthenticatedUser` 参数**，需从 SecurityContext 取操作者 | **谁在什么时候换了签名密钥，无从得知** —— 这是全站凭证的根，被换掉等同于可伪造任意 token |
| `tenant/TenantController.java:55 create` | `@PreAuthorize("hasRole('ADMIN')")` 创建租户 | 平台级操作 |
| `tenant/TenantController.java:63 disable` | `@PreAuthorize("hasRole('ADMIN')")` 禁用租户 | 等同"停用一整个客户" |
| `tenant/UserTenantController.java:81 unlink` | 解绑用户-租户关系 | 权限边界变更 |

### B 组：认证入口 —— **必须同时记成功与失败**

| 位置 | 现状 |
|---|---|
| `auth/AuthController.java:57 login` | 有两条失败分支：`findByUsername` 为空 → 401；`passwordEncoder.matches` 不匹配 → 401。目前**成功和失败都无留痕** |
| `auth/AuthController.java:95 refresh` | 令牌刷新无留痕 |
| `integration/dingtalk/DingTalkController.java:106 login` | 第三方登录无留痕 |
| `integration/dingtalk/DingTalkController.java:170 logout` | 登出无留痕 |

**为什么"失败也要记"**：只记成功的话，有人拿 1000 个密码撞一个账号，
审计日志里一片空白 —— **暴力破解在日志上完全不可见**。
登录失败尝试是安全审计的核心信号（本项目登录已有 `ratelimit.login` 5/300s 限流，
但限流只能挡，不能留证据）。

**实现提示（别踩）**：`login` 的失败分支是 `orElseThrow(...)` 直接抛出的，
**埋点不会被执行到**。要么改成先查再判断（用 `Optional` 判空后手动记 + 抛），
要么用 try/catch 包裹 —— 二选一，但**必须保证失败路径真的走到了 `auditService.log`**。

### C 组：业务操作

| 位置 | 操作 |
|---|---|
| `alert/web/AlertController.java:82` / `:91` | 告警 resolve / subscribe |
| `integration/dingtalk/DingTalkController.java:188` | 创建审批 |
| `im/ImSlashController.java:41` | 斜杠命令执行 |
| `ticket/TicketController.java:26` | 创建会话 |
| `workflow/MessageController.java:83` | 消息标记已读 |

---

## §2 四项任务

### T1（P0）A 组：凭据与租户管理

- `JwtKeyRotationController#rotate`：
  - **payload 只记 `newKid`（密钥 ID），严禁记密钥内容/secret**
  - 该方法无 `AuthenticatedUser` 参数 —— 从 `SecurityContextHolder` 或 `TenantContext` 取操作者；
    确实取不到时 userId 记 `"system"` 并在 payload 标注 `operator_source: security_context_missing`
- `TenantController#create` / `#disable`：payload 含租户 id / name / slug（disable 记 before/after）
- `UserTenantController#unlink`：payload 含 userId 与 tenantId

### T2（P0）B 组：认证入口（成功 + 失败都要）

- `AuthController#login`：
  - 成功：记 username、userId、tenantId
  - **失败**：记 username（请求里的）、失败原因分类（`user_not_found` / `bad_password`）
  - **严禁**记 `password`、`access_token`、`refresh_token`
- `AuthController#refresh`：记结果（成功/失败原因）
- `DingTalkController#login` / `#logout`：同理，第三方登录成功/失败

### T3（P1）C 组：业务操作

按 A/B 组同样标准补齐，payload 含关键参数（不要空对象）。

### T4（P0）收尾

- **每修一条，从 `docs/audit-trail-baseline.txt` 删除对应条目**
  （扫描器按条目集合比对，留着已修条目会让将来回归检测不到）
- 每条至少配一个测试，断言**审计记录真的写进去了**（不是只调了方法）
- 最后跑 `AuditTrailCoverageIntegrationTest` 确认通过

---

## §3 红线（违反即打回）

1. **严禁在 payload 里记密码 / token / refresh_token / 签名密钥 / Cookie。**
   本批碰的是登录与密钥轮换，是泄露风险**最高**的一批 ——
   payload 序列化进 `payloadJson` 落库，一旦写进去就是明文泄露。
2. **严禁只记成功不记失败**（尤其登录）—— 没有失败记录就无法发现暴力破解。
3. **密钥轮换埋点只记 kid，不记密钥内容**。
4. **严禁放宽扫描器规则来消除条目** —— PHASE81 为降数字往审计器塞 `callback(`、
   `token(` 这类宽泛模式，导致探针实测失明；PHASE83 又出现埋点逻辑写在 `main()` 里、
   门禁根本没接上。**发现漏 = 补埋点**，不是改规则。
5. **严禁 payload 空对象** —— 记了等于没记。
6. 严禁修改已应用的迁移文件。

## §4 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1389** |
| `cd frontend && npm run test:run` | **> 382**（前端若无改动，如实写 382，不要虚报） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `AuditTrailCoverageIntegrationTest` | 通过，且**基线条目显著减少**（目标 0） |

## §5 交付清单

1. 14 条逐条处置表：`文件:行号` → action 名 → payload 字段 → 已修/暂不修（说明理由）
2. 登录**失败**路径的实测证据（贴测试断言或 curl）：失败登录也产生了审计记录
3. 密钥轮换的 payload 实测输出（证明只有 kid，无密钥内容）
4. 基线文件 diff（删了哪几条）
5. 门禁六项**实测数字** + 提交 hash + `git status`

## §6 上一批的教训（别重犯）

- PHASE83 回报称 vitest 392 / playwright 69，但**前端零改动** —— 数字在算术上不可能。
  **回报数字要和改动范围对得上**，前端没改就写原值。
- PHASE83 回报称 Python 侧 `export_service.py:194` 有埋点，**该文件根本不存在**（误报）。
  不确定的先查文件是否存在再写。

# 外部集成人工验收清单（Round 3）

> 本文覆盖**无法在离线 CI 中闭环**的外部依赖项：钉钉审批去程、真实 OIDC IdP、真实飞书租户。
> 代码完成标准见 T4/T5 单测；本文只记录需要凭证/公网环境的验收步骤与证据格式。
> 状态列由执行人填写「未验证 / 通过 / 失败 + 日期」。

---

## A. 钉钉审批去程（人工，需企业应用凭证）

前提：

- ADMIN 经 `/api/admin/integration-credentials/dingtalk` 配置 `app-key/app-secret/token/encoding-aes-key/corp-id`；**不得**使用全局凭证跨租户。
- 目标模板已配置 `external_process_code/external_config`，含 corpId、表单映射、approvers 或 `OA_TEMPLATE + deptId`；申请人有启用的 `DINGTALK/external_id`。
- 已执行 V83/V84（V84 将回调幂等键扩展为 corpId+eventId），配置公网 HTTPS `POST /api/dingtalk/approval-callback?msg_signature={msg_signature}&timeStamp={timeStamp}&nonce={nonce}`，body `{"encrypt":...}`；先通过 `check_url` 加密 ACK。
- 开关 `approval.dingtalk.push.enabled=true`（默认关闭，真实验收前不得开启）。
- 官方创建接口 `POST /v1.0/workflow/processInstances` 的响应字段为 `instanceId`；事件按 `processCode/processInstanceId` 精确绑定。

| # | 步骤 | 预期 | 证据 | 状态 |
|---|---|---|---|---|
| A1 | 本地提交一条审批 | 本地 `approval_request` 创建成功 | `approval_request.id` + 提交时间 | 未验证 |
| A2 | 检查钉钉 OA | 出现对应审批实例 | `approval_request.external_instance_id` 非空 + 钉钉实例截图/ID | 未验证 |
| A3 | 钉钉侧「通过」 | 回调推进本地流程，任务状态变化一次 | 回调时间戳 + `workflow_task.status` 前后值 | 未验证 |
| A4 | **同一回调重放 2 次** | 只推进一次，不重复流转 | 两次请求时间戳 + 任务状态仍为已完成 | 未验证 |
| A5 | 钉钉侧「驳回」 | 本地流程进入驳回态 | 任务状态 + 审批意见 | 未验证 |
| A6 | 关闭 `approval.dingtalk.push.enabled` 后提交外推模板 | 返回 409，不创建卡死实例，不外发；普通本地模板不受影响 | HTTP 状态 + 无外部请求 + 本地普通审批结果 | 未验证 |
| A7 | 两个租户同时验收 | token/回调均使用正确租户；corpId 与 binding 匹配 | 租户、实例、binding 映射（无 secret） | 未验证 |
| A8 | 错签名/错 corpId/无绑定/冲突结果/重复重放 | 不推进、不写非法状态；失败不返回成功 ACK | HTTP 状态 + event 状态机记录 | 未验证 |
| A9 | 外部 HTTP 失败与 `UNKNOWN_BINDING` | 单据保留；补绑定不重复创建 OA | pushStatus/error/实例 ID 与管理员重试结果 | 未验证 |
| A10 | 禁用/离职申请人与停用模板 | 禁止外推；管理员重试同样拒绝 | 零外部请求 + HTTP 状态 | 未验证 |

> A3/A5 验收的是 `approval_request` 与 `workflow_instances` 状态及外部后续节点，不要求创建伪造的本地人工待办。
> 当前仅有本地 mock、真实 PG repository/迁移测试；A1–A10 均尚未执行，不得将功能标为生产可用。

> 若 A2 失败，先确认租户凭证是否被 `TenantCredentialService` 解析到（日志 `[DingTalk]` 不打印 secret）。
> 不要在验收中把 `tenantId` 写死为 `tenant_default`。

---

## B. 真实 OIDC IdP（可选，需外部环境）

| # | 步骤 | 预期 | 证据 | 状态 |
|---|---|---|---|---|
| B1 | 配置 `app.oidc.*`（issuer/client-id/secret/success-redirect） | 应用启动时注册 OIDC ClientRegistration | 启动日志无 `app.oidc` 报错 | 未验证 |
| B2 | 新用户首次登录 | `OidcUserService.mapOrCreate` JIT 建号（`password_set=false`） | `users.username` + `password_set` | 未验证 |
| B3 | 登录成功跳转 | 重定向 `success-redirect?code=<一次性随机码>`，URL 不含本地 JWT | 浏览器 URL（code 不记录原值） | 未验证 |
| B4 | 已匹配但 `enabled=false`（或 `RESIGNED`）的本地用户登录 | 403，不签发本地 token；不泄露账号状态细节 | HTTP 状态 + 响应体 | 未验证 |
| B5 | 新 sub 与已有本地用户同 email/username | 不接管旧账号；JIT 按 OIDC sub 建号；无本地密码 | 两账号 ID、external_source/external_id | 未验证 |
| B6 | IdP groups 含 ADMIN，但本地无该角色 | JWT 只含本地角色 | 本地角色表 + JWT claims | 未验证 |
| B7 | `/oidc/callback?code=...` | 地址栏 code 先清除；`POST /api/auth/oidc/exchange` 原子消费 60 秒一次性 code 后才保存 JWT；过期/重放/篡改均 401 并清理会话 | 浏览器 history + 网络请求（脱敏） | 未验证 |
| B8 | 已匹配用户禁用/RESIGNED | 403，不签发有效会话 | HTTP 状态 + 本地用户状态 | 未验证 |

> **多租户范围**：OIDC 仅验收单租户 `tenant_default`（N2 范围）。原「IdP 返回未授权租户 → 403」
> 属**未覆盖**（租户 claim / allowlist / 显式选择契约未定义），不得因 B4 通过而推断多租户可用。
> B4 的通过口径是「已匹配但 `enabled=false` 的本地用户 → 403、不签发本地 token」。

自动化覆盖：`OidcLoginSuccessHandlerTest.java`、`OidcLoginCodeServiceTest.java`、
`OidcLoginCodeRepositoryTest.java`、`OidcAuthControllerTest.java`、`OidcCallbackPage.test.tsx`
（成功/403/401、随机 code 哈希与 TTL、过期/重放/篡改、原子消费、禁用用户、StrictMode 单次交换、失败清理）。
真实 IdP 结果不阻断发布。

---

## C. 真实飞书租户（可选，需外部环境）

| # | 步骤 | 预期 | 证据 | 状态 |
|---|---|---|---|---|
| C1 | 开通权限 `contact:department:readonly`、`contact:user.base:readonly` | 应用可读取通讯录 | 飞书开放平台权限截图 | 未验证 |
| C2 | 配置租户级 `feishu.app-id` / `app-secret`，设置 `feishu.sync.enabled=true` | `GET /api/feishu/sync-status` 显示 configured=true | 响应 JSON（无 secret） | 未验证 |
| C3 | `POST /api/feishu/sync-org` | 部门树写入 `org_unit(source=FEISHU, external_id=open_department_id)` | `departmentsSynced` + `org_unit` 抽样 | 未验证 |
| C4 | 检查用户 | `username=fs_<open_id>`，部门/岗位/上级/邮箱/手机写入 | `users` 抽样（手机号仅管理端可见） | 未验证 |
| C5 | 在飞书移出组织后重同步（确保 `errors=[]` 且 `departmentsSynced==departmentsTotal`） | 对应本地用户 `status=RESIGNED`、`enabled=false`、`disable_reason=RESIGNED`、会话失效 | `users.status` + `users.enabled` + `disable_reason` | 未验证 |
| C5b | 对单部门注入 429/超时后重同步 | **不执行离职**：`usersResigned=0`、`resignSkipped=true`、原用户状态不变 | 响应 JSON + `users` 前后值 | 未验证 |
| C5c | `POST /api/feishu/sync-org?dryRun=true` | 返回 `pendingResignations` 名单，**零离职写入、零会话吊销**；部门/用户资料仍可能 upsert，需单独记录前后差异 | 响应 JSON + `users` 前后值 | 未验证 |
| C6 | 关闭 `feishu.sync.enabled` 后调用同步 | `code=500` 明确提示未启用，**零外部 HTTP 请求** | 应用日志无 outbound 飞书请求 | 未验证 |

自动化覆盖：`FeishuOrgSyncServiceMultiDeptTest`（两层部门、110/10 分页、改名、离职、
429 失败计数、部分失败不离职、比例熔断、dryRun、force 放行、管理员手工禁用保护、跨租户同名隔离、开关关闭零请求）。
真实租户结果另附记录，不阻断发布。

---

## C2. 钉钉组织架构同步（可选，需外部环境）

| # | 步骤 | 预期 | 证据 | 状态 |
|---|---|---|---|---|
| D1 | 租户未配置 `dingtalk.app-key/secret` 时调用 `POST /api/dingtalk/sync-org` | `code=500` 且明确「拒绝回退全局 token」；**零外部 HTTP** | 响应 JSON + 应用日志无 outbound 钉钉请求 | 未验证 |
| D2 | 仅配置全局凭证、未开启 `dingtalk.single-tenant-legacy` | 同上拒绝；多租户环境必须保持该开关为 false | 响应 JSON | 未验证 |
| D3 | 配置租户级凭证后同步 | 部门/用户按租户写入；`departmentsSynced==departmentsTotal` | 响应 JSON + `org_unit`/`users` 抽样 | 未验证 |
| D4 | 单部门注入 429/超时后重同步 | **不执行离职**：`usersResigned=0`、`resignSkipped=true` | 响应 JSON + `users` 前后值 | 未验证 |
| D5 | `POST /api/dingtalk/sync-org?dryRun=true` | 返回 `pendingResignations`，零离职写入、零会话吊销；资料 upsert 仍可能发生 | 响应 JSON + `users` 前后值 | 未验证 |
| D6 | 计划内大裁员 | 默认熔断 409；ADMIN 加 `force=true` 才执行，且审计含 `force=true` | 响应 JSON + `audit_log` | 未验证 |
| D7 | 管理员手工禁用（`disable_reason=MANUAL`）后重同步 | 保持禁用，不被自动启用，也不判离职 | `users` 前后值 | 未验证 |

自动化覆盖：`DingTalkOrgSyncServiceMultiDeptTest`（两层部门/分页/改名/离职、单部门失败不离职、
dryRun、force、未配置租户零外部请求）、`OrgSyncSafetyTest`（完整性/阈值下限/熔断/force）、
`DingTalkControllerAuthTest`（JWT 租户优先于 body）、`UserAdminServiceTest`（G5 资料编辑不改写禁用原因）。
真实租户结果另附记录，不阻断发布。

---

## D. 执行记录

| 日期 | 执行人 | 范围 | 结果 | 备注 |
|---|---|---|---|---|
| — | — | — | 未验证 | 等待具备凭证的环境 |

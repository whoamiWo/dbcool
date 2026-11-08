# 外部集成人工验收清单（Round 3）

> 本文覆盖**无法在离线 CI 中闭环**的外部依赖项：钉钉审批去程、真实 OIDC IdP、真实飞书租户。
> 代码完成标准见 T4/T5 单测；本文只记录需要凭证/公网环境的验收步骤与证据格式。
> 状态列由执行人填写「未验证 / 通过 / 失败 + 日期」。

---

## A. 钉钉审批去程（人工，需企业应用凭证）

前提：

- 租户已配置 `dingtalk.app-key` / `dingtalk.app-secret`（租户级凭证优先，**不得**使用全局凭证跨租户）。
- 目标审批模板已配置 `external_process_code`。
- 开关 `approval.dingtalk.push.enabled=true`（默认关闭）。

| # | 步骤 | 预期 | 证据 | 状态 |
|---|---|---|---|---|
| A1 | 本地提交一条审批 | 本地 `approval_request` 创建成功 | `approval_request.id` + 提交时间 | 未验证 |
| A2 | 检查钉钉 OA | 出现对应审批实例 | `approval_request.external_instance_id` 非空 + 钉钉实例截图/ID | 未验证 |
| A3 | 钉钉侧「通过」 | 回调推进本地流程，任务状态变化一次 | 回调时间戳 + `workflow_task.status` 前后值 | 未验证 |
| A4 | **同一回调重放 2 次** | 只推进一次，不重复流转 | 两次请求时间戳 + 任务状态仍为已完成 | 未验证 |
| A5 | 钉钉侧「驳回」 | 本地流程进入驳回态 | 任务状态 + 审批意见 | 未验证 |
| A6 | 关闭 `approval.dingtalk.push.enabled` 后再次提交 | 不再外发，本地审批仍可完成 | 应用日志中**无** outbound 钉钉请求；本地状态正常 | 未验证 |

> 若 A2 失败，先确认租户凭证是否被 `TenantCredentialService` 解析到（日志 `[DingTalk]` 不打印 secret）。
> 不要在验收中把 `tenantId` 写死为 `tenant_default`。

---

## B. 真实 OIDC IdP（可选，需外部环境）

| # | 步骤 | 预期 | 证据 | 状态 |
|---|---|---|---|---|
| B1 | 配置 `app.oidc.*`（issuer/client-id/secret/success-redirect） | 应用启动时注册 OIDC ClientRegistration | 启动日志无 `app.oidc` 报错 | 未验证 |
| B2 | 新用户首次登录 | `OidcUserService.mapOrCreate` JIT 建号（`password_set=false`） | `users.username` + `password_set` | 未验证 |
| B3 | 登录成功跳转 | 重定向 `success-redirect?token=<本地 JWT>` | 浏览器 URL（token 已脱敏） | 未验证 |
| B4 | IdP 返回未授权租户 | 403，不签发 token | HTTP 状态 + 响应体 | 未验证 |

自动化覆盖：`backend-java/src/test/java/com/nocobase/auth/oidc/OidcLoginSuccessHandlerTest.java`
（成功 / 403 / 非 OidcUser 401 / redirect 已带 query 四种切片）。
真实 IdP 结果不阻断发布。

---

## C. 真实飞书租户（可选，需外部环境）

| # | 步骤 | 预期 | 证据 | 状态 |
|---|---|---|---|---|
| C1 | 开通权限 `contact:department:readonly`、`contact:user.base:readonly` | 应用可读取通讯录 | 飞书开放平台权限截图 | 未验证 |
| C2 | 配置租户级 `feishu.app-id` / `app-secret`，设置 `feishu.sync.enabled=true` | `GET /api/feishu/sync-status` 显示 configured=true | 响应 JSON（无 secret） | 未验证 |
| C3 | `POST /api/feishu/sync-org` | 部门树写入 `org_unit(source=FEISHU, external_id=open_department_id)` | `departmentsSynced` + `org_unit` 抽样 | 未验证 |
| C4 | 检查用户 | `username=fs_<open_id>`，部门/岗位/上级/邮箱/手机写入 | `users` 抽样（手机号仅管理端可见） | 未验证 |
| C5 | 在飞书移出组织后重同步 | 对应本地用户 `status=RESIGNED`、`enabled=false`、会话失效 | `users.status` + `users.enabled` | 未验证 |
| C6 | 关闭 `feishu.sync.enabled` 后调用同步 | `code=500` 明确提示未启用，**零外部 HTTP 请求** | 应用日志无 outbound 飞书请求 | 未验证 |

自动化覆盖：`FeishuOrgSyncServiceMultiDeptTest`（两层部门、110/10 分页、改名、离职、
429 失败计数、开关关闭零请求）。真实租户结果另附记录，不阻断发布。

---

## D. 执行记录

| 日期 | 执行人 | 范围 | 结果 | 备注 |
|---|---|---|---|---|
| — | — | — | 未验证 | 等待具备凭证的环境 |

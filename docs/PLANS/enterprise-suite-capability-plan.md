# DBCool 企业自用闭环 · 能力补齐计划（基线 v2.1.4）

> **范围 = E1 组织架构 + E2 审批单据 + E3 通知中心 + E4 统一搜索**（含 7 个必须先修的真实缺陷）。
> 其余 5 项（日程/移动工作台/导入迁移/后台补齐/IM 互通）明确不做，见 §8。
> **解除冻结**：`docs/PRODUCTIZATION_ROADMAP.md` §3「不新增业务功能」仅对本文件 E1–E4 开例外；
> 开工前必须先改该文件 §3（见 T0），否则执行方会以"违反维护模式"为由拒绝开工。
> 判定口径：**①存在文件 ≠ ②已接线 ≠ ③端到端可用**。所有结论必须带代码位置或实测输出。

---

## 1. 一句话结论

七家产品的**广度**在 v2.1.4 已完成。缺的是**企业日常运转的闭环**：
**谁是谁（组织）→ 要批什么（单据）→ 通知到人（通知）→ 找得到（搜索）**。
四件事中，"组织"不存在、"单据"无法发起、"通知"未统一、"搜索"对业务记录**既搜不到也判不了权限**。
且这四块下面压着 7 个**静默失效**的缺陷——界面看起来是好的，这才是最危险的部分。

---

## 2. 七个必须先修的缺陷（实证）

| # | 缺陷 | 证据 | 后果 |
|---|---|---|---|
| **A-1** | **DEPT 数据范围静默失效** | `acl/AclEnforcer.java:374-375` 生成的 SQL 引用**不存在的表 `user_u`** 与**不存在的列 `department_id`**（全部 migration 无此列；`auth/UserEntity.java` 无此字段）；内存侧 `AclEnforcer.java:191-193` 仅判断 `record.get("department_id") != null`，动态记录表永不含该列 | 配了"本部门可见"→ **一条都看不到**；`AclDataScopeTest.deptScope_createsDepartmentPredicate` 只断言字符串含 `department_id`，**测不出失效** |
| **A-2** | **外部建号缺 password_hash → 建号必失败** | `users.password_hash NOT NULL`（`V1__init.sql:14`）；`DingTalkOrgSyncService.java:265-273` 建用户**未设密码**；`OidcUserService.java:109` 显式 `setPasswordHash(null)` | 钉钉组织同步"同步 N 人"实为 0 人；OIDC 首次登录建号 500。均被 `catch{log.warn; return count}` 吞掉 → **假成功** |
| **A-3** | **统一搜索对记录不做 ACL** | `UnifiedSearchService.java:231-239`（`searchRecord` 直接 `toResultMaps`，无权限校验）；`:137-150`（全局路径只过滤 IM）；Wiki 仅判 `PUBLISHED`，不判 ACL | 搜索会把**无权访问的记录**返回 → 数据泄露；一旦接上前端入口即放大暴露面 |
| **A-4** | **组织同步只覆盖根部门、不更新、租户硬编码** | `DingTalkOrgSyncService.java:205-209` 用户列表只拉 `dept_id=1`；`:259-262` 已存在用户**原样返回**（改名/调岗不更新）；`:117-125` `scheduledSync` 硬编码 `"tenant_default"`；无离职处理 | 多部门企业只同步根部门的人；改名后显示旧名；多租户只同步默认租户 |
| **A-5** | **禁用/离职账号仍可访问** | `auth/JwtAuthFilter.java` 全文件无 `enabled`/`isEnabled` 校验；access TTL 15 分钟（`JwtService:39`） | 离职人员凭旧 token 继续操作；若 refresh 亦不校验则等于永久有效。E1 一开始同步离职即变必然 |
| **A-6** | **搜索索引写入器对业务记录写空** | `search/SearchIndexSyncListener.java:155-166`：`title`/`content` 只取 `data.get("title")`/`get("content")`（业务集合通常没有这两个字段名）→ 索引列全空，`content_tsv` 为空；`metadata` 塞**整条记录**且**不含 collection 名** | 业务记录**搜不出来**；即使搜出来也无法判定所属 collection → A-3 的权限校验连数据都不够用。`RecordChangeEvent` 本身带 `collectionName`（`:31`），写入时被丢掉 |
| **A-7** | **审批节点会造"幽灵审批人"** | `workflow/handler/ApprovalNodeHandler.java:103-117`：无 `assignee` 配置时用 `nameUUIDFromBytes(tenantId.getBytes())` 合成一个 UUID 当审批人（`:116`） | 该任务挂在**不存在的用户**上 → 没人能审、流程永久悬挂，UI 上却显示"已提交、待审批"。`MANAGER`/角色类审批上线后此路径会被大量触发 |

> A-2/A-4/A-3/A-6 的共同特征：**都被单测 mock 掩盖**（`OidcUserServiceTest:46`、`DingTalkOrgSyncServiceTest:137` 均 mock `repository.save`；搜索无"索引后能搜到"的语义测试）。
> **因此本计划硬性要求：涉及 DB 约束、索引写入、权限过滤的用例一律走真实 DB（Testcontainers），禁止 mock `save` 断言。**

---

## 3. 已定设计决策（不再讨论）

| # | 决策 | 依据 |
|---|---|---|
| D1 | **组织与人员 SSOT = 外部平台**（钉钉/企微/飞书），本地只读镜像 | 用户确认。补充：`org_unit.source='LOCAL'` 子树允许本地编辑（未接外部平台的企业可用），外部来源子树 UI 置灰只读 |
| D2 | **通知中心建在现有 `messages` 表上**（加列），`/api/messages` 保持兼容 | 用户确认。避免双表双收件箱；工作流通知已写入该表 |
| D3 | **审批做到串行多级 + 抄送 + 上级审批**，不做会签/或签/加签/转办 | 用户确认 |
| D4 | 钉钉审批推送为**开关默认关**；本地 `approval_request` 为 SSOT | 避免双写不一致 |
| D5 | DEPT 数据范围默认 **含下级子树（SUBTREE）**，策略可切 `SELF_DEPT` | 企业语义多为"本部门及下级"；做成策略字段避免二次改造 |
| D6 | `department_id` 为空的用户 **等同 SELF**（只看自己） | 避免"看不到任何数据"与"看到全部"两个极端 |
| D7 | 通讯录：姓名/部门/岗位/上级/邮箱**全员可见**；手机号 **仅 ADMIN/HR** | 用字段级权限实现，不新造机制 |
| D8 | 审批"打印"走**前端打印样式**，不引入新的 PDF 链路 | 服务端 PDF 走 Python 渲染，牵连过大 |
| **D9** | **审批链由 WorkflowEngine 驱动（单引擎）**：`approval_template.workflow_id` 必填；提交 = 触发 workflow 实例 | 用户确认。避免第二套审批链解释器（本项目已有"两套 ExpressionEvaluator 死实现"前车之鉴）。配套"审批模板向导"一键生成三段式流程，降低管理员成本 |
| **D10** | **搜索索引按字段语义推断**：title = 集合中名为 `name/title` 的 text 字段，否则第一个 text 字段；content = 所有 text/email/url/select/phone 字段值拼接（截断 4KB）；`metadata` **只存白名单**（`collection`、`recordId`、`displayValue`），**不再塞整条记录** | 修 A-6 的最短路径；整条塞 metadata 既是数据冗余也是泄露面 |
| **D11** | **@提及不合并两套已读**：IM 内红点仍读 `im_message_mentions`；通知中心只写一条 `messages(type=MENTION, deeplink=/im/{channelId}?m={messageId})`，两者独立计读 | 强行合并需改 IM 既有语义，收益低；不合并的代价仅是"两处红点"，可接受 |

---

## 4. 任务清单（按依赖排序）

### T0 · 文档收口（先做，否则范围非法）

- 改 `docs/PRODUCTIZATION_ROADMAP.md` §3：新增「E1–E4 白名单（企业自用闭环）」并指向本文件，其余禁止项保留。
- 改 `docs/PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md` 顶部：标注 Wave0.5 A-1..A-8、Wave4 #1..#16 已完成，
  并把本文 §2 的 A-1..A-7 追加进差距清单，避免后续按旧清单返工。
- 验收：两份文档顶部有指向；`python3 scripts/version-consistency-verify.py` exit 0。

---

### T1 · 止血（6 个基础缺陷 A-1..A-6，独立批次，不与新功能混提交；A-7 随 T3 修）

> A-7（幽灵审批人）与审批人解析逻辑同源，放在 T3.2 一并改，避免同一处代码改两遍。

**T1.1 迁移 V77（数据前置）**
- `ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;`
- `users` 新增：`password_set BOOLEAN NOT NULL DEFAULT TRUE`、`department_id UUID`、`position VARCHAR(128)`、
  `manager_id UUID`、`phone VARCHAR(32)`、`avatar_url VARCHAR(512)`、`employee_no VARCHAR(64)`、
  `status VARCHAR(16) DEFAULT 'ACTIVE'`、`joined_at TIMESTAMPTZ`
- `org_unit(id, tenant_id, parent_id, name, code, path, sort, source, external_id, deleted, created_at)`
  + 唯一键 `(tenant_id, source, external_id)`；索引 `(tenant_id, parent_id)`、`(tenant_id, path)`
- 索引：`idx_users_tenant_dept (tenant_id, department_id)`、`idx_users_manager (manager_id)`
- 同步改 `UserEntity`（`passwordHash` 改 `nullable = true` + 新字段 getter/setter）

**T1.2 统一建号路径（A-2）**
- 抽 `auth/UserProvisioningService.provision(...)`：外部托管账号统一写 `passwordHash=null` + `passwordSet=false`
- 改造 7 处调用点：`DingTalkOrgSyncService:265`、`OidcUserService:105`、`LdapSyncService`、`DingTalkAppService:208`、
  `WeComAppService`、`WeChatAppService`、`InboundMessageService:262`（后 6 处现为随机密码 → 统一为无本地密码，只能走 SSO）
- `AuthController` 密码登录：`passwordSet=false` 时返回 401 `"该账号由企业平台托管，请使用 SSO 登录"`
- **验收（真实 DB）**：三条建号路径各建一个用户，断言落库成功且 `password_set=false`；OIDC 首次登录端到端 200

**T1.3 修 DEPT 数据范围（A-1）**
- DEPT 谓词改为 `created_by IN (SELECT id FROM users WHERE tenant_id = :t AND department_id IN (:subtreeIds))`，
  子树由 `org_unit.path LIKE :rootPath || '%'` 求；删除 `user_u` 假表引用
- `AclEnforcer.filterRecordsByDataScope` 由**内存过滤改为 SQL 下推**（`CollectionController:432` 处拼 `WHERE`）；
  内存实现保留但仅在无法下推时使用，并 `log.warn` 标记，防止再成默认路径
- `department_id` 为空按 D6（等同 SELF）；DEPT 语义按 D5（默认 SUBTREE）
- **验收（真实 DB）**：①同部门/子部门可见 ②跨部门不可见 ③无部门账号只看自己；且 SQL 能在真实 PG 执行
- 删除 `AclDataScopeTest` 中"仅断言字符串含 `department_id`"的弱断言，替换为行为断言

**T1.4 修组织同步（A-4）**
- `syncAllUsers` 改为**按部门树逐部门拉取**（复用 `collectAllDepartments` 的 BFS 结果），写入 `users.department_id`
- 已存在用户：更新 `displayName/email/phone/position/manager`（不再原样返回）
- 离职：本次未出现的钉钉来源用户 → `status='RESIGNED'` + `enabled=false`（不删除）
- `scheduledSync` 去掉硬编码 `"tenant_default"`，改为遍历已安装钉钉集成的租户
- `catch` 改为记录 `usersFailed` 与错误列表并向上返回；`GET /integration/dingtalk/sync-status` 返回
  `{departmentsSynced, usersSynced, usersFailed, errors[]}`，`usersFailed>0` 时 `code!=0`
- **验收**：mock RestTemplate 两层部门 + 120 人分页 → 建号 120、部门归属正确、改名后再次同步生效、移出部门后 `enabled=false`；
  注入失败时 `sync-status` 返回 `usersFailed>0`

**T1.5 禁用/离职账号的会话失效（A-5）**
- `JwtAuthFilter` 增加用户状态校验（Redis `user:status:{id}` 缓存 60s，未命中查库；查不到或 `enabled=false` → 401，**fail-closed**）
- 刷新令牌路径同样校验；禁用时删除该用户全部 refresh 记录（`RefreshTokenService` 是否已校验 `enabled` 需开工时确认）
- **验收**：禁用后用已签发 access token 请求 → 401（≤60s 生效）；用 refresh token 刷新 → 401

---

### T2 · E1 组织架构与通讯录

**T2.1 后端**
- `org/OrgUnitEntity` + Repository + `OrgUnitService`（建树、`path` 前缀查子树、移动节点重算子树 path）
- `org/OrgUnitController`：`GET /api/org/tree`、`POST|PUT|DELETE /api/org/units`（**仅 `source='LOCAL'` 可写**）、
  `GET /api/org/members?unitId=&keyword=`、`PUT /api/org/members/{userId}/unit`
- 同步侧：`DingTalkOrgSyncService`/`WeComAppService`/`FeishuAppService` 部门树写 `org_unit`（`source`+`external_id`），
  按 `external_id` 幂等回写 `users.department_id`
- `UserAdminController` 透出新字段；手机号按 D7 做字段级过滤

**T2.2 前端**
- `pages/OrgStructure.tsx`（`admin/org`）：部门树 + 成员列表 + 拖人调部门（LOCAL 可拖，外部子树置灰提示"由钉钉维护"）
- `pages/Directory.tsx`（`directory`）：按部门浏览 + 搜索 + 员工卡片
- `AppLayout` 菜单加「通讯录」；`MessageComposer` 的 @候选改取 `/api/org/members`

**T2.3 验收（DoD）**
1. 建三级部门 → 拖人进 B → 角色设 DEPT(SUBTREE) → **B 成员可见本部门及子部门记录，A 部门不可见**（HTTP + 直接 SQL 双证）
2. 钉钉同步后部门层级与人员归属正确；`source='DINGTALK'` 子树 UI 不可编辑
3. 手机号对非 ADMIN/HR **接口层**不返回（非前端隐藏）
4. 新页面 375px 无横向滚动（Playwright `mobile-chromium`）

---

### T3 · E2 审批单据（审批链 = 工作流，D9）

**T3.1 迁移 V78**
- `approval_template(id, tenant_id, name, icon, category, form_id, workflow_id **NOT NULL**, approver_type, enabled, created_at)`
- `approval_request(id, tenant_id, template_id, applicant_id, form_data JSONB, status, instance_id, current_task_id, external_instance_id, submitted_at, finished_at)`
- `approval_cc(request_id, user_id)`
- 状态映射（**单一事实**：`workflow_instance.status` → `approval_request.status`）
  `PENDING→PENDING`、`COMPLETED→APPROVED`、`FAILED(驳回)→REJECTED`、主动终止`→CANCELED`

**T3.2 后端**
- `approval/ApprovalController`：`POST /api/approvals`（templateId + formData）、`GET /api/approvals/mine|todo|cc`、
  `POST /api/approvals/{id}/cancel`、`GET /api/approvals/{id}`（含审批轨迹）
- 触发：新增 `FORM_SUBMIT` 触发器（复用 Wave4 webhook 触发骨架），`trigger_data` 带 `{applicantId, formData, requestId}`
- 撤回：`cancel` → 终止实例（`WorkflowInstance.status=TERMINATED`）+ 关闭未完成 `workflow_task`；已审批的不回滚
- **修幽灵审批人（A-7）**：`ApprovalNodeHandler.resolveAssignee()` 在无 `assignee` 时用
  `nameUUIDFromBytes(tenantId)` 造一个**不存在的用户**（`:112-116`）→ 任务永远无人处理却显示"待审批"。
  改为：按 `approverType` 解析 —— `USER`（指定人）/ `MANAGER`（发起人 `users.manager_id`）/ `ROLE`（角色成员展开）；
  **无法解析时抛业务异常，节点 FAILED、实例 FAILED，不创建任务**；`manager_id` 缺失时在**提交前**就拦住并给出明确提示
- 抄送：进入 `APPROVED|REJECTED` 时写 `approval_cc` 并触发通知（依赖 T4）
- 钉钉联动：`DingTalkApprovalService` 补去程创建实例（`approval.dingtalk.push.enabled=false` 默认关）；
  回调已有 `approveByExternalBinding`，补幂等（同一 `external_instance_id` 只推进一次）
- **模板向导**：`POST /api/approvals/templates/from-preset` 生成三段式流程（发起 → 审批（可多级）→ 结束）+
  校验"任一审批节点必须有可解析的审批人规则"

**T3.3 前端**
- `pages/ApprovalCenter.tsx`：`我发起的 / 待我审批 / 抄送我的` 三 tab + 状态筛选
- 发起页：选模板 → 复用 `FormDesigner` 渲染 → 提交；`manager_id` 缺失时表单侧即提示
- `MyTasks` 并入审批中心，旧路由重定向（避免断链）
- 打印：`window.print()` + 打印样式（D8）
- 移动端：三 tab 与发起页 375px 可用

**T3.4 验收（DoD）**
1. 六条路径真实跑通：发起→一级通过→二级通过(APPROVED)、驳回(REJECTED)、撤回(CANCELED)、抄送可见、打印
2. `approverType=MANAGER` 时审批人取自 `manager_id`；缺失时**提交被拦**，不产生悬挂任务
3. 审批节点未配审批人 → 实例 FAILED 且**不产生幽灵任务**（负向用例）
4. 移动端 375px 三条路径可用

---

### T4 · E3 通知中心

**T4.1 迁移 V79**
- `messages` 加列：`deeplink VARCHAR(512)`、`read_at TIMESTAMPTZ`、`digest_key VARCHAR(128)`、
  `priority VARCHAR(16) DEFAULT 'NORMAL'`、`channel_sent JSONB DEFAULT '{}'`
- `type` 收敛：`APPROVAL_TODO | APPROVAL_RESULT | MENTION | COMMENT | DUE_SOON | WORKFLOW_FAILED | QUOTA | SYSTEM`
  （迁移里给出旧值 `workflow/system/mention` → 新值映射 UPDATE）
- 偏好复用现有 `user_preferences`（`{"notify": {type: [channels], "quietHours": ["22:00","08:00"]}}`），**不新建表**
- 索引：`(tenant_id, recipient, read, created_at DESC)`

**T4.2 后端**
- `notification/NotifyDispatcher.notify(type, recipientIds, payload)`：写 `messages` → 按偏好与免打扰分渠道投递
  （复用 `NotificationChannelController` 的 EMAIL/WEBHOOK/DINGTALK/WECHAT_WORK；投递失败只记 `channel_sent`，不阻断主流程）
- 模板注册 7 类；`digest_key` 聚合：同 key 10 分钟内合并为一条并累加计数
- 接入点 6 处：`comment/RecordCommentController`、IM @提及落库处（按 D11，IM 红点仍读 mention 表）、
  `workflow/WorkflowTaskService`（待办/结果）、工作流失败分支、`quota/TenantQuotaFilter`（超限）、`approval/`（T3）
- `MessageController` 增：`GET /api/messages/unread-count`、`POST /api/messages/read-all`、`GET /api/messages?type=`

**T4.3 前端**
- `pages/MessagesInbox.tsx` 升级为 `NotificationCenter`（分类 tab + 未读角标 + deeplink 跳转 + 偏好设置抽屉）
- `AppLayout` 顶栏铃铛 + 未读数（轮询 30s，不为通知新建 SSE 链路）

**T4.4 验收（DoD）**
7 类事件各触发一次：站内必达、按偏好渠道到达（mock 通道断言调用）、免打扰时段**不投递但站内留档**、
`digest_key` 聚合生效（10 分钟内 3 条 → 1 条计 3）、点击跳转正确；IM @提及两处红点互不影响（D11）

---

### T5 · E4 统一搜索（先修 A-3/A-6，再开前端入口）

**T5.1 重写索引写入（A-6，D10）**
- `SearchIndexSyncListener.syncRecord`：用 `event.getCollectionName()` 保留集合名；
  title 按 D10 推断，content 按 D10 拼接并截断 4KB；`metadata` 只写白名单 `{collection, recordId, displayValue}`
- 索引实体新增/变更时**必须重新计算**（当前 `indexEntity` 先删后插，逻辑可复用）
- **存量重建**：新增 `scripts/reindex-search.py`（或 Java CommandLineRunner `--reindex-search`），
  按租户全量重建 wiki/im/project/record 索引；上线前必须跑一次，否则旧数据搜不到
- 验收：建一个**没有 title/content 字段**的集合（如 `orders`，字段 `订单号/客户/金额`），录一条 → 搜索"客户名"能命中

**T5.2 补 ACL（A-3）**
- `searchRecord` 与全局路径的 record 分支：按 `metadata.collection` 校验 collection 读权限（复用 `AclEnforcer`），
  再逐条做行级过滤（`RowAclService.filterReadable`）；limit 先放大（×5）再截断
- Wiki：由"仅 PUBLISHED"升级为 "PUBLISHED + Wiki ACL"
- 验收（安全）：A 用户搜 B 用户私有 collection 的关键词 → 0 结果；跨租户 → 0 结果；两条负向用例进 `scripts/tenant-isolation-e2e-verify.py`

**T5.3 补索引类型 + 前端入口**
- 索引补 `approval`（T3 落库后，在提交/状态变更处 `indexEntity`）
- `components/GlobalSearch.tsx`：`AppLayout` 顶部搜索框，`Cmd/Ctrl+K`、防抖 300ms、分组（记录/Wiki/IM/审批）+ 高亮 + 键盘导航
- `pages/SearchResults.tsx`：`/search?q=` 全量结果页 + 类型筛选 + 分页
- 验收：五类实体各命中并可跳转；`Cmd+K` 移动端不出现（移动端用独立搜索页入口）

---

## 5. 全局门禁（每阶段结束必跑）

| 门禁 | 命令 | 本轮新增要求 |
|---|---|---|
| 全量 | `python3 scripts/monthly-gate.py` | 13 项全绿 |
| 后端 | `cd backend-java && mvn -o verify` | 覆盖率红线 0.79 不下调 |
| 前端 | `cd frontend && npm run test:run` + `npx tsc --noEmit` | 0 failed / 0 errors |
| 契约 | `python3 scripts/api-contract-verify.py` | 新端点全部入基线，0 漂移 |
| 租户隔离 | `python3 scripts/tenant-isolation-e2e-verify.py` | **每张新表 + 搜索越权负向用例**必须进 |
| DB 约束 | 新增 Testcontainers 用例 | **禁止 mock `save`** 断言 NOT NULL/唯一键 |
| 索引语义 | 新增用例 | "写入索引 → 能搜到 → 无权者搜不到"三连断言 |
| 端到端 | `scripts/smoke.py` 增一条 | 通讯录 → 发起审批 → 上级审批通过 → 收到通知 → 搜到单据 |

**最终完成判定**：一个真实员工账号**不碰后台配置页**即可走完
「看通讯录 → 发起请假单 → 上级审批通过 → 收到通知 → 搜到该单据」全链路，无 404、无 500、移动端可用。

---

## 6. 风险与对冲

| # | 风险 | 对冲 |
|---|---|---|
| R1 | 改 `users` 表 + ACL 下推触碰租户隔离红线 | 新列可空/带默认；先补负向用例再改实现；下推 SQL 必带 `tenant_id` |
| R2 | ACL 内存过滤→SQL 下推改变分页口径（先 limit 后过滤 → 先过滤后 limit） | 同步调整 `TableView` 分页断言；列为 T1.3 验收项 |
| R3 | 修幽灵审批人会改变既有工作流行为 | 现行为本就产生无效任务（指向不存在用户），修复属纠错；受影响既有用例（`WorkflowEngineTest` 审批相关）随批调整，**禁止改弱断言** |
| R4 | 索引重建期间搜索结果不一致 | 重建按租户分批、幂等（先删后插）；提供进度日志；重建完成前不宣布 E4 完成 |
| R5 | 通知风暴 | `digest_key` 合并 + 免打扰 + 每类每小时上限（超限只留站内） |
| R6 | 审批双写（本地+钉钉）不一致 | 本地 SSOT；回调按 `external_instance_id` 幂等；开关默认关 |
| R7 | 搜索放开入口后暴露既有越权 | **T5.1/T5.2 必须先于 T5.3 合入**；负向用例常驻 CI |

---

## 7. 迁移与兼容

- 从 **V77** 起始（当前最大 `V76__schema_completion.sql`）；**历史迁移不可改**。
- V77：`users` 改列 + `org_unit`；V78：审批三表；V79：`messages` 加列。E4 无迁移。
- `password_hash` 改可空属语义变更：`docs/upgrade-runbook.md` 增加"外部托管账号无法用密码登录"说明；
  现有账号 `password_set` 默认 TRUE，行为不变。
- 搜索索引需一次性重建（`--reindex-search`），写入运维手册"升级步骤"。

---

## 8. 明确不做（含重新启用条件）

| 项 | 重新启用条件 |
|---|---|
| E5 个人日程与会议 | 有明确用户诉求或"会议室管理"类需求 |
| E6 钉钉/企微工作台入口 | E2/E3 页面完成后有人反馈"手机上没法用" → 单开一波（本轮仅保证新页面响应式） |
| E7 xlsx / 外部导入迁移 | 有存量数据要从 Airtable/Notion/Excel 迁入 |
| E8 后台页补齐（治理/工单/插件市场/配额） | 需要对外交付运营能力 |
| E9 RocketChat 互通 | 客户环境已在用 RocketChat |
| 会签/或签/加签/转办 | 审批跑起来后出现"多人同时审批"诉求（注：`ApprovalNodeHandler` 现有 `config.assignees` 已是"多人各一条任务、全通过才推进"，可视作弱会签，本轮不做 UI） |
| 真实支付网关、计量计费闭环 | 转商业化 |
| 机器人/应用市场/模板市场/联邦、原生 App、`gantt` 视图、`user`/`button` 字段 | 长期不做 |

---

## 9. 待确认（非阻塞，开工前一句话即可）

1. **HR 角色**：D7 的"HR 可见手机号"是指现有哪个 `RoleName`，还是新增 `HR` 角色？→ 默认只按 `ADMIN` 实现，预留白名单。
2. **审批模板创建权限**：仅 ADMIN，还是 ADMIN + `workflow:write` 角色？→ 默认后者。
3. **搜索 title 推断的兜底**：集合里**一个 text 字段都没有**（全数字/日期）时，是否退化为"用主键前 8 位 + 集合名"？→ 默认退化为 `{集合中文名} #{recordId 前 8 位}`，避免空标题。

---

## 执行状态（2026-11-04，全部为实测输出）

> 口径：①存在文件 ≠ ②已接线 ≠ ③端到端可用。以下逐项标注实际到达的阶段与证据。

| 任务 | 状态 | 实测证据 |
|---|---|---|
| **T0 文档收口** | ✅ 完成 | `PRODUCTIZATION_ROADMAP.md` §3.1 白名单；`PLATFORM_BENCHMARK_ANALYSIS` 顶部 A-1..A-7；`version-consistency-verify.py` PASS |
| **T1.1 迁移 V77** | ✅ 完成 | V77 应用成功；`org_unit` + `users` 新列在真实 PG 校验通过 |
| **T1.2 统一建号（A-2）** | ✅ 完成 | 7 处建号点全部走 `UserProvisioningService`（passwordHash=null / passwordSet=false）；密码登录对托管账号返回 401；单测 + 集成测试通过 |
| **T1.3 DEPT（A-1）** | ✅ 完成（含 SQL 下推） | `DeptScopeResolver` 真实子树；`AclEnforcer.resolveDataScopePlan` 产出可参数化谓词；SELF/DEPT **下推到 SQL**（`CollectionService.listRecords` 7 参重载 → `DynamicTableManager` `AND (...)` + `LIMIT ×5`），CUSTOM/ROW ACL 保留内存过滤并 `log.warn`；`AclDataScopeTest` 10 项（行为断言）+ `CollectionControllerTest` 37 项；真实 PG 脚本 3 项断言 PASS |
| **T1.4 组织同步（A-4）** | ✅ 完成 | 逐部门 BFS + cursor 分页（110+10 人两页）、已存在用户更新、未出现→RESIGNED+enabled=false、租户遍历、`usersFailed>0 → code!=0`；`DingTalkOrgSyncServiceMultiDeptTest` 4 项 |
| **T1.5 会话失效（A-5）** | ✅ 完成 | `JwtAuthFilter` + `UserStatusService`（Redis 60s + fail-closed）；`RefreshTokenService.revokeAll`；禁用/删除/离职主动失效；`JwtAuthFilterTest` 含失效 token 用例 |
| **T2.1 组织后端** | ✅ 完成（钉钉/企微）；⚠️ 飞书仅登录建号，无部门同步接口（原服务即无此能力） | `OrgUnitServiceTest` 10 项；`OrgUnitController` 树/成员/CRUD/调动 |
| **T2.2 通讯录前端** | ✅ 完成 | `/directory`、`/admin/org`；手机号按 ADMIN/HR 后端字段级下发（D7） |
| **T3.1 审批后端** | ✅ 完成 | V78；`ApprovalServiceTest` 8 项（提交前 manager 校验、状态投影、撤回、模板向导） |
| **T3.2 审批前端 + 钉钉** | ⚠️ 部分（前端完成，钉钉未联调） | `/approvals`、`/approvals/:id` 可用；模板创建 UI 已接入 `from-preset`；`/tasks/my` 已重定向到 `/approvals`（旧组件保留供单测）；单据页 `window.print()` + `@media print` 样式；钉钉去程推送已实现且默认关闭，未对真实钉钉租户联调 |
| **T4 通知中心** | ✅ 完成（7/7 接入点） | APPROVAL_TODO / APPROVAL_RESULT / MENTION / COMMENT / WORKFLOW_FAILED / DUE_SOON / **QUOTA**（`TenantQuotaFilter` 429 分支 → 租户 ADMIN，按天 digest）；偏好读写 API `/api/users/me/preferences`（真实 H2 往返测试 3 项）+ 前端偏好抽屉（免打扰/渠道开关）；`NotifyDispatcherTest` 6、`NotificationSchedulerTest` 4、`CommentNotificationListenerTest` 5、`UserPreferenceControllerTest` 3 |
| **T5.1 索引写入（A-6）** | ✅ 完成（含存量重建） | `SearchIndexSyncListenerTest` 5 项（字段推断、元数据白名单、DELETE、元数据缺失兜底）；存量重建 `SearchReindexRunner`（`--reindex-search`，逐租户逐集合，复用同一推断逻辑，`SearchReindexRunnerTest` 3 项） |
| **T5.2 搜索 ACL（A-3）** | ✅ 完成 | 集合 READ + 行级 + 部门三重判权；取数窗口放大 ×5 后再过滤截断；新增 `approval` 类型与可见性判权（申请人/抄送/任务处理人）；`UnifiedSearchServiceTest` 8 项；fail-closed |
| **T5.3 搜索前端** | ✅ 完成 | 全局搜索优先 deeplink；`/search` 结果页；类型筛选（含 approval）；`Ctrl/Cmd+K` 打开（移动端不启用）+ ↑↓ 键盘导航 + Enter 跳转 + 选中高亮 |
| **额外发现并修复** | ✅ | `V50` 丢弃搜索触发器未重建 → `V80__restore_search_vector_trigger.sql`；真实 PG 复现并回填 |

### 门禁实测（本机，2026-11-04）

- 后端：`mvn -o verify` → **2702 tests, 0 failures, 0 errors**；JaCoCo bundle **79.57%**（阈值 79%）、workflow **94.27%**（阈值 94%）、auth **87.44%**（阈值 86%）。
- 前端：`npm run test:run` → **972 passed / 119 files**；`tsc --noEmit`、`eslint src` 均 0 错误。
- 契约：后端 **455** 路径 × 前端 **136** 路径 → **0 漂移**。
- 真实 PG：`scripts/verify-enterprise-suite.py` → **11/11 PASS**（迁移 4 + DEPT 3 + 索引检索 1 + 结构 2）。
- `scripts/monthly-gate.py` → **13/13 全部 PASS**。

### 未完成项（不得视为已完成）

1. **钉钉审批去程联调**：代码路径存在但默认关闭（`approval.dingtalk.push.enabled=false`），未对真实钉钉应用验证。
2. **行级 ACL（ROW/CUSTOM policy）仍为内存过滤**：SELF/DEPT 数据范围已下推 SQL，ROW 表达式策略与 CUSTOM 范围仍是内存过滤（P2-7 状态：部分修复）；行级策略下推需引入表达式 DSL 编译器，本轮未做。
3. **飞书部门树同步**：原 `FeishuAppService` 无部门列表能力，未新增。
4. **OIDC 首次登录端到端 200**：OIDC 建号路径已用真实 DB 覆盖（`UserProvisioningRealDbTest`），但"真实 IdP 首次登录 200"需要外部 IdP，未在本机执行。
5. **移动端 375px Playwright 验收**（T2.3/T3.4）与 **HTTP+SQL 双证**：需要部署新构建后的运行环境，本轮只做了响应式实现与接口层单测，未跑 Playwright。
6. **搜索负向用例进 `tenant-isolation-e2e-verify.py`**：该脚本跑在**线上旧镜像**上（当前容器仍是改动前的 jar），新端点尚未部署，直接加会立即红灯；已在后端单测层覆盖等价断言（ACL 跨租户/跨 collection 0 结果），待镜像重建后再补脚本用例。
7. **IM @提及候选仍取「频道成员」而非 `/api/org/members`**：刻意保留 —— 频道成员是通讯录的子集且有会话权限，改为全组织会造成"能 @ 不可见成员"的越权。

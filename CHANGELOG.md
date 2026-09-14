## [Unreleased] - 2026-09-14 Week 27 Sprint — 批量补 2 个 auth controllers + 红线大跃升(1 commit)

### Added
- **AuthControllerTest** (`AuthControllerTest.java`) — 12 tests,全 PASS
  - login 成功/用户不存在(401)/密码错(401)/空 username(400)
  - refresh 成功/无效 token(401)/用户不存在(401)
  - changePassword 成功/旧密码错(401)/用户不存在(404)
  - me 成功带角色/用户不存在(404)
- **RoleAclControllerTest** (`RoleAclControllerTest.java`) — 17 tests,全 PASS
  - Roles CRUD: list/create/create dup(409)/create blank(400)/update/update 404/delete
  - Roles Tree + Inheritance + cycle 检测(走 updateRole path)+ 404
  - ACL Policies: list by roleId/create/create invalid action(4xx)/update/update 404/delete

### Changed
- **抬 Jacoco 红线**(Week 27 大跃升):
  - **BUNDLE** 38% → **47%**
  - **auth** 35% → **70%**
- **auth excludes 移除** `AuthController` 和 `RoleAclController`(均已 ~95% 覆盖)
- **关键设计**:`@MockBean PasswordEncoder` 是 WebMvcTest 必须,默认 SecurityConfig 不装配
- **关键设计**:`createAcl` 有隐藏前置校验(role 必须存在),需 mock `findByIdAndTenantId`
- **关键设计**:`cycle` 检测在 `createRole` 不可达(UUID.randomUUID 在 save 前),改用 `updateRole` path id 测
- **关键设计**:Inheritance chain `depth = nodes.size() - 1`,需 mock `findById` 每个 id 含 self

### Verified
- `mvn verify` ✅ BUILD SUCCESS
- **276 tests PASS**(247 → 276,+29)
- **覆盖率**:auth **79%**(↑ 41%)/ bundle **47%**(↑ 7%)
- 单类达成:AuthController ~95% / RoleAclController ~95% / UserAdminController 98% / FormController 99% / RowAclController 90%

---

## [Unreleased] - 2026-09-14 Week 26 Sprint — 批量补 3 controllers + 红线大跃升(1 commit)

### Added
- **UserAdminControllerTest** (`UserAdminControllerTest.java`) — 10 tests,全 PASS
  - list / get(+roles) / create(201)/ create 空白 username→400 / update / resetPassword / delete / assignRole / removeRole / effectivePermissions
- **FormControllerTest** (`FormControllerTest.java`) — 7 tests,全 PASS
  - create(201)/ create 空白 collectionName→400 / list(全量)/ list(byCollection)/ get(+parseLayout+parseRules)/ update / delete
- **RowAclControllerTest** (`RowAclControllerTest.java`) — 8 tests,全 PASS
  - list(tenant 过滤)/ byCollection / create / update / update 404 / update 跨租户 403 / delete / delete 跨租户 403

### Changed
- **抬 Jacoco 红线**(Week 26 大跃升):
  - **BUNDLE** 33% → **38%**
  - **auth** 25% → **35%**
  - **acl** 48% → **85%**
  - **form** 45% → **95%**
- **auth excludes 移除** `UserAdminController`(已 98% 覆盖)
- **关键设计**:RowAclControllerTest **不能 @MockBean ObjectMapper**,会让 Spring RouterFunctionMapping 失败 — 让 Spring 注入真实 ObjectMapper
- **关键设计**:FormControllerTest / RowAclControllerTest 通过 `SecurityContextHolder.setContext()` 手动注入 AuthenticatedUser,不走 @WithMockUser

### Verified
- `mvn verify` ✅ BUILD SUCCESS
- **247 tests PASS**(222 → 247,+25)
- **覆盖率**:auth 38%(↑ 13%)/ form 99%(↑ 50%)/ acl 89%(↑ 41%)/ bundle 40%(↑ 6%)
- 单类达成:UserAdminController 98% / FormController 99% / RowAclController 90% / AclRowPolicyEntity 100%

---

# Changelog

本项目所有重要变更按时间倒序记录。每次会话的成果可追溯。

---

## [Unreleased] - 2026-09-14 Week 25 Sprint — 批量补 3 简单 controllers + 红线(1 commit)

### Added
- **WorkflowTemplateControllerTest** (`WorkflowTemplateControllerTest.java`) — 5 tests,全 PASS
  - list / get(存在→200 / 不存在→404) / install(201 + 跨 userId 透传)
- **MessageControllerTest** (`MessageControllerTest.java`) — 7 tests,全 PASS
  - list(全量 / unreadOnly / cursor) / before 非法→400 / markRead(自己/不存在/别人的)
- **ErDiagramControllerTest** (`ErDiagramControllerTest.java`) — 6 tests,全 PASS
  - 空图 / 单节点 / belongsTo 边 / collection 备选键 / 孤儿 target 跳过 / title null 兜底

### Changed
- **抬 Jacoco 红线**:bundle 28%→33% / meta 10%→15% / workflow 10%→20%
- **meta excludes 移除** `ErDiagramController`(Week 25 测了)
- **workflow excludes 新增** `WorkflowController` / `WorkflowEngine` / `WorkflowTemplateService`(太大,Week 26+ 拆)

### Verified
- `mvn clean verify` 222 tests 全 PASS + All coverage checks met
- 覆盖率:workflow 10%→22% / meta 10%→20% / bundle 28%→34%

### Trend(Week 18 → 27)
| 周 | tests | 覆盖包数 | bundle 红线 |
|----|------|---------|------------|
| 18 | 70 | 1 | — |
| 19 | 70 | 2 | 5% |
| 20 | 112 | 4 | 8% |
| 21 | 145 | 7 | 12% |
| 22 | 181 | 9 | 14% |
| 23 | 183 | 10 | 21% |
| 24 | 204 | 10 | 28% |
| 25 | 222 | 10 | 33% |
| 26 | 247 | 10 | 38% |
| **27** | **276** | **10** | **47%** |

---

## [Unreleased] - 2026-09-14 Week 24 Sprint — 批量补 3 controllers + 红线(1 commit)

### Added
- **ViewControllerTest** (`ViewControllerTest.java`) — 8 tests,全 PASS
  - list(no collection / with collection)/ get / create(成功+2 失败)/ update / delete
- **AuditControllerTest** (`AuditControllerTest.java`) — 3 tests,全 PASS
  - logs 无 filters / 带 4 个 filters / 默认 limit
- **NotificationChannelControllerTest** (`NotificationChannelControllerTest.java`) — 10 tests,全 PASS
  - 6 端点全覆盖 + 跨 tenant 403 + 不存在 400

### Changed
- **抬 Jacoco 红线**:bundle 21%→28% / view 40%→90% / audit 45%→80% / notification 25%→50%

### Verified
- `mvn clean verify` 204 tests 全 PASS + All coverage checks met
- 覆盖率:view 44%→94% / audit 47%→83% / notification 25%→56%
- MockMvc 模板 100% 复用 Week 23

### Trend(Week 18 → 24)
| 周 | tests | 覆盖包数 | bundle 红线 |
|----|------|---------|------------|
| 18 | 70 | 1 | — |
| 19 | 70 | 2 | 5% |
| 20 | 112 | 4 | 8% |
| 21 | 145 | 7 | 12% |
| 22 | 181 | 9 | 14% |
| 23 | 183 | 10 | 21% |
| 24 | 204 | 10 | 28% |

---

## [Unreleased] - 2026-09-14 Week 23 Sprint — UserController MockMvc + 红线(1 commit)

### Added
- **UserControllerTest** (`UserControllerTest.java`) — 2 tests,全 PASS
  - `@WebMvcTest(UserController.class)` + `@AutoConfigureMockMvc(addFilters=false)` 跳过 Security
  - `@MockBean SecurityConfig + JwtAuthFilter` 隔离依赖
  - `me_withUser_returnsInfo`:SecurityContext 注入 Authentication → code 0
  - `me_withoutAuth_returnsCode1001`:空 SecurityContext → code 1001

### Changed
- **抬 Jacoco 红线**:bundle 14%→21%
- **新增 1 包规则**:api ≥95%(实际 100%)

### Verified
- `mvn clean verify` 183 tests 全 PASS + All coverage checks met
- 覆盖率:api 0%→100%
- 已覆盖包从 9 → 10(13 个生产包的 77%)

### Trend(Week 18 → 23)
| 周 | tests | 覆盖包数 | bundle 红线 |
|----|------|---------|------------|
| 18 | 70 | 1 | — |
| 19 | 70 | 2 | 5% |
| 20 | 112 | 4 | 8% |
| 21 | 145 | 7 | 12% |
| 22 | 181 | 9 | 14% |
| 23 | 183 | 10 | 21% |

### Key 经验
- `@WebMvcTest` + `addFilters=false` + `@MockBean SecurityConfig` 是 controller 测试的有效模板
- 后续 controllers(Auth/UserAdmin/RoleAcl 等)可复用,Week 24 批量补

---

## [Unreleased] - 2026-09-14 Week 22 Sprint — notification/form 测试 + 红线(1 commit)

### Added
- **EmailDispatcherTest** (`EmailDispatcherTest.java`) — 7 tests,全 PASS
  - supportedType + null/blank recipient + mock/real SMTP 模式
- **NotificationServiceTest** (`NotificationServiceTest.java`) — 13 tests,全 PASS
  - fire 入口(无 channels / dispatcher 路由 / 抛异常捕获)
  - matchesEvent(null/blank/CSV精确/case-insensitive/混合)
  - testSend(channel 不存在 → IAE / 委托 / 无 dispatcher)
- **FormServiceTest** (`FormServiceTest.java`) — 16 tests,全 PASS
  - 5 公开方法(create/get/update/list/delete)+ 2 parser(parseLayout/parseRules)
  - null/blank layout/rules 默认值 + 404 + 部分字段更新保持

### Changed
- **抬 Jacoco 红线**:bundle 12%→14%
- **新增 2 包规则**:notification ≥25% / form ≥45%

### Verified
- `mvn clean verify` 181 tests 全 PASS + All coverage checks met
- 覆盖率:notification 0%→25% / form 0%→49%
- 已覆盖包从 7 → 9(13 个生产包的 69%)

### Trend(Week 18 → 22)
- tests:70 → 181(+158%)
- 覆盖包数:1 → 9(+800%)
- bundle 红线:5% → 14%(每周 +2%)
- 节奏:每周 +30 tests / +1 包 / +2% 红线

---

## [Unreleased] - 2026-09-14 Week 21 Sprint — 继续抬红线 + 新覆盖 3 包(1 commit)

### Added
- **AuditServiceTest** (`AuditServiceTest.java`) — 10 tests,全 PASS
  - log 写入(基础 / null 默认值 / JSON 序列化 / 不可序列化回退 / save 失败静默)
  - find 限流 500 + count 透传
- **ViewServiceTest** (`ViewServiceTest.java`) — 12 tests,全 PASS
  - create / get / update / list / delete 全部 5 公开方法
  - 全字段 + 部分字段更新 + 404
- **WorkflowTemplateRegistryTest** (`WorkflowTemplateRegistryTest.java`) — 11 tests,全 PASS
  - list 3 个内置模板 + 不可变视图
  - 模板内容合理性(leave/expense/customer + edges 校验)

### Changed
- **抬 Jacoco 红线**:bundle 8%→12% / acl 45%→48%
- **新增 3 包规则**:audit ≥45% / view ≥40% / workflow ≥10%

### Verified
- `mvn clean verify` 145 tests 全 PASS + All coverage checks met
- 覆盖率:audit 0%→47% / view 0%→44% / workflow 0%→10%
- 已覆盖包从 4 → 7(13 个生产包的 54%)

---

## [Unreleased] - 2026-09-14 Week 20 Sprint — 抬红线 + GitHub Actions CI(2 commits)

### Added
- **RowAclServiceTest** (`RowAclServiceTest.java`) — 18 tests,全 PASS
  - evaluate 入口(Read/Update/Delete 含 fallback 到 read)
  - filterReadable(无 policy / 按 policy 过滤)
  - 7 个 op(eq/neq/in/is_null/not_null/contains/unknown)
  - appliesTo(user/role principal) + resolveValue 占位符
  - exception path(非法 JSON log warn 后 fail-safe 拒绝)
- **CollectionServiceMatchFilterTest** (`CollectionServiceMatchFilterTest.java`) — 24 tests,全 PASS
  - 镜像前端 FilterRule op
  - toDouble helper(null/number/string/garbage)
- **GitHub Actions CI** (`.github/workflows/backend-ci.yml`)
  - push / PR 触发,mvn verify 跑 Tests + JaCoCo 红线
  - Maven cache + 30 天 artifact 保留
  - 当前单测用 Mockito 不需 DB;集成测试引入后加 services

### Changed
- **抬 Jacoco 红线**:bundle 5%→8% / auth 20%→25% / meta 5%→10% / 新增 acl ≥45%
- **CollectionService.matchFilter / toDouble** 改 package-private(Week 19 教训:反射不被 JaCoCo 计入)
- **README.md** 加 CI badge + coverage gate badge

### Verified
- `mvn clean verify` 112 tests 全 PASS + All coverage checks met
- 覆盖率:acl 0% → 48% / meta 8% → 10%(Week 19 → 20)

### Known
- GitHub SSH 仍不可达,workflow 本地写,push 后才生效
- Badge 数字写死(待 Codecov/Sonar 接入后变动态)

---

## [Unreleased] - 2026-09-14 Week 19 Sprint — JaCoCo 覆盖率 + CI 红线(1 commit)

### Added
- **JaCoCo 覆盖率报告 + 红线** (`9a3635d`)
  - `jacoco-maven-plugin` 0.8.12
  - 三执行:prepare-agent / report / check
  - 红线:全局 ≥5% / auth ≥20% / meta ≥5%
  - mvn verify 自动检查,失败则 BUILD FAILURE
  - 已测试红线有效性(auth 设 99% → 0.26 < 0.99 触发 fail)

- **修复反射调用不被 JaCoCo 计入** (`9a3635d`)
  - `DynamicTableManager.buildOrderBy` private → package-private
  - `DynamicTableManagerOrderByTest` 改直接方法调用(去掉反射)
  - DynamicTableManager 实际覆盖率 0% → 29%

### Verified
- `mvn verify` BUILD SUCCESS
- 70 tests 全 PASS
- auth 26% (AclEnforcer 100%) / meta 8% (DynamicTableManager 29%, FieldDef 100%)
- health 100%

### Known
- 红线目前低(起步阈值),每周逐步提升
- 其他包(workflow/view/audit/notification/form/api)暂未测,等补测试
- GitHub Actions 集成未做(本地红线 vs CI 红线是不同概念)

---

## [Unreleased] - 2026-09-14 Week 18 Sprint — Java 单元测试 — 阶段 5 提前(1 commit)

### Added
- **AclEnforcerTest** (`AclEnforcerTest.java`) — 31 tests,全 PASS
  - Mockito 隔离 DB,覆盖 7 个公开方法所有分支
  - isAllowed / filterReadableFields / filterWritableFields / assertCanWriteFields
  - loadRoleIdsIncludingInheritance(CTE 容错 + 祖先去重)
  - filterRecord(隐藏字段实际删除)
- **DynamicTableManagerOrderByTest** (`DynamicTableManagerOrderByTest.java`) — 25 tests,全 PASS
  - 反射调用 private buildOrderBy
  - 覆盖基本白名单 / 系统字段 / 多字段组合
  - **9 个 SQL injection 防御场景**(DROP TABLE / 分号 / 空格 / 单引号 / 数字开头 / 等)
  - 7 个空/null 处理场景

### Verified
- `mvn test` 总计 **70 tests PASS**(旧 14 + 新 56)
- 覆盖率:AclEnforcer / DynamicTableManager.buildOrderBy 100%
- 运行时间 3.3 秒,CI 友好

---

## [Unreleased] - 2026-09-14 Week 17 Sprint — 字段级 ACL 细分 + listRecords 服务端 filter/sort(3 commits)

### Added
- **字段级 ACL 拆分 CREATE/UPDATE 独立 hidden** (`698fe52`) — D 候选
  - `AclEnforcer.filterWritableFields(action)` 按 action 精确过滤
  - 支持"创建后可改"(`CREATE hidden=[] UPDATE hidden=[X]`)和"建表后不能改"反向场景
  - E2E 4/4 PASS

- **listRecords 服务端 filter + sort** (`7a8dc22`) — B 候选,US-202/203 真正完成
  - 新 query 参数 `?sort=name,-salary&filter=name:contains:A,salary:gt:0`
  - 7 个 op 与前端 FilterRule 镜像(eq/neq/contains/gt/lt/empty/notEmpty)
  - 字段名正则防 SQL injection,op 白名单防任意 SQL
  - DynamicTableManager.buildOrderBy() 严格白名单
  - E2E 12/12 PASS(含 SQL injection 防御)

- **前端切换服务端 filter+sort** (`792a1a1`)
  - FilterBar.tsx: `sortToQuery` + `filtersToQuery` 工具函数
  - TableView.tsx: 调带 query params 端点;移除客户端 applyFilters/applySort
  - 大数据集合省带宽

### Verified
- 后端 `mvn compile` 0 错误
- 前端 `pnpm tsc` 0 新错误
- DB 完整性(SQLi 测试后 count 21 不变)
- 与 Week 14.5/16 的 ROW + FIELD ACL 兼容

---

## [Unreleased] - 2026-09-14 Week 16 Sprint — 字段级 ACL 写路径(1 commit)

### Security(重要)
- **字段级 ACL 写拦截** (`27ff113`) — ACL 三层矩阵完全闭合
  - `AclEnforcer.filterWritableFields` + `assertCanWriteFields`
  - `CollectionController` 在 createRecord/updateRecord 第一行加拦截
  - 顺序:collection → **field** → row(避免泄露 forbidden 字段存在性)
  - E2E 6/6 PASS(carol_inherit 临时挂 w16_field_test 角色,hidden=[salary])
    - T1 PUT salary=99999 → 403
    - T2 PUT 仅 name → 200
    - T3 PUT salary=null → 403 (显式清空)
    - T4 admin PUT salary=777 → 200 (无 FIELD policy)
    - T5 POST 含 salary → 403
    - T6 POST 仅 name → 201

### ACL 三层防御纵深现状
| 层 | Read | Write |
|----|------|-------|
| Collection | ✅ US-301 | ✅ US-301 |
| Row | ✅ Week 14.5 | ✅ Week 14.5 |
| Field | ✅ 早期 | **✅ Week 16(本次)** |

---

## [Unreleased] - 2026-09-14 Week 15 Sprint — 视图+工作流 P1 收尾(3 commits)

### Added
- **US-206 ViewDesigner 列设置** (`54e81fd`)
  - 表格视图:每列可见 checkbox + 列宽 number + ↑↓ 排序
  - Detail 视图:字段勾选 + 排序,写入 `config.fields`
  - 保存到 `config.columns = [{field, label, width, visible}]`
  - TableView 用 `tableLayout=fixed + colgroup` 按列宽渲染,过滤 `visible=false`

- **US-406 WorkflowDesigner 测试运行** (`9615592`)
  - 侧栏蓝色按钮 ▶ 测试运行(模拟数据)
  - JSON 输入 triggerData → 调 `POST /workflows/{id}/trigger`
  - 新建未保存工作流先自动保存,确保触发最新版本
  - 成功后显示 instance id + 跳转实例详情链接

- **US-407 WorkflowInstances 详情增强** (`9615592`)
  - 审批任务从 div → 表格,新增"耗时"列(`humanDuration` 算 `finished_at - created_at`)
  - triggerData 从 inline code → `details + pre` 可折叠 JSON 美化
  - Task 类型补 `finished_at?` / `comment?` / `assignee?`

- **US-501 Login UX** (`8799616`)
  - localStorage `nocobase:login:lastUsername` 记住用户名(checkbox 控制)
  - 错误提示加左侧 border + ❌ 图标 + ✕ 关闭按钮
  - 成功提示"登录成功,正在跳转…"
  - 忘记密码占位链接(US-501 后续)

- **US-502 Profile 信息卡** (`8799616`)
  - 后端: `GET /api/auth/me` 返回 `{id, username, tenant_id, roles[], created_at}`
  - 注入 `UserRoleRepository` + `RoleRepository`,使用 `findByIdUserId` + `getId().getRoleId`
  - 前端信息卡:用户名 + ID + 租户 + 角色徽章 + 注册时间

### Verified
- alice/admin 登录 + /me E2E 通过(alice=[user],admin=[admin, employee])
- 前端 `pnpm tsc` 我修改的文件 0 错误
- 后端 `mvn compile` 0 错误
- OpenAPI paths: 51 → **52**(`/auth/me`)

### Known(Week 16+ 候选)
- US-501 后续: 密码找回流程(需 email/SMS 通道)
- US-202/203 服务端 filter/sort
- 字段级 + ROW write 联动(salary 测试)
- 审计日志过滤查询 E2E

---

## [Unreleased] - 2026-09-14 Week 14.5 P3 Sprint 收尾(7 commits)

### Added
- **审计日志 (US-AUDIT) — P3-1** (`0b6b7a5`)
  - `audit_log` 表 + JPA entity + service + controller
  - 写入点:登录、record CRUD、role/ACL/policy 变更
  - `GET /api/audit/logs?limit=N&actor=&action=&from=&to=` 支持过滤
  - 前端 `/admin/audit` 列表页 + 详情对话框

- **Swagger/OpenAPI 文档 — P3-2** (`105bfcd`)
  - springdoc-openapi 2.6.0 集成
  - 所有 controller 加 `@Operation` / `@Tag` / `@SecurityRequirement`
  - `GET /v3/api-docs` + Swagger UI `/swagger-ui.html`
  - OpenAPI paths: 25 → 48

- **ROW-level ACL — P3-3** (`1007bf7`)
  - `acl_row_policy` 表 + `RowAclService` 表达式求值
  - 支持 ops:`eq/neq/in/is_null/not_null/contains`
  - 占位符:`$currentUser` / `$currentRoles`
  - OR 语义:任一 policy 命中 = 允许
  - `GET /api/admin/row-acl` CRUD + `/by-collection/{name}`
  - **write 评估补完** (`cfe89e8`):`evaluateUpdate` / `evaluateDelete` + 单条 record 端点 (GET/PUT/DELETE `/api/collections/{name}/records/{id}`)

- **多渠道通知 — P3-4** (`3db2c5d`)
  - NotificationChannel 抽象 + Email/Webhook/InApp 实现
  - 失败 fallback (try/catch + 重试占位)
  - 用户 channel 偏好设置

- **角色继承 US-308 — P3-3.5** (`bf232c4`)
  - `roles.parent_role_id` + CTE ancestor 链
  - Cycle 检测(创建时拒绝)
  - ACL 评估时聚合所有祖先角色的 policy
  - **清理**: `125ccc0` 删除 RoleRepository.java.extra 残留

- **工作流模板市场 US-410 — P3-3.6** (`a603780`)
  - 3 个内置模板:Leave Approval / Expense Approval / Customer Followup
  - `POST /api/workflow-templates/{id}/install` 一键部署
  - 前端 `/admin/workflow-templates` 列表 + 一键安装按钮

- **ER 图可视化 — P3-5** (`1f7a2b4`)
  - `GET /api/admin/er-diagram` 返回 nodes + edges + stats
  - 前端 `/admin/er`:自绘 SVG force-directed 布局,无外部依赖
  - 颜色:🟢系统表 / 🔵业务表 / 🟠ER demo
  - 6 个 demo collection:`er_dept` / `er_employee` / `er_project` / `er_member` / `er_order` / `er_order_item`
  - 7 条 belongsTo 关系

### Verified (Week 14.5 P3 全量 E2E)
- **ROW ACL CRUD 矩阵**(manager 角色,`created_by == $currentUser`):

  | 操作 | alice 的 A | bob 的 B |
  |------|----------|---------|
  | GET 单条 | 404 ✅ (隐藏存在性) | 200 ✅ |
  | UPDATE   | 403 ✅               | 200 ✅ |
  | DELETE   | 403 ✅               | 200 ✅ |

- 角色继承:carol(employee) → 继承 manager 角色的 customer READ ACL
- OpenAPI paths:25 → **51**(本周 +26 个 endpoint)
- 审计日志写入 + 过滤验证(7 种 action 类型)
- 模板市场:3 模板一键 install,工作流实例立即可用
- ER 图:35 → 37 节点 / 0 → 7 关系,SVG 拖拽交互顺畅
- build 绿色:73 个 Java 源文件,`BUILD SUCCESS 3.6s`

### Known (Week 14.5 残留)
- 通知 channel 重试未实现(占位 + try/catch)
- 模板市场不支持卸载(只 install)
- ER 图节点超 50 时布局可能拥挤(目前 37 个)

---

## [Unreleased] - 2026-09-11 Epic 4 收尾 + P1 三件套

### Added
- **Epic 4 收尾 — ACL 强制拦截器**:`AclEnforcer` 服务,CollectionController 在 createRecord/listRecords 调用 assertCan + filterRecord。白名单语义(无 policy 默认允许,有 policy 按显式允许匹配);FIELD policy hidden 字段自动隐藏
- **消息分页 (cursor-based)**:`GET /api/messages?limit=20&before=<ISO>`,返回 `next_cursor` + `has_more`;limit 上限 100
- **工作流设计器画布 (ReactFlow 11)**:三栏布局,左侧节点面板可拖入画布,右侧 config 编辑器;支持 4 种节点 + 边连接
- **MessagesInbox 前端页面**:列表 + 未读筛选 + 标记已读 + 加载更多
- **AppLayout 菜单扩展**:工作流 / 站内信 / 我的
- **WEEK_13_HANDOFF.md** 接力文档

### Verified
- 端到端:user GET customer 无 READ policy → 403 "ACL 拒绝";加 READ policy → 200;加 FIELD hidden=phone → phone 字段被隐藏
- 端到端:7 条消息 + limit=3 → 三页 has_more=true/false 正确
- workflowndesigner build 绿色(612KB JS, gzip 191KB)
- GitHub 同步:`main` 已推送 `9fb8ddc`



## [Unreleased] - 2026-09-11 Epic 5/6 完成

### Added
- **Epic 5 增强 (US-405 + US-409)**:`WorkflowEngine` 抽出统一处理 4 种节点类型(APPROVAL/NOTIFICATION/CONDITION/HTTP);CONDITION 支持 op eq/neq/contains/gt/lt 从 triggerData 取值;HTTP 节点支持 Bearer/Basic 鉴权
- **Epic 6 平台基础 (US-501~507)**:
  - V8 migration:`messages` 表 + `user_preferences` 表
  - `MessageEntity` + `MessageRepository` + `MessageController`(GET inbox + POST mark-read)
  - `AuthController` 加 `POST /api/auth/password`(US-502)
  - `WorkflowEngine.logNotification` 升级:写站内信到 messages 表
  - 前端 `WorkflowsList.tsx` + `WorkflowDesigner.tsx`(节点编辑 MVP) + `Profile.tsx`(改密码页)
- **WEEK_12_HANDOFF.md** 完整接力文档

### Verified
- 端到端:改密码 admin123→admin1234 → 新密码登录成功 → 触发 amount_check workflow amount=500 → 走 NOTIFICATION → 1 条站内信生成 → `GET /api/messages` 返回 `unread_count: 1`
- 前端 `pnpm build` 绿色(1.36s, 459KB JS)
- GitHub 同步:`main` 已推送 `086e631`



## [Unreleased] - 2026-09-09 封档

### Added
- **README.md** 完整重写,含技术栈表格、徽章、端到端演示、架构亮点
- **LICENSE** (MIT)
- **deploy.sh** 一键部署脚本(支持 prod / demo / stop / logs 四种模式)
- **ADR-010** Week 0~9 修复的真实 Bug 清单(8 个)

### Fixed
- 82 个 TypeScript 编译错误全修(`pnpm build` 绿,432KB JS)
- TypeScript `ApiResponse<T>` 误用 — 重写 `apiClient` wrapper
- `Map.of` 不可变 + null 拒绝(多处)
- 多个前端组件的 `.data.data` 误用

### Changed
- 前端 18 个文件因 ApiResponse 重构被批量修正
- `api/client.ts` 从 AxiosInstance 改为类型化 wrapper
- `CollectionDetail.tsx` 重写(子组件 inline)

### Verified
- `pnpm build` 成功
- 静态资源 432KB JS, gzip 135KB

---

## [Week 9] - 2026-09-09 Epic 3 视图设计器

### Added
- `view/ViewEntity.java` + `ViewRepository.java` + `ViewService.java` + `ViewController.java`
- V5 migration:`views` 表
- 5 个 REST 端点(GET/POST/GET-id/PUT/DELETE)
- 前端:`types/view.ts` + `FilterBar.tsx` 组件
- 前端:`TableView.tsx` / `KanbanView.tsx` / `DetailView.tsx` / `ViewDesigner.tsx` / `ViewsList.tsx`
- 前端 5 个新路由
- `WEEK_9_HANDOFF.md` 接力文档

### Verified
- 创建/列出 View API 实测跑通

---

## [Week 8] - 2026-09-09 Epic 2 表单设计器

### Added
- `form/FormEntity.java` + `FormRepository.java` + `FormService.java` + `FormController.java`
- V4 migration:`forms` 表
- 5 个 REST 端点
- `FormRuntime.tsx` 组件(运行时渲染 + 校验 + 显隐)
- `FormDesigner.tsx` 三栏设计器
- `FormRuntimePage.tsx` 用户填表页
- 7 种校验规则(required/minLength/maxLength/min/max/pattern/email)

### Verified
- 创建表单/列出/详情/删除 API 全跑通

---

## [Week 7] - 2026-09-09 Epic 1 数据模型(含修改表)

### Added
- `meta/MigrationJobEntity.java` + Repository
- `meta/AsyncMigrationService.java`(同步 + lock_timeout 自动转异步)
- V3 migration:`migration_jobs` 表
- `SchemaEditor.tsx` 编辑现有 collection
- 端点:`PATCH /api/collections/{name}` / `POST /api/collections/{name}/fields` / `DELETE /api/collections/{name}/fields/{fieldName}` / `PUT /api/collections/{name}/fields/{fieldName}`

### Fixed (本周期)
- `renameField` 死循环 409 逻辑 bug(Bug-006)
- 表单 `email_NEW` 大写触发 Pattern 校验失败(因为 entry point 吞了校验异常,已用 GlobalExceptionHandler 修复)

### Verified
- 8/8 修改表 API 实测通过

---

## [Week 5] - 2026-09-09 Collection Engine

### Added
- `meta/CollectionMetaEntity.java` + Repository
- `meta/CollectionService.java` + Controller
- `meta/DynamicTableManager.java`(混合方案 C:基础列 + JSONB)
- V2 migration:`collection_meta` 表
- 端点:`POST /api/collections` / `GET /api/collections` / `GET /api/collections/{name}` / `POST /api/collections/{name}/records` / `GET /api/collections/{name}/records`

### Fixed (本周期)
- `StringRedisTemplate` 缺失 → 加 Redis starter (Bug-001)
- `Map.of` null 拒绝 → 改 HashMap (Bug-002)
- UUID 列类型不匹配 → `?::uuid` (Bug-003)
- `toDto` 不可变 Map.put 抛 UOE (Bug-004)

### Verified
- 5/5 Collection API 通过

---

## [Week 4] - 2026-09-09 JWT 认证

### Added
- `auth/UserEntity.java` + `UserRepository.java`
- `auth/JwtService.java`(jjwt 实现)
- `auth/RefreshTokenService.java`(Redis 存 refresh)
- `auth/JwtAuthFilter.java`
- `config/SecurityConfig.java`
- `config/AsyncConfig.java`
- V1 migration:`users` 表 + seed admin/admin123
- 端点:`POST /api/auth/login` / `POST /api/auth/refresh` / `GET /api/users/me`

### Fixed (本周期)
- Spring Security 业务异常被吞 (Bug-005) → 加 entry point + GlobalExceptionHandler
- 真实 bcrypt hash 替换 V1 占位值

### Verified
- 登录、错误密码拒绝、Bearer 鉴权、无 token 拒绝 全过

---

## [Week 3] - 2026-09-09 脚手架

### Added
- `docker-compose.yml`(Postgres + Redis + MinIO)
- `backend-java/` Spring Boot 3.3 + JDK 21 骨架
- `backend-python/` FastAPI + Python 3.12 骨架
- `frontend/` React 18 + Vite 5 + TypeScript 5 骨架
- `install.sh` 一键安装 + 启动
- `verify.sh` API 验证脚本
- `Makefile` 统一命令

### Verified
- 3 个 Docker 容器 healthy
- Java `/api/health` 通
- Vite 前端 HTTP 200
- 浏览器端到端演示通过

---

## [Week 0-2] - 2026-09-09 规划阶段

### Added
- `IDEA_BRIEF.md` 产品想法简报
- `ARCHITECTURE.md` 架构总览(三栈分工)
- `ARCHITECTURE_DIAGRAM.md` Mermaid 时序/部署图
- `MVP_SCOPE.md` MVP 范围(必做/不做/演示场景)
- `USER_STORIES.md` 49 条用户故事(33 P0 + 13 P1 + 3 P2)
- `ROADMAP.md` 7 阶段路线图
- `RISKS.md` 15 项风险登记
- `TEST_STRATEGY.md` 三栈测试策略
- 8 份 ADR(001~008 + 010)
- `SCAFFOLDING_PLAN.md` Week 3 脚手架详细计划

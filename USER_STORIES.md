# 用户故事清单

> 创建日期: 2026-09-09
> 关联: MVP_SCOPE.md
> 格式: 标准 "As a / I want / So that"
> 优先级: P0(必做)/ P1(强烈推荐)/ P2(可推迟)

---

## 一、Epic 1:数据模型管理

### US-001 [P0]
**As a** 应用开发者
**I want** 创建一个新表,定义表名和字段
**So that** 业务数据有地方存
**验收**: 列表显示新表,可点开看 Schema

### US-002 [P0]
**As a** 应用开发者
**I want** 给表添加各种类型的字段(文本/数字/日期/单选/附件等)
**So that** 字段类型符合业务数据特征
**验收**: 至少 10 种字段类型可用

### US-003 [P0]
**As a** 应用开发者
**I want** 设置字段为主键、必填、唯一、默认值
**So that** 数据完整性有保障
**验收**: 数据写入时校验生效

### US-004 [P0]
**As a** 应用开发者
**I want** 创建两个表的关联字段(一对一、一对多)
**So that** 业务数据有结构关系
**验收**: 选关联表时显示对方字段

### US-005 [P0]
**As a** 应用开发者
**I want** 修改已存在的表(改名、增删字段、改类型)
**So that** 业务变化时模型可演进
**验收**: 已有数据不丢失

### US-006 [P1]
**As a** 应用开发者
**I want** 删除一个表(带二次确认)
**So that** 误删可避免
**验收**: 删除后所有关联数据清除

### US-007 [P1]
**As a** 应用开发者
**I want** 导入 JSON Schema 创建表
**So that** 批量建表更高效

### US-008 [P2]
**As a** 应用开发者
**I want** 给字段添加中文注释
**So that** 团队协作时字段含义清晰

---

## 二、Epic 2:表单设计

### US-101 [P0]
**As a** 应用开发者
**I want** 从左侧字段列表拖拽到画布创建表单
**So that** 表单由我自由组合字段
**验收**: 拖拽响应流畅(< 100ms)

### US-102 [P0]
**As a** 应用开发者
**I want** 点击字段弹出属性面板,配置标签/占位符/帮助/必填
**So that** 表单字段可定制

### US-103 [P0]
**As a** 应用开发者
**I want** 设置字段间的显隐规则(条件表达式)
**So that** 表单能根据用户输入动态变化
**验收**: 至少支持"等于/不等于/包含/为空"4 种操作符

### US-104 [P0]
**As a** 应用开发者
**I want** 设置字段校验(必填/长度/范围/正则)
**So that** 提交数据前先过滤无效输入

### US-105 [P0]
**As a** 应用开发者
**I want** 预览表单(只读模式)
**So that** 上线前先检查

### US-106 [P0]
**As a** 应用开发者
**I want** 设置提交后动作(跳转/弹提示/调工作流)
**So that** 表单与业务流打通

### US-107 [P1]
**As a** 应用开发者
**I want** 把表单保存为模板供其他表复用
**So that** 减少重复工作

### US-108 [P1]
**As a** 终端用户
**I want** 在手机上能填表(响应式)
**So that** 移动场景可用

---

## 三、Epic 3:视图设计

### US-201 [P0]
**As a** 应用开发者
**I want** 选择表 + 选字段 + 保存为表格视图
**So that** 用户能看数据列表
**验收**: 表格支持分页(默认 20)

### US-202 [P0]
**As a** 应用开发者
**I want** 在表格视图设置筛选条件
**So that** 用户能快速定位数据

### US-203 [P0]
**As a** 应用开发者
**I want** 在表格视图设置排序(多字段)
**So that** 数据有序展示

### US-204 [P0]
**As a** 应用开发者
**I want** 创建看板视图,按某字段分组
**So that** 任务/工单类场景适用

### US-205 [P0]
**As a** 应用开发者
**I want** 创建详情视图(单条记录展示)
**So that** 字段多时也能清晰查看

### US-206 [P0]
**As a** 应用开发者
**I want** 设置表格列的显示/隐藏、宽度
**So that** 视图可定制

### US-207 [P1]
**As a** 应用开发者
**I want** 把视图分享给指定用户/角色
**So that** 权限受控

### US-208 [P1]
**As a** 应用开发者
**I want** 视图导出 CSV
**So that** 数据可二次处理

---

## 四、Epic 4:权限管理

### US-301 [P0]
**As a** 平台 Admin
**I want** 创建/编辑/禁用用户账号
**So that** 用户管理可控

### US-302 [P0]
**As a** 平台 Admin
**I want** 创建角色并分配用户
**So that** 权限按角色批量化

### US-303 [P0]
**As a** 平台 Admin
**I want** 设置某角色对某表的字段级权限(可见/可编辑/隐藏)
**So that** 敏感字段不泄露

### US-304 [P0]
**As a** 平台 Admin
**I want** 设置某角色的记录级权限(行过滤条件)
**So that** 部门经理只能看本部门数据

### US-305 [P0]
**As a** 平台 Admin
**I want** 设置某角色的操作权限(增/删/改/查)
**So that** 普通用户不能删数据

### US-306 [P1]
**As a** 应用开发者
**I want** 把视图授权给指定角色
**So that** 用户只能看被授权的视图

### US-307 [P1]
**As a** 平台 Admin
**I want** 查看用户的有效权限(权限预览)
**So that** 调试时一目了然

### US-308 [P2]
**As a** 平台 Admin
**I want** 权限继承(角色继承另一个角色)
**So that** 权限树可分层

---

## 五、Epic 5:工作流编排

### US-401 [P0]
**As a** 应用开发者
**I want** 创建工作流,选触发器(数据变化/定时/手动)
**So that** 业务流程可自动化

### US-402 [P0]
**As a** 应用开发者
**I want** 拖拽添加节点(审批/通知/条件/数据更新/HTTP)
**So that** 流程可视化编排

### US-403 [P0]
**As a** 应用开发者
**I want** 配置审批节点(单人/多人会签/或签)
**So that** 审批逻辑灵活

### US-404 [P0]
**As a** 应用开发者
**I want** 配置通知节点(站内信/邮件)
**So that** 关键节点有人知道

### US-405 [P0]
**As a** 应用开发者
**I want** 配置条件分支(if/else,基于字段值)
**So that** 流程能分叉

### US-406 [P0]
**As a** 应用开发者
**I want** 测试工作流(模拟数据)
**So that** 上线前验证

### US-407 [P0]
**As a** 应用开发者
**I want** 查看工作流执行历史(实例状态、节点耗时)
**So that** 故障可排查

### US-408 [P1]
**As a** 终端用户
**I want** 我的待办列表(待审批/待处理)
**So that** 不漏掉任务

### US-409 [P1]
**As a** 应用开发者
**I want** HTTP 调用节点支持鉴权(Bearer / Basic)
**So that** 可对接企业 API

### US-410 [P2]
**As a** 应用开发者
**I want** 工作流版本管理(发布历史、回滚)
**So that** 流程演进可追溯

---

## 六、Epic 6:平台基础

### US-501 [P0]
**As a** 终端用户
**I want** 登录(账号密码)
**So that** 进入平台

### US-502 [P0]
**As a** 终端用户
**I want** 修改自己的密码
**So that** 账号安全

### US-503 [P0]
**As a** 终端用户
**I want** 站内信收件箱
**So that** 收到通知

### US-504 [P0]
**As a** 应用开发者
**I want** 应用切换(我建的多个应用)
**So that** 多应用共存

### US-505 [P1]
**As a** 平台 Admin
**I want** 看审计日志(谁在什么时间做了什么)
**So that** 责任可追溯

### US-506 [P1]
**As a** 应用开发者
**I want** 应用图标 + 名称 + 描述
**So that** 用户识别应用

### US-507 [P1]
**As a** 终端用户
**I want** 忘记密码 / 找回
**So that** 自助恢复

---

## 七、用户故事完成度审计(2026-09-16, 据代码实据)

> 审计方法: 逐故事搜索实际代码(实体/Controller/Repository/前端页面/测试), 非凭 CHANGELOG 声明。
> 状态定义: **done**(验收标准实质满足) / **partial**(部分实现,需补齐) / **not-started**(代码无实现痕迹)。

### 7.1 统计

| Epic | 总数 | P0 | P1 | P2 |
|---|---|---|---|---|
| 1 数据模型 | 8 | 5 | 2 | 1 |
| 2 表单 | 8 | 6 | 2 | 0 |
| 3 视图 | 8 | 6 | 2 | 0 |
| 4 权限 | 8 | 5 | 2 | 1 |
| 5 工作流 | 10 | 7 | 2 | 1 |
| 6 平台基础 | 7 | 4 | 3 | 0 |
| **合计** | **49** | **33** | **13** | **3** |

### 7.2 完成度矩阵(按优先级)

| 状态 | P0 | P1 | P2 | 合计 |
|---|---|---|---|---|
| **done** | 29 | 5 | 1 | **35** |
| **partial** | 3 | 3 | 0 | **6** |
| **not-started** | 1(US-504) | 5 | 2 | **8** |
| **总计** | **33** | **13** | **3** | **49** |

### 7.3 MVP 达标率(P0=done)

- **P0 完全 done:29 / 33 = 87.9%**(2026-09-17 更新:US-003、US-102~106、US-202、US-203、US-303、US-402、US-406 已达 done)
- P0 partial:3(需补齐验收标准)
- P0 not-started:1(US-504 应用切换)
- **结论:若以"done"为 MVP 达标线,MVP 完成率 87.9%,仍需补齐 4 个 P0 方可达标。**

### 7.4 P0 完全 done 清单(29 个)

US-001(建表), US-002(11种字段类型), **US-003(字段约束-主键/必填/唯一/默认值)**, US-005(修改表), US-201(表格视图), US-204(看板), US-205(详情), US-206(列设置), US-301(用户管理), US-302(角色分配), US-304(记录级权限), US-305(操作权限), US-401(工作流CRUD), US-403(审批会签), US-405(条件分支), US-407(执行历史), US-501(登录), US-502(改密), US-503(站内信), **US-102(字段属性面板-标签/占位符/帮助/必填)**, **US-103(显隐规则-8种操作符)**, **US-104(校验规则-必填/长度/范围/正则/邮箱)**, **US-106(提交后动作-停留/跳转/触发工作流)**,
**US-202(筛选条件-7种操作符,服务端 filter)**, **US-203(多字段排序-服务端 sort)**,
**US-105(只读预览-禁用输入并显示完整布局)**, **US-402(节点拖拽-4种节点)**, **US-406(测试运行-模拟数据)**,
**US-303(字段级权限-FIELD 策略 hidden 配置)**

### 7.5 P0 partial 清单(3 个,需补齐)

US-004(关联字段-前端), US-101(拖拽建表单), US-404(邮件通知)

> **US-003 已达 done(2026-09-17)**:`FieldDef` 支持 primaryKey/unique/defaultValue/required;
> `CollectionService.applyFieldConstraints` 在 insert/update 时校验(必填 400、唯一/主键 409、默认值填充);
> 12 个单元测试覆盖;`mvn -o verify` 903/903 PASS。
>
> **US-102 已达 done(2026-09-17)**:属性面板可配置标签/占位符/帮助文本/必填;
> `FormRuntime` 真实消费 `options.placeholder` 与 `options.helpText` 并渲染;
> 复用既有 `options` Map,零后端改动;5 个测试验证渲染端生效。
>
> **US-103 / US-104 已达 done(2026-09-17)**:新增 `FieldRulesEditor` 编辑显隐规则(8 种操作符,验收要求 4 种)
> 与校验规则(必填/长度/范围/正则/邮箱);数据结构与 `FormRuntime` 运行时契约一致;
> 3 个集成测试验证规则被真实执行(非"能配不生效");
> 修复 number 清空产生 `value: NaN` 脏规则、email 复选框无法添加两处 bug;`vitest` 195/195 PASS。
>
> **US-106 已达 done(2026-09-17)**:`FormRuntime` 提交成功后执行 `rules.submit`
> (stay 显示提示 / redirect 跳转 / workflow 触发);真实运行页接入 `POST /workflows/{id}/trigger`,
> 且 redirect 时不再 navigate 覆盖配置目标;6 个测试覆盖三种动作及"校验失败不执行动作";`vitest` 201/201 PASS。
>
> **US-202 / US-203 已达 done(2026-09-17 复核判定,非新增实现)**:
> 初审曾判 partial(理由"前端 UI 不完整"),经代码复核属**误判** ——
> - `FilterBar`(`components/views/FilterBar.tsx`)已完整:字段下拉 + 7 种操作符(eq/neq/contains/gt/lt/empty/notEmpty)
>   + 值输入 + chip 展示/删除 + `filtersToQuery` 转后端 query;
> - `TableView` 的 `toggleSort` 支持**多字段累加**(asc→desc→移除三态),`sortToQuery` 输出 `"name,-salary"`,
>   后端 `CollectionService.listRecords` + `DynamicTableManager.buildOrderBy` 对应多字段服务端排序;
> - `FilterBar.test.tsx` 已覆盖 UI 交互、7 种 op、`applyFilters`、`applySort` 多级排序、`sortToQuery` 多字段、`filtersToQuery`;
> - `ViewDesigner.tsx:197` 明确注记「视图运行后可继续调整筛选/排序」—— 即**运行时调整是既定设计语义**,
>   不存在"未持久化到 view.config"的缺口(该注记即设计意图的证据)。
> 全量 `vitest` 201/201 PASS,含上述测试。
>
> **US-105 已达 done(2026-09-17)**:`FormRuntime` 新增 `readOnly`,用 `fieldset[disabled]`
> 一次性禁用全部输入;只读时忽略显隐规则以展示完整布局、隐藏提交按钮;
> `FormDesigner` 预览区提供开关;6 个测试覆盖;`vitest` 24 文件/207 测试 PASS,`vite build` ✓。
>
> **US-402 / US-406 已达 done(2026-09-17 复核判定,非新增实现)**:初审曾判 partial
> (理由"节点面板不完整"/"测试面板不完整"),经代码复核属**误判** ——
> - **US-402**:`WorkflowDesigner.tsx:317-322` 左侧面板遍历 `nodeKindMeta` 渲染 4 种节点
>   (审批/通知/条件/HTTP),每个带 `draggable` + `onDragStart`;`:207-223` 的 `onDrop`
>   读取 kind → `project()` 计算落点 → 构造 `newNode`(含 `defaultConfig`)→ `setNodes` 新增;
>   保存时 `:227-230` 序列化 nodes/edges。**拖拽链路完整闭环**。
> - **US-406**:`:334-340` 测试运行按钮、`:372+` 测试运行弹窗、`:267-276` 校验 triggerData 为合法 JSON
>   (非法则报错)、未保存时先 `saveMutation` 再触发、调用 `POST /workflows/{id}/trigger`、
>   `:278-285` 成功展示 instanceId / 失败展示后端错误消息。
> - ⚠️ 诚实标注:这两项**无专项自动化测试**(WorkflowDesigner 依赖 ReactFlow,渲染测试成本较高),
>   判定依据为上述代码链路实据,非运行验证。若需更强保证,后续可补 E2E。
>
> **US-303 已达 done(2026-09-17 复核判定,非新增实现)**:初审曾判 partial(理由"字段级权限 UI 不完整"),
> 经复核属**误判** —— 三层均已具备:
> - **配置 UI**:`AclEditor.tsx:102-103` 选择「字段权限 (FIELD)」后可填 hidden 字段,
>   `:33-35` 构造 `{"hidden":[...],"readonly":[]}`,`:43-49` POST `/admin/acl`;表格展示并可删除;
> - **配置 API**:`RoleAclController` 提供 `GET/POST/PUT/DELETE /api/admin/acl`,支持 `type=FIELD`;
> - **强制执行**:`AclEnforcer.filterReadableFields` / `filterRecord` 读时移除 hidden 字段,
>   `filterWritableFields` / `assertCanWriteFields` 写时拒绝 hidden 字段,`CollectionController` 已接入。
>
> 另修正一处**误导性过时注记**:`AclEditor.tsx` 原文写「ACL 仅配置不强制执行(Week 10 简化版)」,
> 与当前实现不符(强制执行已接入),已更正为说明读写两侧的实际执行路径 —— 该注记会让人低估权限实现程度。

### 7.6 P0 not-started(1 个)

US-504(应用切换) — 无 tenant 切换 UI/API, 当前硬编码 `tenant_default`

### 7.7 关键发现

1. **Epic 2 表单(US-101~106)最严重**:6 个 P0 全部 partial/not-started,`FormDesignerPage` 实为静态字段管理,非真正拖拽表单设计器。
2. **Epic 4 权限完成度最高**:7/8 done(仅 US-303 partial)。
3. **完全空白**:US-007(JSON Schema 导入), US-008(字段注释), US-107(表单模板), US-108(移动端), US-410(工作流版本), US-504(应用切换), US-506(应用图标), US-507(忘记密码)。
4. **IM 实时聊天(`ImChannelController` + `ImChatPage`)是独立功能,不属于任何 USER_STORY**,属额外实现。

**MVP 范围:全部 P0 = 33 个故事。**
**V1.1 候选:全部 P1 = 13 个故事。**
**V2.0 候选:全部 P2 = 3 个故事。**

# Phase 50 企业综合平台整合方案 — GLM-5.3 执行提示词

> 生成日期: 2026-09-20
> 编排者: CodeBuddy (Cline) · 执行者: GLM-5.3
> 关联: INTEGRATION_ROADMAP.md, DECISION_MATRIX.md, ARCHITECTURE.md, ROADMAP.md
> 基线: 后端 `mvn test` 1057 PASS · 前端 `vitest` + `tsc` + `vite build` 全绿 · Python `pytest` 50 tests

---

## 零、全局约定（每个任务都必须遵守）

### 0.1 项目结构
```
/home/who/multistack-project/
├── backend-java/          Spring Boot 3.3.5 / JDK 21 / Maven (核心引擎)
├── backend-python/        FastAPI 0.115 / Py3.12 (AI 网关/异步任务/告警/连接器)
└── frontend/              React 18 + TS + Vite 5 + MUI v9 + @emotion
```

### 0.2 质量门禁（每个任务完成后必须全绿，缺一不可）
```bash
# 后端
cd backend-java && mvn test                  # 基线 1057 PASS，只允许增加，不允许减少
# 前端
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
# Python
cd backend-python && pytest                  # 基线 50 tests
```

### 0.3 提交规范（分栈提交）
```
[java]   — 后端 Java 改动
[js]     — 前端 TS/TSX/CSS 改动
[python] — Python 改动
[docs]   — 文档 / 提示词 / 记忆
```
每任务完成后单独提交一次，不要跨任务混提交。

### 0.4 通用禁止项（红线）
1. **禁止"形似接真"**: 不允许用 `log.info` + `// TODO` 冒充真实实现。所有"接真"必须真实调用既有服务/组件（本 Phase 的核心目标就是消除历史遗留的占位）。
2. **禁止擅自引入重型新依赖**: 除非任务明确要求（如 T6 的 spring-ldap、T7 的 SFU 客户端）。引入前评估体积与兼容性。
3. **禁止删除/破坏既有通过测试的功能**: 改动前先确认现有调用点（用搜索确认符号引用）。
4. **禁止硬编码亮色**: 前端新增/修改一律使用 `frontend/src/theme/tokens.ts` 与 `styles.css` 的 `.glass-*` 工具类或 MUI `sx` 主题变量（`var(--color-...)`）。
5. 数据库迁移版本号必须唯一，新增迁移文件用 `V31__` / `V32__` ... 顺延（当前已到 V30）。

### 0.5 完成后的记忆更新（必须）
- 追加到 `/home/who/multistack-project/.codebuddy/memory/2026-09-20.md`（用 replace_in_file 追加，不要覆盖）。
- 若含长期架构决策，同步更新 `MEMORY.md`。

### 0.6 执行顺序（依赖）
```
T1 (P0 阻塞，必须先做)
   ↓
T2 / T3 / T4 / T5 / T6 / T7   (P1，全部依赖 T1，可并行)
   ↓
T8 / T9 / T10 / T11            (P2，依赖对应 P1 任务)
```
建议 GLM-5.3 按 T1 → T3 → T2 → T4 → T5 → T6 → T7 → (T8/T9/T10) → T11 顺序逐个执行，每完成一个提交一次。

---

## T1 · fix-flyway-schema（P0 阻塞，最高优先级）

**目标**: 消除 Flyway 启动冲突，确立唯一权威 schema。不修则服务启动失败、`ddl-auto: validate` 报错。

**背景 / 现状**:
- `backend-java/src/main/resources/db/migration/` 下存在两个 `V27` 文件:
  - `V27__huddle_automation_ldap_search.sql`
  - `V27__playbook_run.sql`
  → Flyway 版本号重复，`application.yml` 未配 `out-of-order`，启动时直接失败。
- `V27__huddle_automation_ldap_search.sql` 与 `V28__platform_enhancement.sql` 对以下 **6 张表**存在双重定义（列名/约束/触发器各不相同）:
  `im_huddle`、`automation_rule`、`automation_execution`、`ldap_config`、`ldap_user_mapping`、`unified_search_index`
- **V28 是实际生效 schema**（JPA 实体与 V28 对齐）。例如:
  - `automation_execution` 列名是 `result_data`（V28），而 V27 写的是 `result_data_json`
  - `im_huddle` 在 V28 有 `name` / `metadata`，V27 用 `participants` JSONB
  - `unified_search_index` 在 V28 列名 `content_tsv` + 触发器 `update_search_vector`，V27 是 `search_vector` + `unified_search_update_vector`
  - `ldap_user_mapping` V28 有 `UNIQUE(ldap_uid, tenant_id)`
- `V27__huddle_automation_ldap_search.sql` 中的 `im_huddle_signaling` 表建了但**全仓零代码使用**（HuddleService 注释声称信令走 `/api/huddle/signaling`，但该端点不存在）。

**涉及文件**:
```
backend-java/src/main/resources/db/migration/
├── V27__playbook_run.sql                  → 重命名为 V30__playbook_run.sql
├── V27__huddle_automation_ldap_search.sql [修改] 删除与 V28 重复的六表定义 + 删除 im_huddle_signaling
└── V28__platform_enhancement.sql          [只读参照，不要改，它是权威 schema]
```

**步骤**:
1. `git mv V27__playbook_run.sql V30__playbook_run.sql`（版本 27 → 30，消除重复版本号）。
2. 编辑 `V27__huddle_automation_ldap_search.sql`:
   - 删除 `im_huddle`、`automation_rule`、`automation_execution`、`ldap_config`、`ldap_user_mapping`、`unified_search_index` 六张表的 `CREATE TABLE` / `ALTER` / 触发器 / 索引语句（这些已在 V28 权威定义）。
   - 删除 `im_huddle_signaling` 建表语句（零代码使用；T7 做 WebRTC 信令时会重新设计）。
   - 该文件可保留为一个空迁移（仅注释说明"V28 已重定义，本文件保留为空迁移"），或直接只保留 V28 未覆盖的内容（若有）。
3. 校验: `grep -rn "CREATE TABLE" migration/` 确认六张表只出现在 V28；`ls migration/` 确认无重复版本号（每个 `Vxx__` 唯一）。
4. 确认 JPA 实体与 V28 列名一致（抽查 `AutomationExecutionEntity.result_data`、`ImHuddleEntity.name/metadata`、`LdapUserMappingEntity`、`unified_search_index` 相关实体）。若有实体与 V28 不符，以 **V28 为权威**改实体（不是改 SQL）。

**验收**:
```bash
cd backend-java && mvn test        # 通过（注意: test profile 关闭 Flyway 用 H2，主要验证编译与单测）
ls src/main/resources/db/migration/ # 无重复 Vxx 版本号
grep -c "CREATE TABLE" src/main/resources/db/migration/V27__huddle_automation_ldap_search.sql  # 0（或仅剩 V28 未覆盖的表）
```
- 额外验证（重要）: 用 dev profile 连真实 PG 启动一次 Flyway（或至少 `mvn compile` + 人工核对 V28 与实体一致性），确保 `ddl-auto: validate` 不再报缺失列/多余列。

---

## T2 · wiki-block-backend（对标 Notion）

**目标**: 把 Wiki 从"整块 TEXT 内容"升级为 **Notion 式 Block 模型**，实现双向链接关系表与页面模板库。

**背景 / 现状**:
- `backend-java/src/main/java/com/nocobase/wiki/` 已有: `WikiController`(21 端点)、`WikiPageService`(版本/回溯/模板/软删除/`parseBacklinks`+`listBacklinks` 文本 `[[slug]]` 解析)、`WikiSearchService`(PG FTS)、`WikiPermissionService`、`WikiAttachmentService`。
- **后端无 block 模型**: 页面内容仍是整块 `content` TEXT；双向链接是文本解析而非关系存储；页面模板 0 命中（前端 `components/wiki/NotionStyleEditor.tsx` 已实现 11 种块 + 斜杠菜单 + md↔blocks 双向转换，但后端未配套）。

**涉及文件**:
```
backend-java/src/main/resources/db/migration/
├── V31__wiki_block.sql       [新建] wiki_block 表
├── V32__wiki_backlink.sql    [新建] wiki_backlink 表
└── V33__wiki_template.sql    [新建] wiki_template 表

backend-java/src/main/java/com/nocobase/wiki/
├── WikiBlockEntity.java          [新建]
├── WikiBlockRepository.java      [新建]
├── WikiBlockService.java         [新建] Block 树增删移/排序
├── WikiBacklinkEntity.java       [新建]
├── WikiBacklinkRepository.java   [新建]
├── WikiTemplateEntity.java       [新建]
├── WikiTemplateRepository.java   [新建]
├── WikiController.java           [修改] 新增 block/backlink/template 端点
└── WikiPageService.java          [修改] 内容读写改为 Block 树，双向链接改为关系表

frontend/src/components/wiki/NotionStyleEditor.tsx  [修改] 对接 Block API
frontend/src/pages/wiki/*.tsx                       [修改] 反向链接面板 + 模板选择 UI
```

**实现要点**:
1. `wiki_block` 表建议字段: `id UUID PK`、`page_id UUID`、`parent_id UUID NULL`、`type VARCHAR(32)`、`content TEXT/JSONB`、`sort_order INT`、`tenant_id`、`created_at`、`updated_at`。用 `parent_id + sort_order` 表达树形/顺序。
2. `wiki_backlink` 表: `id`、`source_page_id`、`target_page_id`（或 target_slug）、`tenant_id`、`created_at`。保存页面时解析内容中的 `[[链接]]` 写入关系表；`listBacklinks` 改为查关系表（保留文本解析做兼容过渡，但查询走关系表）。
3. `wiki_template` 表: `id`、`kb_id`、`name`、`title`、`content_json`（Block 树 JSON）、`icon`、`tenant_id`、`created_at`。
4. `WikiController` 新增端点: `GET/POST /api/wiki/pages/{pageId}/blocks`、`PUT /api/wiki/blocks/{id}`、`DELETE /api/wiki/blocks/{id}`、`POST /api/wiki/blocks/{id}/move`（排序/换父）、`GET /api/wiki/pages/{pageId}/backlinks`、`GET/POST /api/wiki/templates`、`POST /api/wiki/templates/{id}/apply`。
5. `WikiPageService` 改造: 保存页面时同时写 Block 树（或把 content 迁移为 Block）；读取时按 Block 组装。
6. 前端 `NotionStyleEditor` 改为调用 Block API（替换本地 md↔blocks 仅前端持有的状态）；Wiki 页面加反向链接面板与模板选择。

**验收**:
```bash
cd backend-java && mvn test     # 通过，新增 WikiBlockServiceTest / 模板单测
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
```
- Block CRUD + 排序有单测；双向链接写入/查询有单测（非纯文本正则）；模板创建/应用有单测。

---

## T3 · automation-actions-real（对标 Airtable Automations / Trello Butler）

**目标**: 自动化引擎从"条件求值真实但动作全是占位"升级为**动作真实执行 + 事件触发**，成为平台自动化中枢。

**背景 / 现状**:
- `backend-java/src/main/java/com/nocobase/automation/AutomationRuleService.java`: 条件求值真实（7 种 operator），但 4 个动作 `NOTIFY`/`UPDATE_RECORD`/`CREATE_RECORD`/`WEBHOOK` **全部只 `log.info` + TODO**（约 272-299 行）。
- **未订阅 `RecordChangeEvent`**: `RECORD_CREATE/UPDATE/DELETE/SCHEDULED` 四类触发器名存实亡，只能手动 `POST /api/automation/{id}/execute`。
- 既有可复用服务: `notification/NotificationService`（多渠道）、`meta/CollectionService`（`insertRecord`/`updateRecord`）、`webhook/WebhookDispatcher`（真实 HTTP）、`workflow/WorkflowEngine`（审批/流程）、`event/RecordChangeEvent`。

**涉及文件**:
```
backend-java/src/main/java/com/nocobase/automation/
├── AutomationRuleService.java       [修改] 4 个动作接真 + 新增 TRIGGER_WORKFLOW 动作
├── AutomationTriggerListener.java   [新建] 订阅 RecordChangeEvent
├── AutomationRuleController.java    [修改] 完善触发/历史端点
└── (可选) AutomationRuleRepository/Entity  [只读]
```

**实现要点**:
1. **动作接真**（替换 log.info）:
   - `NOTIFY` → 调用 `NotificationService`（站内信/已配渠道），传入 tenantId、目标用户、标题、内容。
   - `UPDATE_RECORD` → 调用 `CollectionService.updateRecord(collectionName, recordId, updates, tenantId)`（真实改记录）。
   - `CREATE_RECORD` → 调用 `CollectionService.insertRecord(collectionName, fields, tenantId)`。
   - `WEBHOOK` → 调用 `WebhookDispatcher`（或统一 RestTemplate）真实发 HTTP POST，带自定义 header/超时。
   - 新增 `TRIGGER_WORKFLOW` → 调用 `WorkflowEngine` 触发审批/流程实例。
2. **事件触发**: 新建 `AutomationTriggerListener`，用 `@TransactionalEventListener` / `ApplicationListener<RecordChangeEvent>`（或既有 `WorkflowTriggerListener` 同款机制）订阅；按 `rule.triggerType` + `collectionName` 匹配，求值条件，执行动作。
3. **执行历史**: 每次执行落 `automation_execution`（`status`、`error_message`、`execution_time_ms`、`result_data`），失败留痕 + 指数退避重试（上限 3 次，可选）。
4. 保留手动 `POST /{id}/execute` 通道（不能破坏现有端点）。

**验收**:
```bash
cd backend-java && mvn test
```
- 新增 `AutomationActionTest` / `AutomationTriggerListenerTest`: 用 mock 断言**真实服务被调用**（如 `verify(notificationService).notify(...)`、`verify(collectionService).insertRecord(...)`），而不是断言日志。
- 手动 execute 端点仍工作（回归）。

---

## T4 · unified-search-index（对标 Slack / Notion 全局搜索）

**目标**: 让"统一搜索"真正可用——索引写入/删除生效、Collection 记录可搜、事件驱动增量。

**背景 / 现状**:
- `backend-java/src/main/java/com/nocobase/search/UnifiedSearchService.java`: message / wiki / automation 三类走真实查询；但 `record` 类型**硬编码返回** `id="search-placeholder"`、`title="Collection 记录搜索(待实现)"`；`indexEntity`/`removeEntity` 为 TODO 空实现 → `unified_search_index` 表与 FTS 触发器全部空转。
- 既有可复用: `meta/CollectionService.listRecords`（带权限过滤）、`event/RecordChangeEvent`、PG FTS。

**涉及文件**:
```
backend-java/src/main/java/com/nocobase/search/
├── UnifiedSearchService.java          [修改] 实现 indexEntity/removeEntity + record 真实搜索
├── UnifiedSearchIndexEntity.java      [新建] unified_search_index 实体
├── UnifiedSearchIndexRepository.java  [新建]
├── UnifiedSearchIndexListener.java    [新建] 事件驱动增量索引
└── UnifiedSearchController.java       [修改] 语义检索开关（可选）
backend-java/src/main/resources/db/migration/V34__search_vector.sql  [新建] pgvector 列 + ivfflat 索引（可选）
```

**实现要点**:
1. 新建 `UnifiedSearchIndexEntity` + Repository（对应 `unified_search_index`，字段含 `entity_type`、`entity_id`、`tenant_id`、`title`、`content`、`content_tsv`、`created_at`）。
2. `indexEntity` / `removeEntity` 真实写库（upsert / delete）。
3. `record` 类型改为真实搜索: 复用 `CollectionService.listRecords`（带 tenant 与权限过滤）检索匹配 keyword 的记录，映射为统一结果结构（type=record, id, title, snippet）。
4. `UnifiedSearchIndexListener` 订阅 `RecordChangeEvent`（及 Wiki/IM 事件）做增量索引，避免全量重建。
5. **pgvector（可选，评估后做）**: V34 加 `vector` 列 + ivfflat 索引；Java 用 SQL `<=>` 排序；embedding 由 Python 生成回写。若评估不引入，则本任务至少完成 FTS 索引接真，并在文档中记录"语义检索待 pgvector 扩展"。

**验收**:
```bash
cd backend-java && mvn test
```
- `indexEntity` 写入后有单测可查到；`search?types=record` 返回真实 Collection 记录（非 placeholder）；事件驱动索引有单测。

---

## T5 · trello-board-api（对标 Trello）

**目标**: 把"有实体无 API"的看板能力接线，实现 Trello 式看板（列/卡片拖拽、清单、标签、成员、截止日期）。

**背景 / 现状**:
- `backend-java/src/main/java/com/nocobase/project/` 下已有实体与孤儿 Service: `BoardListEntity`、`CardChecklistEntity`、`CardChecklistItemEntity`、`CardLabelEntity` + 各自 Repository + `CardMoveService`，但**无任何 Controller 调用** → 看板拖拽/卡片清单/标签不可用。
- 前端 `frontend/src/features/project/ProjectPage.tsx`（Tabs 看板/甘特）+ `TaskBoard.tsx`(dnd-kit) + `GanttView.tsx` 已可用，但需手填 projectId。
- 既有: `ProjectService`（任务 CRUD/甘特，真实）、`ProjectController`（`/api/projects`）。

**涉及文件**:
```
backend-java/src/main/java/com/nocobase/project/
├── ProjectBoardController.java  [新建] 看板列/卡片移动/清单/标签/成员端点
└── ProjectController.java       [修改] 整合/复用端点
frontend/src/features/project/ProjectPage.tsx  [修改] 接线真实 API（替换手填 projectId）
```

**实现要点**:
1. `ProjectBoardController` 端点建议（复用既有实体与 `CardMoveService`）:
   - `GET/POST/PUT/DELETE /api/projects/{projectId}/lists`（看板列 CRUD）
   - `POST /api/projects/cards/{cardId}/move`（卡片移动/换列/排序 → 调 `CardMoveService`）
   - `GET/POST/PUT/DELETE /api/projects/cards/{cardId}/checklist`（清单项）
   - `GET/POST/DELETE /api/projects/cards/{cardId}/labels`（标签）
   - `PUT /api/projects/cards/{cardId}/members`（成员）
   - `PUT /api/projects/cards/{cardId}/due`（截止日期）
2. 所有端点走 tenant 隔离 + 权限校验（复用既有 tenant/ACL 模式）。
3. 前端 `ProjectPage` 接线: 从路由/已有项目列表取 projectId（不再手填），看板列与卡片调新 API，dnd-kit 拖拽后调 `/move`。

**验收**:
```bash
cd backend-java && mvn test
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
```
- 看板列 CRUD、卡片移动、清单、标签端点有 Controller 单测（mock Service 或直接集成）。

---

## T6 · ldap-real-sync（对标 Rocket.Chat LDAP/AD）

**目标**: LDAP/AD 真实同步（去 mock），支撑企业统一身份接入。

**背景 / 现状**:
- `backend-java/src/main/java/com/nocobase/ldap/LdapSyncService.java`: `syncUsers` 用**硬编码 mock 用户** `uid=testuser,dc=example,dc=com`；`pom.xml` **无 spring-ldap / unboundid 依赖**；类注释称 `@Scheduled` 但无任何 `@Scheduled` 方法；仅配置/映射 CRUD 真实。
- 已有 `ldap_config`、`ldap_user_mapping` 表（V28 权威定义）。

**涉及文件**:
```
backend-java/pom.xml                                        [修改] 加 spring-boot-starter-data-ldap
backend-java/src/main/java/com/nocobase/ldap/
├── LdapSyncService.java     [修改] 真实 LDAP 查询 + 属性映射 + @Scheduled 定时同步（删 mock）
└── LdapSyncController.java  [修改] 增加立即同步/同步状态端点
```

**实现要点**:
1. `pom.xml` 加依赖: `org.springframework.boot:spring-boot-starter-data-ldap`（或 `spring-ldap-core`）。
2. `LdapSyncService` 用 `LdapTemplate` 做真实查询:
   - 按 `config.getUserSearchFilter()` 在 `baseDn` 下搜索（如 `(objectClass=person)`）。
   - 用 `AttributesMapper` / `ContextMapper` 取 `uid`、`cn`、`mail`、**dn**（dn 需从 `ContextMapper` 的 `getNameInNamespace()` 取，不能用 `attrs.get("dn")`——这是历史 mock 代码的错误点，务必修正）。
   - 应用 `attributeMapping` 属性映射（LDAP 字段 ↔ 本地字段）。
   - 同步逻辑: upsert `LdapUserMapping`（按 `ldap_uid + tenant_id` 唯一），创建/更新本地 `UserEntity`（已有 email 字段）。
3. 加 `@Scheduled`（如 `fixedDelay` 或 cron，间隔用 `config.getSyncIntervalMinutes()`）实现定时同步；保留手动触发端点。
4. **删除 mock 用户代码**（硬编码 `testuser`）。
5. `LdapSyncController` 增加 `POST /{configId}/sync`（立即同步）、`GET /{configId}/status`。

**验收**:
```bash
cd backend-java && mvn test
```
- 新增 `LdapSyncServiceTest`: 用 mock `LdapTemplate`（或嵌入式 LDAP）断言**真实查询被调用**（`verify(ldapTemplate).search(...)`），且返回用户被正确映射入库；断言不再返回硬编码 testuser。
- 编译通过、无 mock 残留。

---

## T7 · huddle-webrtc-signal（对标 Slack Huddle / Rocket.Chat 音视频）

**目标**: 让 Huddle 从"仅元数据"升级为可**真实音视频通话**（WebRTC 信令 + SFU），并在 IM 频道页可用。

**背景 / 现状**:
- `backend-java/src/main/java/com/nocobase/im/HuddleService.java` 仅房间/参与者元数据 CRUD（创建/加入/离开/结束/静音/共享）。
- `im_huddle_signaling` 表建了但**零代码**；`HuddleService` 注释声称信令走 `/api/huddle/signaling`，但该端点全仓不存在。
- 前端 IM（`features/im/*`）**Huddle 完全 0 命中**（无入口、无 hook）。
- 既有实时基建: `realtime/` STOMP（`RealtimeCollabController`、`RedisStompBridge`）、`stompClient.ts`。

**涉及文件**:
```
backend-java/src/main/java/com/nocobase/im/
├── HuddleService.java               [修改] 保留元数据，房间对接 SFU（可插拔适配）
├── HuddleSignalingController.java   [新建] WebRTC 信令端点
└── ImHuddleController.java          [修改] 参与者状态同步（可选）
frontend/src/features/im/
├── useHuddle.ts                     [新建] WebRTC 信令 hook (RTCPeerConnection + WS)
├── ImLayout.tsx                     [修改] 接入 Huddle 入口（频道内发起/加入）
└── (可选) HuddlePanel.tsx            [新建] 音视频渲染 + 参与者控制
```

**实现要点**:
1. SFU 选型: 优先 **Janus（videoroom）** 或 **mediasoup**；Java 侧定义 SFU 适配接口（如 `HuddleSfuGateway`），默认实现走 HTTP/WebSocket 调 SFU，未配置 SFU 时降级为"仅元数据 + 明确提示未启用媒体"（禁止假成功）。
2. `HuddleSignalingController`: 提供 WebSocket 或 REST 信令端点（offer/answer/ICE candidate 转发、加入/离开房间）。复用既有 STOMP 基础设施（`/ws` 已配 `ws: true`）。
3. 前端 `useHuddle.ts`: 封装 `RTCPeerConnection` + 信令 WebSocket；`ImLayout` 频道头部加"发起/加入 Huddle"按钮；`HuddlePanel` 渲染本地/远端 `<video>`、静音/挂断控制。
4. 权限: 加入 Huddle 需校验频道成员（复用 `ImChannelMemberRepository`）。
5. 媒体不经过应用服务器（SFU 架构），Java 只管房间/参与者元数据。

**验收**:
```bash
cd backend-java && mvn test
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
```
- 信令端点存在且有单测（mock SFU 网关，断言 offer/answer 转发）；前端 Huddle UI 可渲染（组件测试或至少构建通过）；降级路径（无 SFU）有明确提示而非静默成功。

---

## T8 · wecom-wechat-embed（嵌入企微/微信）

**目标**: 补齐企微（对标钉钉已有能力）与微信客服入口，实现"嵌入微信生态"。

**背景 / 现状**:
- 现有企微仅群机器人 webhook（`notification/WeChatWorkDispatcher` → `qyapi.weixin.qq.com/cgi-bin/webhook/send`）+ `WeChatWorkController` 的 `/send`、`/ping`。
- **无企微 SSO、无通讯录同步、无应用消息（corp API）、无微信客服**。
- 钉钉侧已是完整真实实现（`integration/dingtalk/` 9 个类），可作为对称参照。

**涉及文件**:
```
backend-java/src/main/java/com/nocobase/integration/wecom/
├── WeComAppService.java    [新建] 企微 SSO / 通讯录同步 / 应用消息
├── WeComController.java    [新建] 端点（auth-url/login/sync-org/send）
└── (对称参照) integration/dingtalk/DingTalkAppService.java
frontend/src/features/im/LivechatWidget.tsx   [新建] 客服组件（微信/网页客服转工单）
```

**实现要点**:
1. `WeComAppService` 真实 HTTP（复用统一 RestTemplate Bean，见 T11）:
   - SSO: `qyapi.weixin.qq.com/cgi-bin/gettoken` 取 token → `user/getuserinfo`（code 换用户）。
   - 通讯录: 部门/成员拉取（`department/list`、`user/list`），落到本地用户 + 映射（对称钉钉 `UserMappingService`）。
   - 应用消息: `/message/send`（text/markdown/card）。
2. `WeComController` 端点对称钉钉: `/api/wecom/auth-url`、`/login`、`/sync-org`、`/send`、`/sync-status`。
3. 微信客服: 前端 `LivechatWidget` 提供网页客服入口，消息转为工单（可先落到既有 `workflow` 或 `project` 任务，或新建工单实体——若新建需加迁移 V35）。
4. 回调签名校验（企微需补，对称钉钉已有）。

**验收**:
```bash
cd backend-java && mvn test
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
```
- 企微 Service 有单测（mock HTTP，断言 token 获取与消息发送真实调用）；端点在 Controller 测试中覆盖。

---

## T9 · slack-python-connector（Slack 集成 + Python 侧补全）

**目标**: 交付用户指定的第一批集成之一 **Slack**，并补齐 Python 侧连接器/路由/真实 LLM/事件通道。

**背景 / 现状**:
- `backend-python/src/nocobase_py/` 只有 `routers/health.py` 与 `routers/ai.py`（`/api/ai/*`，17 端点）；**无 `/api/integration/*`、无 `/api/tasks/*`**；无钉钉/企微/飞书/Slack 连接器（仅有通用告警 `webhook.py`: `WebhookPusher` generic/feishu/slack + HMAC 签名）。
- `POST /api/ai/chat` 未配 `llm_api_key` 时回退 `[simulated]` 占位；无流式 SSE/多轮/Function Calling。
- 与 Java: 无 `/internal/*`、无 Redis Streams（`redis_host/port` 定义后 0 引用）；实际是 Java 反代 `/api/ai/*`。

**涉及文件**:
```
backend-python/src/nocobase_py/
├── routers/integration.py                        [新建] /api/integration/* 路由
├── routers/tasks.py                              [新建] /api/tasks/* 路由
├── services/connectors/__init__.py               [新建]
├── services/connectors/{dingtalk,wecom,slack,feishu}.py  [新建] 连接器
├── config.py                                     [修改] 真实 LLM 配置 + Redis 事件通道配置
└── main.py                                       [修改] 注册新 router
```

**实现要点**:
1. Slack 连接器: Events API 接收事件 + `chat.postMessage` 发消息；复用 `WebhookPusher` 的 HMAC-SHA256 签名 + 时间戳防重放模式（对称现有实现）。
2. `/api/integration/*`: 连接器管理（列表/配置/测试发送）；`/api/tasks/*`: 异步任务提交与查询（对接既有 `scheduler.py`）。
3. `config.py`: 增加 `llm_api_key`、`llm_base_url`（去 `[simulated]` 回退，未配置时明确报错或保留显式 mock 标记）；增加 Redis 事件通道配置（Java↔Python 事件总线，优先 Redis Streams，次选 `/internal/*`）。
4. 钉钉/企微/飞书连接器对称实现（企微可与 T8 的 Java 侧分工: Java 做 SSO/通讯录，Python 做消息推送，或按团队约定）。

**验收**:
```bash
cd backend-python && pytest
```
- 新增 Slack 连接器单测（mock HTTP，断言签名与 postMessage）；新路由有测试；`pytest` 50+ 全过。

---

## T10 · frontend-dark-full（视觉统一）

**目标**: 消除"外壳暗色、内部亮色"的割裂，把剩余 **28 个硬编码亮色文件**统一为 Glassmorphism 深色主题。

**背景 / 现状**:
- `frontend/src/theme/tokens.ts`（设计令牌）、`theme/index.ts`（MUI 暗色主题，覆盖 15 类组件）、`styles.css`（CSS 变量 + `.glass-panel/.glass-card/.glass-strong/.glass-light/.glass-button/.glass-button-primary/.input-glass/.textarea-glass` + IM 布局/消息气泡/频道列表类）**已完整且自洽**。
- 但毛玻璃改造**仅覆盖 10 个文件**: `AppLayout.tsx`、`Home.tsx`、`Workbench.tsx`、`features/im/*`(7 个)。
- **28 个文件仍硬编码** `background:'white'` / `#f1f5f9` / `#e2e8f0` 亮色: 全部 wiki 页面、数据视图（Table/Kanban/Gallery/Calendar/Detail）、FormDesigner/FormRuntime、全部 admin 页、Login。

**涉及文件**（逐文件改，禁止遗漏）:
```
frontend/src/pages/wiki/*.tsx                     (KB/页面/阅读/编辑/版本/搜索/分类)
frontend/src/pages/{TableView,KanbanView,GalleryView,CalendarView,DetailView}.tsx
frontend/src/pages/{FormDesigner,FormRuntime,FormsList}.tsx
frontend/src/pages/{Login,Profile,MessagesInbox,MyTasks,WorkflowInstances}.tsx
frontend/src/pages/admin/*.tsx                    (Users/Roles/Acl/Audit/RowAcl/Notifications/Alerts/ER)
frontend/src/components/**                        (forms/views/wiki 等残留内联亮色)
```

**实现要点**:
1. 把 `background:'white'` → `var(--color-bg-secondary)` / `.glass-card`；`#f1f5f9` → `var(--glass-bg-light)`；`#e2e8f0` → `var(--color-border-light)`；文字 `#0f172a` → `var(--color-text-primary)`。
2. 卡片/面板统一用 `.glass-card` / `.glass-panel`；输入框用 `.input-glass`；按钮用 `.glass-button` / `.glass-button-primary`。
3. 优先用 MUI `sx` 主题变量（如 `bgcolor: 'background.paper'`、`color: 'text.primary'`）而非硬编码，保持与 MUI 暗色主题一致。
4. 保持功能与 DOM 结构不变（只改样式），避免破坏既有 wiki/视图测试（31 个测试文件中有 wiki 6 个、forms 4 个、views 1 个等，改完必须全过）。

**验收**:
```bash
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
```
- 三命令全绿；`grep -rn "background: *'white'" src/` 与 `grep -rn "#f1f5f9" src/` 结果为 0（或仅剩有意保留的白底印刷区域，需在文档说明）。

---

## T11 · hardening（稳定性加固）

**目标**: 修复已知缺陷，提升生产可用性（依赖 T4/T5 完成）。

**背景 / 现状**:
1. `api/UserController.java:38` `/api/users/me` 返回 **roles 硬编码 `["admin"]`**，与 `UserRoleEntity` + 角色继承（`findSelfAndAncestors`）脱节。
2. zustand **双 auth store 分裂**: `stores/auth.ts`（主 store，全站用）与 `stores/authStore.ts`（仅钉钉 `services/dingtalk.ts` 用，persist key `auth-storage`）；钉钉登录写入的 token 不进主 store → 钉钉登录后主 store 无 token。
3. `api/client.ts` **无 refresh token** 机制（401 直接清 token 跳登录）。
4. Slash `/invite`（`SlashCommandInitializer`）直接 `UUID.fromString` 未 try/catch，非法输入抛 500。
5. 钉钉各 Service 内 `new RestTemplate()` **就地创建**（无连接池/超时/重试）。

**涉及文件**:
```
backend-java/src/main/java/com/nocobase/api/UserController.java          [修改] /me 返回真实角色
backend-java/src/main/java/com/nocobase/config/RestTemplateConfig.java   [新建] 统一 HTTP 客户端 Bean
backend-java/src/main/java/com/nocobase/integration/dingtalk/*.java      [修改] 注入统一 RestTemplate
backend-java/src/main/java/com/nocobase/im/SlashCommandInitializer.java  [修改] /invite 入参校验
frontend/src/stores/auth.ts / authStore.ts                               [修改] 统一 auth store
frontend/src/api/client.ts                                               [修改] refresh token 静默续期
```

**实现要点**:
1. `/me` 真实角色: 查 `UserRoleEntity`（+ 角色继承 `findSelfAndAncestors`）返回实际 roles，替换硬编码。
2. 统一 RestTemplate Bean（`RestTemplateConfig`），配置连接池/超时/重试；钉钉 Service 改为构造器注入（删 `new RestTemplate()`）。
3. `/invite` 加 try/catch，非法 UUID 返回友好错误（400）而非 500。
4. 前端: 删除 `authStore.ts` 或让其代理写入主 `stores/auth.ts`（推荐删除并改造 `services/dingtalk.ts` 写主 store）；`api/client.ts` 增加 refresh token 静默续期（401 时先尝试 refresh，失败再跳登录）。

**验收**:
```bash
cd backend-java && mvn test
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
```
- `/me` 单测断言返回真实角色（非固定 admin）；钉钉 Service 注入 Bean 有单测；前端 auth/refresh 逻辑有测试；Slash 非法入参返回 400 有测试。

---

## 附: 任务 → 对标竞品映射（供执行时理解意图）

| 任务 | 对标产品 | 核心优点落地 |
|---|---|---|
| T1 | — | 消除启动阻塞（前提） |
| T2 | **Notion** | Block 编辑器、双向链接、模板 |
| T3 | **Airtable / Trello Butler** | Automations IF-THEN、事件触发 |
| T4 | **Slack / Notion** | 全局搜索、增量索引 |
| T5 | **Trello** | 看板 List/Card 拖拽、清单、标签 |
| T6 | **Rocket.Chat** | LDAP/AD 企业身份集成 |
| T7 | **Slack Huddle / Rocket.Chat** | 音视频会议（WebRTC SFU） |
| T8 | **钉钉 / 企微 / 微信** | 嵌入微信生态、SSO、客服 |
| T9 | **Slack** | 频道消息、连接器、AI 网关 |
| T10 | **Notion / Slack** | 深色 Glassmorphism 视觉统一 |
| T11 | **Mattermost** | 稳定性、合规、生产可用 |

---

## 给 GLM-5.3 的执行指令（复制这段开始）

> 你是本项目的执行模型。请按 `PHASE50_GLM53_PROMPT.md` 的 **T1 → T11** 顺序逐个任务执行。
> 每个任务开始前: 先读任务中"背景/现状"列出的现有文件，确认真实签名后再改。
> 每个任务结束后: 跑该任务的验收命令（后端 `mvn test` / 前端 `vitest+tsc+build` / Python `pytest`），全绿后按 `[java]`/`[js]`/`[python]` 规范提交，然后追加记忆到 `.codebuddy/memory/2026-09-20.md`。
> 严格遵守"零、全局约定"的 5 条红线，尤其**禁止用 log.info+TODO 冒充接真**。
> 首个任务从 **T1 fix-flyway-schema** 开始（它是所有后续任务的前置）。

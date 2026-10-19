# Phase 51 企业综合平台整合与断链修复 — GLM-5.3 执行提示词

> 生成日期: 2026-09-20
> 编排者: CodeBuddy (HY4) · 执行者: GLM-5.3
> 依据: 2026-09-20 全量只读代码审计（24 项断链）
> 关联: PHASE50_GLM53_PROMPT.md, ARCHITECTURE.md, MEMORY.md
> 门禁基线: 后端 `mvn test` 1057 PASS · 前端 `vitest` 233 PASS + `tsc` 0 errors + `vite build` · Python `pytest` 50

---

## ⚠️ 首要警告：当前代码是坏的

上一轮 Phase50 交付了 11 个任务但**未做编译与启动验证**。审计确认当前代码：
- `mvn compile` **必失败**（10 处符号/签名错误）
- Spring **启动必失败**（`LdapTemplate` 无 Bean + 4 张看板表缺迁移 → `ddl-auto: validate` 报 missing table）
- 即便编译通过，**12 项功能静默失效**（看板端点 NPE、搜索恒空、Python 连接器 bug）

**本轮第一要务是"止血"**：T1、T2 不做完，项目完全不可用。不要跳去做 P2 新功能。

---

## 零、全局约定（每个任务都必须遵守）

### 0.1 项目结构
```
/home/who/multistack-project/
├── backend-java/          Spring Boot 3.3.5 / JDK 21 / Maven
├── backend-python/        FastAPI 0.115 / Py3.12
└── frontend/              React 18 + TS + Vite 5 + MUI v9 + @emotion
```

### 0.2 质量门禁（每任务完成后必跑，缺一不可）
```bash
# 后端（基线 1057，只允许增加）
cd backend-java && mvn test
# 前端
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
# Python（基线 50）
cd backend-python && pytest
```
> **T1 完成后必须先跑 `mvn compile`**，确认 10 项编译阻断清零，再进入 T2。

### 0.3 提交规范（分栈提交，每任务一次）
`[java]` / `[python]` / `[js]` / `[docs]`

### 0.4 红线（违反即返工）
1. **禁止 `log.info` + `// TODO` 冒充接真**——本轮大量修复正是要消除这类占位。
2. **禁止臆造 API**——所有调用的符号必须先在代码中确认存在（见 §附录真实契约）。
3. **禁止硬编码亮色**——前端一律用 `theme/tokens.ts` + `.glass-*` 工具类 / MUI `sx` 主题变量。
4. **迁移版本号唯一**——`V34` 已被 T2 占用（看板 4 表），后续新表从 `V35` 起。
5. **不擅自引重型依赖**——确需引入先评估。

### 0.5 完成后更新记忆
追加到 `/home/who/multistack-project/.codebuddy/memory/2026-09-20.md`（用 `replace_in_file` 追加，勿覆盖）。

### 0.6 执行顺序（严格按依赖）
```
T1 编译阻断修复 (P0-止血)
  ↓
T2 启动阻断修复 (P0-止血)  ← 完成后 mvn compile 通过 + Spring 可启动
  ↓
T3 后端运行时接真 (P1)  ┐
T4 前端/Python 接真 (P1) ┴ 可并行
  ↓
T5 Notion 前端 / T6 Trello 前端 / T7 Huddle SFU / T8 企微+Livechat (P2-深化)
  ↓
T9 全栈门禁 + 提交
```

---

## T1 · fix-compile-blockers（编译阻断修复，最高优先级）

**目标**: 让 `mvn compile` 通过。当前必失败，共 10 处。

**验收（硬指标）**:
```bash
cd backend-java && mvn compile -q    # 必须 BUILD SUCCESS，0 errors
```

### 修复清单（逐项，精确到行）

| # | 文件 | 行 | 问题 | 修复 |
|---|---|---|---|---|
| 1 | `wiki/WikiBlockService.java` | 34,46 | 缺 `import java.util.Map`（文件 import 仅 Instant/ArrayList/List/UUID） | 补 `import java.util.Map;` |
| 2 | `wiki/WikiBlockRepository.java` | — | 无 `deleteByPageId`（Service:42,101 调用） | 补 `@Modifying @Query("delete from WikiBlockEntity b where b.pageId = :pageId") void deleteByPageId(@Param("pageId") UUID pageId);`（需 import `Modifying`/`Query`/`Param`） |
| 3 | `automation/AutomationTriggerListener.java` | 40 | 缺 `TransactionPhase` import | 补 `import org.springframework.transaction.event.TransactionPhase;` |
| 4 | `automation/AutomationRuleService.java` | 323,337,351 | `TenantContext.getTenantId()` 不存在 | 改 `TenantContext.currentTenantId()` |
| 5 | `automation/AutomationRuleService.java` | 363 | `webhookDispatcher.send(url, data)` 2参不匹配（接口为 3 参 `send(channel, recipient, payload)`） | **给 `WebhookDispatcher` 新增重载** `public SendResult send(String url, Map<String,Object> payload)`（内部构造临时 `NotificationChannelEntity`，config 放 `{"url": url}`），保持 Service 的 2 参调用不变 |
| 6 | `notification/WeComDispatcher.java` | 76 | `SendResult.success(...)` 不存在（record 仅 `ok`/`error`） | 改 `SendResult.ok(resp.body())` |
| 7 | `integration/wecom/WeComAppService.java` | 113 | `userRepository.findByUnionId(unionId)` 不存在 + `UserEntity` 无 `unionId` 字段 | 改走 `userRepository.findByUsername(username)`（username 用 `wecom_` + 摘要，与钉钉 `dt_` 前缀模式对称） |
| 8 | `integration/wecom/WeComAppService.java` | 129 | `user.setUpdatedAt(...)` 不存在（`UserEntity` 无 `updatedAt`） | **删除该行** |
| 9 | `ldap/LdapSyncService.java` | 112-127 | `ldapTemplate.search(baseDn, filter, ctx -> {...})`：① `ContextMapper#mapFromContext` 参数是 `Object`，lambda 内 `getNameInNamespace()`/`getAttribute()` 不存在；② `search(String,String,ContextMapper)` 与 `(String,String,AttributesMapper)` 对隐式 lambda 歧义 | 显式转型：`ldapTemplate.search(baseDn, filter, (ContextMapper<Map<String,Object>>) ctx -> { DirContextOperations d = (DirContextOperations) ctx; ... d.getNameInNamespace(); d.getStringAttribute("uid"); ... });`（import `org.springframework.ldap.core.ContextMapper`、`org.springframework.ldap.core.DirContextOperations`） |
| 10 | `config/RestTemplateConfig.java` | 30,42 | HC5 API 类型错：`setValidateAfterInactivity` 需 `TimeValue`（当前传 `java.time.Duration`）；`evictIdleConnections` 为 `TimeValue` 版（非 `long,TimeUnit`） | 改 `TimeValue.ofSeconds(10)` / `TimeValue.ofSeconds(60)`，import `org.apache.hc.core5.util.TimeValue` |

**注意**: 第 5 项若选择"构造 `NotificationChannelEntity`"而非加重载，需确保 channel 的 `config` 含 `url` 且 `WebhookDispatcher.send` 能从 `cfg.get("url")` 取到（既有所实现正是这么做的）。

---

## T2 · fix-startup-blockers（启动阻断修复）

**目标**: 让 Spring 能启动（`ddl-auto: validate` 不再报缺表/缺 Bean）。

**前置**: T1 已完成且 `mvn compile` 通过。

### 2.1 `LdapTemplate` Bean 缺失 → 应用起不来
- `ldap/LdapSyncService.java:38,45` 构造注入 `org.springframework.ldap.core.LdapTemplate`
- 全仓**无** `@Bean LdapTemplate / LdapContextSource`；`application.yml` 无 `spring.ldap.*`
- 依赖 `spring-boot-starter-data-ldap` 已在 `pom.xml:165-168`

**修复（二选一，推荐后者以保证无 LDAP 环境也能启动）**:
- 方案 A: `application.yml` 加 `spring.ldap.urls / base / username / password`（需真实 LDAP 服务器）
- **方案 B（推荐）**: 新建 `config/LdapTemplateConfig.java`，提供 `@Bean @ConditionalOnMissingBean public LdapTemplate ldapTemplate()` 占位实现（无真实连接时 `syncUsers` 明确返回错误而非伪造用户），**禁止静默成功**

### 2.2 4 张看板表缺迁移 → validate 失败
`BoardListEntity(board_lists)`、`CardChecklistEntity(card_checklists)`、`CardChecklistItemEntity(card_checklist_items)`、`CardLabelEntity(card_labels)` 在全部 33 个 Vxx 中**零 CREATE TABLE**。

**修复**: 新建 `db/migration/V34__project_board.sql`，建 4 表，字段与 4 个实体**逐一对齐**：
- `board_lists`: id / tenant_id / project_id / title / type / sort_order / wip_limit / created_at / updated_at
- `card_checklists`: id / tenant_id / task_id / title / sort_order / created_at
- `card_checklist_items`: id / tenant_id / checklist_id / task_id / title / done / sort_order / created_at
- `card_labels`: id / tenant_id / project_id / name / color（非空默认 `BLUE`）/ created_at

**验收**:
```bash
cd backend-java && mvn compile        # 通过
# 用 dev profile 连真实 PG 启动 Flyway/validate，确认无 missing table / NoSuchBeanDefinition
```

---

## T3 · fix-runtime-backend（后端运行时接真）

**目标**: 消除"编译能过但功能是空的"静默失效。

### 3.1 `project/ProjectBoardController.java` 4 端点 NPE（必 500）
`:56, :139, :199, :257` —— 4 个创建端点 `new` 实体后**未 setId、未 save**，对应 Repository **根本未注入**，却调用 `list.getId().toString()` 等。
**修复**: 构造器注入 `BoardListRepository` / `CardChecklistRepository` / `CardChecklistItemRepository` / `CardLabelRepository`；每个创建端点 `setId(UUID.randomUUID())` + `setTenantId(...)` + `setCreatedAt(...)` + `repository.save(...)` 后返回真实 id。

### 3.2 `search/UnifiedSearchService.java` 搜索恒空
- `:231` `collectionService.listRecords(tenantId, keyword, limit)` **参数错位**（真实签名 `listRecords(String collectionName, String tenantId, int limit)`）→ keyword 被当 collectionName，必然 403 被 catch 吞掉 → "record" 恒空
- `:98` `listByKbAndStatus(null, "PUBLISHED", tenantId)` 传 kbId=`null` → 生成 `knowledge_base_id = null` 条件 → "wiki" 恒空
**修复**: 修正 `listRecords` 参数顺序（遍历该租户的 collection 或按传入 collectionName）；wiki 分支改为传真实 kbId（遍历知识库）或改用 `searchByContent` 走 FTS。

### 3.3 `content_tsv` tsvector 类型冲突（写入必炸）
- `V28__platform_enhancement.sql:133` `content_tsv` 是 **tsvector**，`:146-156` 有 BEFORE INSERT/UPDATE 触发器自动写 `to_tsvector(...)`
- 但 `search/UnifiedSearchIndexEntity.java:37-38` 把它映射成**可写 String**，且 `UnifiedSearchService.java:189` 主动 `setContentTsv(content.toLowerCase())`
- 结果：`indexEntity()` 的 INSERT 报 `column "content_tsv" is of type tsvector but expression is of type character varying`
**修复**:
  1. `UnifiedSearchIndexEntity` 的 `contentTsv` 改为 `@Column(name="content_tsv", insertable=false, updatable=false)`
  2. 删除 `UnifiedSearchService:189` 的 `setContentTsv(...)` 赋值（交给触发器）
  3. `UnifiedSearchIndexRepository:40,53` 的 `contentTsv ILIKE %:query%` 对 tsvector 无效 → 改对 `content`/`title` 做 `ILIKE`，或改 nativeQuery 用 `@@ plainto_tsquery`

### 3.4 `ldap/LdapSyncService.java` 密码非空约束
`:207-214` 创建 `UserEntity` **未设 `password_hash`**（列 `nullable=false`）→ 违反非空约束，异常被 catch 吞成 mapping ERROR。
**修复**: 设随机密码 `passwordEncoder.encode(randomPassword())`（与 `DingTalkAppService` 的 SSO 建号模式一致，该账号不走密码登录）。

### 3.5 删除 `im/HuddleSignalingController.java`
该文件**未被删除**，与新的原生 `/ws/huddle` handler 并存；其 `@SendTo("/topic/huddle/{roomId}")` 无 `@DestinationVariable` 可解析 `{roomId}` → 被调用时占位符解析异常。
**修复**: 删除该文件（T7 会用原生 WS + 可插拔 SFU 重建）。

**验收**: `cd backend-java && mvn test`（基线 1057+，新增断链修复的单测）。

---

## T4 · fix-runtime-frontend-python（前端 + Python 接真）

### 4.1 `frontend/src/features/im/useHuddle.ts`
- `:29` `localStorage.getItem('access_token')` 与 store 实际 key **`nocobase_access_token`** 不一致 → token 恒 `null`，WS URL 变 `?token=null`
- `:57-59, :67-68` TS strict 下 `msg` 在 `setPeers(prev => {...})` 闭包内丢失 `if(msg.peerId)` 收窄 → `next.set/delete` 传 `string|undefined` 报类型错
**修复**: token key 改 `nocobase_access_token`；回调外先 `const peerId = msg.peerId;` 再在闭包内用 `peerId`。

### 4.2 `backend-python/.../services/connectors/wecom.py`
`:21` `self.corp_secret = corp_id` —— **复制粘贴错误**（应为 `corp_secret`）→ `is_configured` 误判通过、换 token 用错 secret。
**修复**: `self.corp_secret = corp_secret`。

### 4.3 `dingtalk.py` / `feishu.py` 相对 URL
`dingtalk.py:35,59`（`"/gettoken"`、`"/message/send?..."`）、`feishu.py:34`（`"/auth/v3/tenant_access_token/internal"`）**未拼 `self.base_url`** → `httpx.UnsupportedProtocol`。
（对比 `wecom.py:35,65` 与 `feishu.py:60` 都正确拼了 `base_url`）
**修复**: 全部拼上 `self.base_url`。

### 4.4 `routers/integration.py` slack_verify 参数
`:107-111` `slack_verify(timestamp, signature, body)` 被 FastAPI 当 **query 参数**接收；签名验证场景应读**原始请求体**。
**修复**: 改用 `Body()` / `Request` 读原始 body（`await request.body()`），再交给 `SlackConnector.verify_signature(timestamp, signature, raw_body)`。

### 4.5 `config/HuddleWebSocketConfig.java` 挂鉴权
`:21-24` 注入 `JwtService` 但**未使用**、未挂 handshake interceptor；`/ws/huddle` 已在 `SecurityConfig:66` `permitAll` → 信令端点**无鉴权**。
**修复**: 挂上 `StompHandshakeInterceptor(jwtService)`（复用 JWT 校验，token 走 `?token=` 查询参数）。

**验收**:
```bash
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
cd backend-python && pytest
```

---

## T5 · notion-block-frontend（Notion 前端 Block 对接）

**目标**: 把后端 T2 已建的 Block API 接到前端，补齐反向链接面板 + 模板选择。

**背景**: 后端 `WikiBlockService`/`WikiBacklinkEntity`/`WikiTemplateEntity` + V31-V33 已就绪；前端 `components/wiki/NotionStyleEditor.tsx` 仍是本地状态（md↔blocks 转换），未调后端。

**涉及文件**:
- `frontend/src/components/wiki/NotionStyleEditor.tsx` [修改] 对接 Block API
- `frontend/src/pages/wiki/*.tsx` [修改] 反向链接面板 + 模板选择 UI

**实现要点**:
1. 编辑器改为从 `GET /api/wiki/pages/{pageId}/blocks` 加载、保存时调 `POST /api/wiki/pages/{pageId}/blocks`（整树替换）或 block 级 `PUT/DELETE/move`。
2. **反向链接面板**: 调 `GET /api/wiki/pages/{pageId}/backlinks`（后端 `WikiBacklinkRepository.findByTargetPageId`），以半透明侧栏展示"引用了本页的页面"。
3. **模板选择**: 调 `GET /api/wiki/templates`（按 kbId）+ `POST /api/wiki/templates/{id}/apply` 应用模板。
4. **样式**: 编辑区用 `.glass-card` 容器，工具条用 `.glass-button`，反向链接侧栏半透明，hover 用 `.glass-card-hover`（背景提亮 + `translateY(-2px)` + 阴影加深），过渡用 `tokens.transitions`。

**验收**: 前端三命令全绿；Block 能保存并回显、反向链接面板能列出引用页、模板能应用。

---

## T6 · trello-board-frontend（看板拖拽 + 接真 API）

**目标**: TaskBoard 用 dnd-kit 实现拖拽，ProjectPage 接线真实看板 API（替换手填 projectId）。

**背景**: 后端 `ProjectBoardController` 已在 T3 修好（列 CRUD / 卡片移动 `CardMoveService` / 清单 / 标签 / 成员）；前端 `features/project/ProjectPage.tsx` 需手填 `projectId`，`TaskBoard.tsx` 仅有下拉改状态、无拖拽。

**涉及文件**:
- `frontend/src/features/project/TaskBoard.tsx` [修改] `@dnd-kit` 拖拽
- `frontend/src/features/project/ProjectPage.tsx` [修改] 接线真实 API

**实现要点**:
1. `TaskBoard` 用 `@dnd-kit/core` + `@dnd-kit/sortable`：列（`BoardListEntity.type`: TODO/IN_PROGRESS/BLOCKED/DONE）与卡片均可拖拽；拖拽结束调 `POST /api/project-boards/cards/move`（`{taskId, toListId, toIndex}`）。
2. `ProjectPage` 从路由/项目列表取 projectId（**不再手填**），看板列与卡片调 `/api/project-boards/*` 真实端点。
3. **样式**: 列容器与卡片用 `.glass-card`；拖拽中卡片加 `box-shadow: var(--shadow-lg)` + 轻微缩放；列头计数用 `text-muted`；过渡用 `spring` 缓动。

**验收**: 前端三命令全绿；拖拽后能持久化（调 `/move` 成功）。

---

## T7 · huddle-sfu-media（Huddle SFU + 音视频面板）

**目标**: 原生 `/ws/huddle` 信令（T4 已挂鉴权）基础上，对接可插拔 SFU，新增音视频渲染面板。

**涉及文件**:
- `backend-java/src/main/java/com/nocobase/im/HuddleSfuGateway.java` [新建] 可插拔 SFU 适配接口
- `frontend/src/features/im/HuddlePanel.tsx` [新建] 音视频渲染 + 静音/挂断
- `frontend/src/features/im/ImLayout.tsx` [修改] 接入 HuddlePanel

**实现要点**:
1. **SFU 可插拔**: 定义接口（如 `createRoom(huddleId)` / `join(roomId, userId)` / `destroy(roomId)`），默认实现走 **Janus videoroom** 或 **mediasoup**；**未配置 SFU 时降级为"仅元数据 + 前端明确提示未启用媒体"，禁止假成功**。
2. **媒体不经应用服务器**（SFU 架构），Java 只管房间/参与者元数据 + 信令中继。
3. `HuddlePanel`: 右下角**悬浮毛玻璃面板**，本地/远端 `<video>` 圆角裁剪，静音/挂断按钮用 `.glass-button-primary` / `.glass-button`。
4. 复用 `useHuddle`（T4 已修好 token key 与 TS 收窄）。

**验收**: 前端三命令 + 后端 `mvn test` 全绿；未配 SFU 时前端有明确提示（不静默失败）。

---

## T8 · integration-embed-real（企微接真 + Livechat 客服）

**目标**: 企微从 demo 占位换为真实 API；新增微信客服 Livechat 组件转工单；验证 Slack 连接器。

**背景**: `WeComAppService.loginFromWeCom` 目前用**演示数据**（`unionId = "wecom_unionid_demo_" + code.hashCode()`）；`getAccessToken` 返回 `"FAKE_ACCESS_TOKEN_FOR_DEMO"`。

**涉及文件**:
- `integration/wecom/WeComAppService.java` [修改] 接真实 API
- `integration/wecom/WeComController.java` [修改]
- `frontend/src/features/im/LivechatWidget.tsx` [新建] 客服组件

**实现要点**:
1. **企微接真**（注入池化 `RestTemplate`，T1 已配 Bean）:
   - `gettoken`: `GET qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=XXX&corpsecret=YYY`
   - `getuserinfo`: `GET .../user/getuserinfo?access_token=...&code=...` 取 userid
   - 用户详情: `GET .../user/get?access_token=...&userid=...`（name/email/avatar/mobile）
   - 应用消息: `POST .../message/send`（touser/msgtype/agentid）
   - 按 userid 稳定映射本地账号（`wecom_` 前缀 + 摘要，与钉钉 `dt_` 对称）；未配置凭证时返回**明确错误**而非伪造用户
2. **LivechatWidget**: 右下角悬浮入口气泡，展开为**毛玻璃会话窗**，消息气泡沿用 IM 既有 `im-message` 样式类；支持消息转工单（落到 `workflow` 或 `project` 任务）。
3. 验证 Slack 连接器（T4 已修 bug）能 `chat.postMessage` 与验签。

**验收**: 后端 `mvn test` + 前端三命令 + Python `pytest` 全绿。

---

## T9 · gate-verify-commit（全栈门禁 + 提交）

**目标**: 跑通三栈门禁，分栈提交，更新记忆。

```bash
cd backend-java && mvn test                                   # ≥1057 PASS
cd frontend && npx vitest run && npx tsc --noEmit && npx vite build
cd backend-python && pytest                                   # ≥50 PASS
```

**提交**: 按 `[java]` / `[python]` / `[js]` 分栈提交。
**记忆**: 追加到 `.codebuddy/memory/2026-09-20.md`，记录本轮修复的 24 项断链与验证结果。

---

## 附录：真实契约（禁止臆造，务必按此调用）

```java
// notification/NotificationDispatcher.java —— SendResult 仅有 ok/error（无 success！）
public interface NotificationDispatcher {
    NotificationChannelEntity.Type supportedType();
    SendResult send(NotificationChannelEntity channel, String recipient,
                    Map<String, Object> payload);
    record SendResult(boolean ok, String detail) {
        static SendResult ok(String detail) { return new SendResult(true, detail); }
        static SendResult error(String detail) { return new SendResult(false, detail); }
    }
}

// tenant/TenantContext.java —— 无 getTenantId()
public static String currentTenantId();
public static String requireTenantId();

// meta/CollectionService.java —— listRecords 真实参数顺序
public List<Map<String, Object>> listRecords(String collectionName, String tenantId, int limit);

// auth/UserEntity.java —— 字段仅以下 8 个（无 updatedAt、无 unionId）
id / username / passwordHash / tenantId / displayName / email / enabled / createdAt

// auth/UserRepository.java —— 仅 findByUsername（无 findByUnionId）
Optional<UserEntity> findByUsername(String username);
```

---

## 给 GLM-5.3 的执行指令（复制这段开始）

> 你是本项目的执行模型。请按 `PHASE51_GLM53_PROMPT.md` 的 **T1 → T9** 顺序执行。
>
> **T1、T2 是止血任务，必须先完成**——当前 `mvn compile` 必失败、Spring 必启动失败，跳过它们去写新功能毫无意义。
>
> 每个任务开始前：先读任务"背景"列出的文件，用搜索确认符号真实存在（参考附录契约），再改。
> 每个任务结束后：跑该任务的验收命令（T1 跑 `mvn compile`；其余按栈跑 `mvn test` / `vitest+tsc+build` / `pytest`），全绿后按 `[java]`/`[python]`/`[js]` 提交，再追加记忆。
>
> 严格遵守四条红线：禁止 log.info+TODO 冒充接真、禁止臆造 API、前端禁硬编码亮色、迁移版本 V34 已被 T2 占用（后续从 V35 起）。
>
> 首个任务从 **T1 fix-compile-blockers** 开始。

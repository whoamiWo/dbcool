# Phase 48 审计遗留修复 — GLM5.3 执行提示词

> 日期：2026-09-19
> 来源：Phase 48 交付后代码审计发现的 4 项「形似接真」缺口
> 状态：已执行完成（后端 1052 PASS / 前端 38 文件全通过 / tsc 0 error / build 成功）

---

## 零、背景

Phase 48 已提交 3 个 commit（`9722407 [java]`、`7c8456f [js]`、`7b54a4e [docs]`）。
审计结论：硬性约束（不引新依赖 / 只增 1 张表 / 鉴权兜底 / 400 替代 500）**全部守住**，
但存在 4 项「形似接真」缺口——测试能过（只 verify 了"被调用"），功能实际不可用。

---

## 一、R1【P0】6 个 AI 工具接真（删除全部占位）

### 现状实锤（全部为占位假代码）
```
ai/tools/CreatePageTool.java:16       → Map.of("pageId","TODO_create_page",...)
ai/tools/QueryDatabaseTool.java:16    → Map.of("rows",0,...)（永远 0 行）
ai/tools/SendDingTool.java:16         → Map.of("sent",true,...)（硬编码 true，假装发送成功，最危险）
ai/tools/SummarizeChannelTool.java    → "TODO:summarize_channel(...)"
ai/tools/SearchWikiTool.java:16       → Map.of("results", params.get("query"))（原样回显 query）
ai/tools/CreateTaskTool.java:16       → Map.of("taskId", params.getOrDefault("name","task"))
```
`AgentService` 的 ReAct 循环已为真（`toolRegistry.get` + `tool.execute`），但执行的是假函数。

### 1.1 扩展工具接口以传递上下文（必须先做）
```java
public interface AgentTool {
    String name();
    String description();
    Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx);
}
public record AgentToolContext(String tenantId, UUID userId, UUID channelId) {}
```
同步改造：`AgentService.executeTool` 构造 ctx 并传入；6 个工具类实现新签名；`AgentServiceTest` 调整。

### 1.2 各工具接真目标（复用既有服务，禁止自行实现逻辑）
| 工具 | 注入服务 | 接真方式 |
|---|---|---|
| `SearchWikiTool` | `WikiSearchService` | `search(query, kbId, tenantId, page, size)` → `{total, items:[{id,title,slug}]}` |
| `CreatePageTool` | `WikiPageService` | `create(kbId, parentId, title, slug, content, createdBy, tenantId)`，缺 kbId 返 error |
| `CreateTaskTool` | `ProjectService` | `create(projectId, title, description, parentId, assigneeId, status, priority, startDate, endDate, createdBy, tenantId)` |
| `SummarizeChannelTool` | `MessageService` + `AiAssistantService` | `MessageService.list(channelId, cursor, limit)` → `askQuestion()` 生成摘要 |
| `SendDingTool` | `DingTalkMessageService` | `sendWorkNotice/sendGroupMessage` → **返回真实 boolean**，严禁硬编码 true |
| `QueryDatabaseTool` | `CollectionService` | `listRecords(collectionName, tenantId, limit, sortExpr, filters)`，只读，禁止裸 SQL |

### 1.3 验收
- `grep -rn "TODO" backend-java/src/main/java/com/nocobase/ai/tools/` → **0 命中**
- 新增 `AgentToolTest`：mock 服务断言真实返回值（SendDing 未配置时 `sent=false`）

---

## 二、R2【P0】PinList / SearchResults 接入（消除死代码）

**现状**：两组件已创建但全项目零 import、零使用 → 死代码。

**要求**：在 `ImLayout.tsx` 接入
- `PinList`：置顶区块（channelId 与 channel 取当前频道），置顶变更回调 `onChanged` 同步置顶标记
- `SearchResults`：搜索结果面板，`onMessageClick(messageId, channelId)` 跳转到消息所属频道
- 置顶走 `listPins / pinMessage / unpinMessage`；跨频道搜索走 `searchCrossChannel`

**验收**：`grep "PinList\|SearchResults" frontend/src` ≥ 3 处（定义 + 导入 + JSX 使用）

---

## 三、R3【P0】ChannelList 侧边栏分组折叠

**现状**：`ChannelList.tsx` 折叠/分组关键词 0 命中 → 功能未实现。

**要求**
- 按 `type` 分组（PUBLIC / PRIVATE），组标题「公开频道 / 私有频道」
  （刻意与卡片内「群聊/私聊」文案区分，避免 `getByText` 多命中）
- 每组可折叠展开，`aria-expanded` 标记
- **保持既有 props 契约不变**，避免破坏 `ChannelList.test.tsx`

**验收**：既有用例不回归；新增折叠交互用例

---

## 四、R4【P1】ImLayout 补全 props 接线

**现状**：`pinnedMessageIds`、`onBurnExpired`、`onAttachment` 仅自身定义，调用方未传入。

**要求**
- `pinnedMessageIds`：来自 `listPins` 的 `Set<string>`
- `onBurnExpired`：到期刷新消息列表转不可读态
- `onAttachment`：上传成功后以 FILE 消息回显

---

## 五、硬性约束（违反必返工）
1. **不引新依赖**：LLM 复用 `AiAssistantService`，禁止 Spring AI / OpenAI SDK
2. **不新建数据库表**
3. **复用优先**：工具必须注入既有 Service，禁止自行实现业务逻辑
4. **安全**：工具执行带 `ctx.tenantId()`；跨频道搜索带 tenantId + 成员过滤
5. **MUI v9**：Grid `size={{xs,md}}`；Typography 禁直传 fontWeight（走 sx）；每文件 <300 行；跨文件类型 export
6. **禁止假代码**：禁止占位 return、禁止硬编码成功、**禁止"实现但未被调用"**
7. **不在本轮**：企微/微信、ABAC、Teams、Calls、Plugin Marketplace

---

## 六、执行顺序
R1（接口改造 → 6 工具接真 → 测试）→ R2 → R4 → R3 → 全量验证
每完成一项立即跑对应测试，禁止攒到最后。

## 七、完成定义（DoD）
- `grep -rn "TODO" backend-java/src/main/java/com/nocobase/ai/tools/` = **0**
- `PinList` / `SearchResults` 被 `ImLayout` 真实引用
- `ChannelList` 具备分组折叠且测试不回归
- `ImLayout` 传入 `pinnedMessageIds` / `onBurnExpired` / `onAttachment`
- 后端 `mvn test` ≥1036 PASS · 前端 `npx vitest run` 全通过 · `npx tsc --noEmit` 0 error · `npx vite build` 成功
- 按 `[java]` / `[js]` 分栈提交

---

## 八、实际执行结果（2026-09-19）

| 项 | 结果 |
|---|---|
| 后端 `mvn test` | **1052 PASS** / 0 Failures / 0 Errors（原基线 1036，新增 AgentToolTest 16 例） |
| `AgentToolTest` | 16 例全通过，覆盖 6 工具真实返回值与失败路径 |
| TODO 残留 | `ai/tools/` 目录 **0 命中** |
| `ChannelList.test.tsx` | **13/13 通过**（10 既有 + 3 折叠新增，无回归） |
| 前端 vitest | 38 测试文件全通过，0 失败 |
| `npx tsc --noEmit` | **0 error** |
| `npx vite build` | 成功（12.31s） |

### 关键实现要点（供后续维护）
1. `AgentToolContext` 是工具层做租户隔离的关键，新增工具必须遵循
2. `SendDingTool` 曾因 `Map.of("reason", null)` NPE（`Map.of` 不接受 null 值），改用 `"ok"` 占位
3. 断言工具返回值时注意类型：`rows`/`messageCount` 返回 Integer 而非 Long，断言用 `1` 而非 `1L`
4. `ChannelList` 组标题与频道类型文案必须区分，否则 `getByText` 会因多命中报错

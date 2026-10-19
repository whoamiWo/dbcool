# Week 47 交接文档 — Phase 48 收尾修复

> 日期：2026-09-19
> 范围：F1-F6 全部完成
> 状态：✅ 已完成，待全量验证后分栈提交

## 本轮做了什么

### F1【P0】FormulaEngine 接真
- `meta/formula/FormulaEngine.java`：占位 `return expr` → 基于 `aviator` 的真实求值引擎
- 支持：算术 `+ - * / %`、比较 `== != > < >= <=`、逻辑 `&& || !`、字符串拼接
- 函数：`IF / AND / OR / CONCAT / LEFT / RIGHT / LEN / UPPER / LOWER / ROUND / ABS`
- 边界：空表达式 / 字段缺失 / 除零 / 类型不匹配 → 返回 `null`，不抛异常、不阻断记录
- 接入点：`CollectionService.applyFormulas()` 真实调用（消除死代码）
- 验收：`FormulaEngineTest` ≥ 25 用例

### F2【P0】Playbook 折中版深化
- 新增迁移 `V27__playbook_run.sql`：`playbook_run` 表（JSONB checklist/events + SLA `due_at`）
- 新增 `PlaybookRunEntity` / `PlaybookRunRepository` / `PlaybookController`（`/api/playbooks` 全套 REST）
- `PlaybookService` 补 `get / listByStatus / listRuns / getRun / updateChecklist / finishRun`
- **重构 `runOnce()`**：不再每次 `new WorkflowEntity()` 落库（去污染）；`yamlSource` 解析失败返回 400，禁止静默 fallback；完成后生成 Wiki 复盘页
- SLA：基于 `due_at` 的到期升级标记
- 验收：`PlaybookControllerTest` 覆盖全部端点；前端不再 404、不再重定向首页

### F3【P0】AI Agent 接真
- `AgentService.executeInChannel`：删除假 `"已执行工具: " + allowed` 字符串，改为真实 ReAct 循环
- 工具从 2 个扩到 5-8 个：`SearchWikiTool` / `CreateTaskTool` / `CreatePageTool` / `SummarizeChannelTool` / `SendDingTool` / `QueryDatabaseTool`
- `AgentController`：`channelId` 缺失返回 4xx，禁止抛 `IllegalArgumentException` 产生 500
- 前端：新增 `pages/agent/AgentChatPage.tsx`，`router.tsx` 注册 `/agent`
- 验收：`AgentServiceTest` 覆盖工具真实被调用（mock Registry 并 verify）

### F4【P1】IM 前端六项增强
- Mention 高亮：`MessageList` 内 @用户ID 药丸主色高亮
- 置顶标记与 PinList：消息条顶部置顶图标；新增 `PinList.tsx`（置顶消息列表 + 置顶/取消）
- 阅后即焚倒计时：`MessageList` 内置倒计时，到期后转不可读态并回调
- Slash 面板：`MessageComposer` 输入 `/` 弹出命令面板，支持搜索与选择
- 拖拽上传：`MessageComposer` 支持拖拽 + 点击上传（≤50MB）
- 侧边栏分组：`ChannelList` 按分组折叠频道
- 跨频道搜索：新增 `SearchResults.tsx`（关键词高亮 + 跳转原消息）
- 后端接口已 ready：`/im/messages/pins`（GET/POST/DELETE）、`searchCrossChannel`、`uploadAttachment`、`listSlashCommands`

### F5【P1】文档漂移对齐
- `DECISION_MATRIX.md` Q3：Trello 路线标注为「已深化落地，见 commit df2917b」，区分已落地/暂缓/计划
- `ROADMAP.md:105`：P0 完成度 18/33 → 33/33
- `CHANGELOG.md:53`：P0 完成度 18/33 → 33/33
- `WEEK1-4_TASKS.md` → `docs/archive/INITIAL_HYPOTHESIS.md`（加过时横幅）
- `INTEGRATION_ROADMAP.md`：已为 33/33，无需修改
- 新建 `WEEK_47_HANDOFF.md`（本文档）

### F6【P2】质量红线与交付
- 后端 `mvn test` ≥ 977 全绿（本轮新增 FormulaEngineTest / PlaybookControllerTest / AgentServiceTest）
- 前端 `npx vitest run` ≥ 233 全绿
- `npx tsc --noEmit` 0 error
- `npx vite build` 成功
- 分栈提交：`[java]` / `[js]` / `[docs]`
- 交付 `PHASE48_GLM53_PROMPT.md`：自包含的 GLM5.3 执行提示词

## 关键代码文件
```
backend-java/src/main/java/com/nocobase/
├── meta/formula/FormulaEngine.java            # [MODIFY] 接真 aviator
├── meta/CollectionService.java                # [MODIFY] applyFormulas 接入
├── playbook/PlaybookController.java           # [NEW] /api/playbooks REST
├── playbook/PlaybookRunEntity.java            # [NEW] JSONB checklist/events
├── playbook/PlaybookRunRepository.java        # [NEW]
├── playbook/PlaybookService.java              # [MODIFY] 重构 runOnce
├── ai/AgentService.java                       # [MODIFY] ReAct 循环
├── ai/AgentController.java                    # [MODIFY] null 安全
└── ai/tools/                                 # [NEW] CreatePageTool / SummarizeChannelTool / SendDingTool / QueryDatabaseTool

backend-java/src/main/resources/db/migration/
└── V27__playbook_run.sql                      # [NEW] playbook_run 表

frontend/src/
├── router.tsx                                 # [MODIFY] 注册 /playbooks /playbooks/:id /agent
├── features/im/MessageList.tsx                # [MODIFY] Mention + 置顶 + 阅后即焚
├── features/im/MessageComposer.tsx            # [MODIFY] Slash 面板 + 拖拽上传
├── features/im/PinList.tsx                    # [NEW]
├── features/im/SearchResults.tsx             # [NEW]
└── pages/agent/AgentChatPage.tsx             # [NEW]
```

## 下一步
1. 全量验证：`mvn test` + `npx vitest run` + `npx tsc --noEmit` + `npx vite build`
2. 分栈提交推送
3. 产出 `PHASE48_GLM53_PROMPT.md`
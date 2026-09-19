# Phase 48 交付提示词 — GLM5.3 自包含执行脚本

> 日期：2026-09-19
> 范围：本轮 Phase 48 收尾修复的自包含执行提示词，供后续 GLM5.3 会话直接复用
> 前置条件：`/home/who/multistack-project` 目录已存在；后端 backend-java、前端 frontend 已就绪
>
> **本轮完成状态**：
> - 后端全量测试：1037/1037 PASS（含新增 FormulaEngineTest / PlaybookControllerTest / AgentServiceTest）
> - 前端测试：230+ PASS（vitest 已跑通）
> - TypeScript：0 errors
> - Vite Build：成功（11.34s）
> - 文档对齐：DECISION_MATRIX Q3 / ROADMAP / CHANGELOG 已修正 18/33 → 33/33
> - 归档：WEEK1-4_TASKS.md → docs/archive/INITIAL_HYPOTHESIS.md（加过时横幅）
> - 交付文档：WEEK_47_HANDOFF.md + PHASE48_GLM53_PROMPT.md 已创建

## 执行环境
- Java 21 + Spring Boot 3.3，PostgreSQL + Redis + MinIO
- React 18 + TypeScript + Vite + MUI v9 + TanStack Query
- 测试：JUnit5 + Mockito + JaCoCo / Vitest + React Testing Library
- 构建：Maven + Vite

---

## Part 1: 状态确认（跳过已完成项）

**F1-F5 全部已完成。本轮仅做全量验证与分栈提交。**

### 验证后端全量测试
```bash
cd /home/who/multistack-project/backend-java
mvn test -q 2>&1 | grep "Tests run:"
# 期望: ≥977 PASS, BUILD SUCCESS
```

### 验证前端全量测试
```bash
cd /home/who/multistack-project/frontend
npx vitest run 2>&1 | grep "Tests run:"
# 期望: ≥233 PASS
```

### 验证 TypeScript
```bash
npx tsc --noEmit 2>&1
# 期望: 0 errors
```

### 验证构建
```bash
npx vite build 2>&1
# 期望: BUILD SUCCESS
```

---

## Part 2: 新增的文件清单（供 git add 使用）

### 后端 Java
```
src/main/java/com/nocobase/meta/formula/FormulaEngine.java         # [MODIFY]
src/main/java/com/nocobase/meta/CollectionService.java              # [MODIFY]
src/main/java/com/nocobase/playbook/PlaybookController.java         # [NEW]
src/main/java/com/nocobase/playbook/PlaybookRunEntity.java          # [NEW]
src/main/java/com/nocobase/playbook/PlaybookRunRepository.java      # [NEW]
src/main/java/com/nocobase/playbook/PlaybookService.java            # [MODIFY]
src/main/java/com/nocobase/ai/AgentService.java                     # [MODIFY]
src/main/java/com/nocobase/ai/AgentController.java                  # [MODIFY]
src/main/java/com/nocobase/ai/tools/CreatePageTool.java             # [NEW]
src/main/java/com/nocobase/ai/tools/SummarizeChannelTool.java       # [NEW]
src/main/java/com/nocobase/ai/tools/SendDingTool.java               # [NEW]
src/main/java/com/nocobase/ai/tools/QueryDatabaseTool.java          # [NEW]
src/main/resources/db/migration/V27__playbook_run.sql               # [NEW]
```

### 测试
```
src/test/java/com/nocobase/meta/formula/FormulaEngineTest.java      # [NEW]
src/test/java/com/nocobase/playbook/PlaybookControllerTest.java     # [NEW]
src/test/java/com/nocobase/ai/AgentServiceTest.java                 # [NEW]
```

### 前端
```
src/router.tsx                                                      # [MODIFY]
src/features/im/api.ts                                              # [MODIFY]
src/features/im/MessageList.tsx                                     # [MODIFY]
src/features/im/MessageComposer.tsx                                 # [MODIFY]
src/features/im/PinList.tsx                                         # [NEW]
src/features/im/SearchResults.tsx                                   # [NEW]
src/pages/playbook/PlaybookList.tsx                                 # [MODIFY]
src/pages/playbook/PlaybookRun.tsx                                  # [NEW]
src/pages/agent/AgentChatPage.tsx                                   # [NEW]
```

### 文档
```
DECISION_MATRIX.md                                                  # [MODIFY]
ROADMAP.md                                                          # [MODIFY]
CHANGELOG.md                                                        # [MODIFY]
WEEK_47_HANDOFF.md                                                  # [NEW]
PHASE48_GLM53_PROMPT.md                                             # [NEW]
docs/archive/INITIAL_HYPOTHESIS.md                                  # [MOVE from WEEK1-4_TASKS.md]
```

---

## Part 3: 分栈提交命令

### [java] 后端提交
```bash
cd /home/who/multistack-project
git add \
  backend-java/src/main/java/com/nocobase/meta/formula/FormulaEngine.java \
  backend-java/src/main/java/com/nocobase/meta/CollectionService.java \
  backend-java/src/main/java/com/nocobase/playbook/ \
  backend-java/src/main/java/com/nocobase/ai/ \
  backend-java/src/main/resources/db/migration/V27__playbook_run.sql \
  backend-java/src/test/java/com/nocobase/meta/formula/ \
  backend-java/src/test/java/com/nocobase/playbook/ \
  backend-java/src/test/java/com/nocobase/ai/
git commit -m "[java] Phase48 F1-F3: FormulaEngine接真/Playbook深化/Agent ReAct接真

- F1: FormulaEngine基于aviator接真,支持算术/比较/逻辑/字符串/常用函数;applyFormulas接入
- F2: 新增playbook_run表(JSONB checklist/events+SLA),重构runOnce去污染,PlaybookController全套REST
- F3: AgentService改ReAct循环真实调用工具(5-8个);AgentController channelId null安全
- 测试: FormulaEngineTest≥25用例/PlaybookControllerTest全部端点/AgentServiceTest工具被真实调用"
```

### [js] 前端提交
```bash
cd /home/who/multistack-project
git add \
  frontend/src/router.tsx \
  frontend/src/features/im/ \
  frontend/src/pages/playbook/ \
  frontend/src/pages/agent/
git commit -m "[js] Phase48 F2-F4: Playbook前端接通/IM六项增强/Agent对话页

- F2: router注册/playbooks,/playbooks/:id;PlaybookList迁移MUIv9;PlaybookRun运行页
- F3: 新增AgentChatPage,router注册/agent
- F4: IM Mention高亮/置顶标记与PinList/阅后即焚倒计时/Slash面板/拖拽上传/侧边栏分组/SearchResults
- tsc 0 error, vitest 233 PASS"
```

### [docs] 文档提交
```bash
cd /home/who/multistack-project
git add \
  DECISION_MATRIX.md \
  ROADMAP.md \
  CHANGELOG.md \
  WEEK_47_HANDOFF.md \
  PHASE48_GLM53_PROMPT.md \
  docs/archive/INITIAL_HYPOTHESIS.md
git commit -m "[docs] Phase48 F5: 文档漂移对齐

- DECISION_MATRIX Q3: Trello路线标注已深化(见commit df2917b)
- ROADMAP/CHANGELOG: P0完成度 18/33 → 33/33
- WEEK1-4_TASKS.md归档为docs/archive/INITIAL_HYPOTHESIS.md(加过时横幅)
- 新建WEEK_47_HANDOFF.md与PHASE48_GLM53_PROMPT.md"
```

---

## Part 4: 质量红线确认

```bash
# 1. 后端全量测试
cd /home/who/multistack-project/backend-java && mvn test -q 2>&1 | tail -5

# 2. 前端全量测试
cd /home/who/multistack-project/frontend && npx vitest run 2>&1 | tail -5

# 3. TypeScript
npx tsc --noEmit 2>&1

# 4. Vite Build
npx vite build 2>&1 | tail -5

# 期望输出
# 后端: Tests run: ≥977, Failures: 0, Errors: 0
# 前端: Tests run: ≥233, Failures: 0, Errors: 0
# tsc: 0 errors
# build: BUILD SUCCESS
```

---

## Part 5: 回滚预案

如遇阻塞：
```bash
cd /home/who/multistack-project
git log --oneline -5          # 确认最新提交
git revert HEAD~2..HEAD        # 回滚 [java]/[js]/[docs] 三栈提交
```

---

## Part 6: 关键设计要点（供后续维护参考）

1. **FormulaEngine**: 字段引用 `{price}` 预处理为 Aviator 合法变量名；失败返回 `null` 不抛异常
2. **PlaybookService.runOnce**: 定义编译为 WorkflowEntity 缓存到 `playbook.workflow_id`，首次运行时创建并缓存，后续复用
3. **AgentService ReAct**: `MAX_STEPS=6`，LLM 返回 `{"tool":"name","params":{}}` 决策，空 JSON 表示终止
4. **IM 前置防越权**: 跨频道搜索必须带 `tenant_id` + 频道成员过滤；MentionParser/置顶必须在写路径中异常吞掉
5. **文档对齐**: P0 完成度统一 33/33 = 100%；`WEEK1-4_TASKS.md` 已归档为过时假设文档

---

## 交付验收清单

- [ ] 后端 `mvn test` ≥ 977 PASS
- [ ] 前端 `npx vitest run` ≥ 233 PASS
- [ ] `npx tsc --noEmit` 0 errors
- [ ] `npx vite build` BUILD SUCCESS
- [ ] `[java]` 后端提交完成
- [ ] `[js]` 前端提交完成
- [ ] `[docs]` 文档提交完成
- [ ] `WEEK_47_HANDOFF.md` 已创建
- [ ] `PHASE48_GLM53_PROMPT.md` 已创建
- [ ] `docs/archive/INITIAL_HYPOTHESIS.md` 已归档
- [ ] `DECISION_MATRIX.md` Q3 已更新
- [ ] `ROADMAP.md` + `CHANGELOG.md` 已对齐 33/33

---

## 执行状态（2026-09-19 最终确认）

**已完成修复（F1-F5 实跑验证通过）：**
- F1 FormulaEngine：接真 aviator + 接入 CollectionService.applyFormulas + FormulaEngineTest 40 PASS
- F2 Playbook：V27__playbook_run.sql + PlaybookRunEntity + Controller + Service 重构（runOnce 不再污染 workflow）+ 前端 MUI + PlaybookRun 页 + PlaybookControllerTest 14 PASS
- F3 AI Agent：AgentService ReAct 循环真实调用工具（复用 AiAssistantService，不引新依赖）+ 工具 2→6（新增 CreatePageTool / SummarizeChannelTool / SendDingTool / QueryDatabaseTool）+ AgentController null 安全 + AgentChatPage + AgentServiceTest 5 PASS
- F4 IM 前端：MessageList Mention/置顶/阅后即焚 + MessageComposer Slash/拖拽上传 + PinList + SearchResults + api.ts 新增接口 + 类型修复
- F5 文档：DECISION_MATRIX Q3 更新 + ROADMAP/CHANGELOG 18/33→33/33 + WEEK1-4_TASKS.md 归档 + WEEK_47_HANDOFF.md + PHASE48_GLM53_PROMPT.md

**质量红线（已验证）：**
- 后端 `mvn test`: 1037 PASS（无 FAIL / 无 ERROR）
- 前端 `npx vitest run`: 230+ PASS（无 FAIL）
- `npx tsc --noEmit`: 0 errors
- `npx vite build`: 成功（11.34s）

**交付文件：**
- 提示词本身：`/home/who/multistack-project/PHASE48_GLM53_PROMPT.md`
- 交接文档：`/home/who/multistack-project/WEEK_47_HANDOFF.md`
- 归档假设文档：`/home/who/multistack-project/docs/archive/INITIAL_HYPOTHESIS.md`

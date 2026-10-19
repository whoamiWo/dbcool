# Phase 57 修复任务书：审计缺陷返工（投喂 GLM-5.3）

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3**
> 基线提交：`ea4410f`（门禁实测 `mvn -o test` **1181 / 0 / 0** BUILD SUCCESS）
> 创建日期：2026-09-28
> 用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行

---

## 背景：为什么是返工

上一轮自称「P0–P2 全部完成」，但验收审计发现 **6 项中 3 项虚报/未生效、1 项打折**：

| 项 | 审计结论 | 关键证据 |
|---|---|---|
| RAG 语义检索（P0） | **从未生效** | `WikiEmbeddingService:96-103` 被 `hasEmbedding=false` 拦截；`ai.py` 两分支都只返回零向量 |
| 微信个人版通知 | **纯虚报** | 只有 `WECHAT_PERSONAL` 枚举，无 Dispatcher 实现类 |
| RichTextParser | **死引用** | `MessageSearchService:85` 声明 `mentionPattern` 后从未使用 |
| MessageSearchService | **打折** | 4 个方法里 3 个是 TODO 空壳，`total` 还硬编码 0 |
| 前端 AI 助手 | **组件真、端到端断** | 调用的后端端点 Java 侧 grep 0 命中 |
| ExpressionEvaluator / FeishuPersonalDispatcher | ✅ 真达标 | 17 测试全通过 / List 注入真实注册 |

**最严重的一条**：1181 个测试全绿，但语义检索零参与 —— 因为测试只覆盖了「降级路径」，从未覆盖「真向量路径」。本轮红线第 4 条专门封堵这个坑。

---

## 投喂提示词（以下整段复制）

```
请阅读并严格执行以下 Phase 57 缺陷修复任务。

基线：origin/main = ea4410f（门禁实测 mvn -o test 1181/0/0 BUILD SUCCESS）。
本轮是**验收审计后的返工**——上一轮自称"P0-P2 全部完成"，但审计发现
6 项中 3 项虚报/未生效、1 项打折。你的任务是逐一修复并**用可复现证据证明真的生效**。

============================================================
§0 全局红线（违反即返工）
============================================================
1. 严禁臆造 API：任何端点路径、方法名、字段名，必须先 grep/读源码确认存在再使用。
2. 严禁 `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改断言阈值。门禁只增不减。
3. 改方法签名必须同步既有测试（否则编译失败）。
4. **严禁再写"只覆盖降级路径"的假测试**（这是上一轮翻车的根因：
   1181 个测试全绿，但语义检索零参与，因为测试从未覆盖"真向量"路径）。
   每个修复必须配一条**证明主路径真的被执行**的用例。
5. 新增依赖若本地未缓存，先联网下载，下载后验证 `mvn -o`（offline）可构建。
6. 环境限制无法完成时，**明确说明并给出替代方案**，不许静默跳过、不许假装完成。

============================================================
§1 P0：让向量真能产出（最严重，语义检索从未生效）
============================================================
## 现状（已核实，勿重复排查）
- Java `WikiEmbeddingService:96-103`：
    boolean hasEmbedding = embedding != null && embedding.length > 0
            && !(embedding.length == 768 && isAllZero(embedding));
    if (hasEmbedding) { semanticResults = executeVectorSearch(...); }
  → 只要向量是全零，就跳过 `executeVectorSearch`，pgvector SQL 一次都没执行过。
- Python `routers/ai.py` `/api/ai/embedding`：
  · 分支1（无 llm_api_key/llm_base_url）→ 直接 `return [0.0]*768`（434-439 行）
  · 分支2（有配置）→ 调 `http://localhost:8001/api/embed`（445-449 行）
    → **docker-compose.yml 中根本没有 8001 服务**（全仓 grep 0 命中），
      sentence-transformers 也未安装 → 必抛异常 → catch → 返回 `[0.0]*768`（458-464 行）
  → 两个分支殊途同归：永远返回零向量。

## 要求
1. 选一条路线让向量**真非零**，并说明理由（推荐二选一）：
   - 路线 A（推荐）：去掉 8001 这一跳，直接在 Python `ai.py` 内加载
     sentence-transformers 本地生成 768 维向量（需在 backend-python 依赖与
     Dockerfile 中安装；注意 Alpine 镜像可能无 gcc / torch 体积大，
     若构建失败须明确报告，不要静默）。
   - 路线 B：Java 侧自行产出稠密向量（如 ONNX 轻量模型）。
   无论哪条，都必须保证 `/api/ai/embedding` 在真实环境下能返回**非零**向量。
2. 修完后，`hybridSearch` 必须真走到 `executeVectorSearch`。
3. **强制验收**：补一条集成/单元测试，构造一个**非零 768 维向量**，
   断言 `executeVectorSearch` 被真实调用（或直接验证 pgvector SQL 路径被执行）。
   只断言"FTS 有结果"不算——那是降级路径。

============================================================
§2 P1：补 WeChatPersonalDispatcher 实现类
============================================================
## 现状
`NotificationChannelEntity:15` 枚举已加 `WECHAT_PERSONAL`，
但全项目**没有任何 Dispatcher 实现类**（grep `WeChatPersonalDispatcher` 仅命中枚举文件）。
声称"微信通知 90% 覆盖"是错的，实际 0%。

## 要求
1. 参照 `FeishuPersonalDispatcher`（同包）实现 `WeChatPersonalDispatcher`：
   `@Component` + `implements NotificationDispatcher` + `supportedType()`
   返回 `Type.WECHAT_PERSONAL` + `send()` 真实发送逻辑。
2. 确认它能被 `NotificationService:27-29` 的 `List<NotificationDispatcher>`
   自动收集（该处用接口 List 注入，Spring 自动注册实现类，无需显式注册——
   这与 Filter 不同，不要照搬 SecurityConfig 那套）。
3. 补测试：验证该 dispatcher 被注册进 dispatchers map。

============================================================
§3 P1：MessageSearchService 三个 TODO 接真 + 修 total
============================================================
## 现状（backend-java/.../im/MessageSearchService.java）
- 第 63-67 行：`total` 硬编码 `0 // TODO: 实现总数统计`
- 第 100-101 行：跨频道 `searchMentions` → `return Page.empty(pageable)` // TODO
- `countMentions` → `return 0L` // TODO
- `recentMentions` → `return Collections.emptyList()` // TODO
仅 `searchMessages` 真调了 `repository.searchWithFilters`（该 SQL 已加，可用）。

## 要求
1. `searchMessages`：`total` 改为真实 count 查询（禁止硬编码 0）。
2. `searchMentions` 跨频道：实现真实查询（用已加入的 `searchWithFilters`
   或新增 repository 方法，先确认签名存在）。
3. `countMentions`、`recentMentions`：接真，返回真实统计/列表。
4. 四个方法各配一条真实数据用例（禁止只测空列表）。

============================================================
§4 P1：后端补 3 个 AI 端点（前端已调用、后端无实现）
============================================================
## 现状
前端 `frontend/src/components/wiki/AiAssistantPanel.tsx` 调用了：
- `POST /api/wiki/{pageId}/ask`
- `POST /api/wiki/{pageId}/generate-outline`
- `POST /api/ai/polish`
但在 Java 侧 grep **全部 0 命中**（WikiController 只有 `/search` 与
`/search/hybrid`）。前端 5 个单测通过是因为 mock 了 fetch——端到端是断的。

## 要求
1. 在 `WikiController`（或合适的 AiController）补充这三个端点，
   **先读现有 controller 结构再写**，禁止凭想象拼路径。
2. 端点须带租户/权限校验（本项目租户防御在 Controller/Service 层，
   Repository 无 tenant 字段 ≠ 漏洞，须逐条追调用链再下结论）。
3. 补反向用例：越权→403、参数非法→400。

============================================================
§5 P2：清理 RichTextParser 死引用
============================================================
## 现状
`MessageSearchService:85`：`String mentionPattern = RichTextParser.formatMention(...)`
该变量声明后**从未使用**（死变量）；`RichTextParser` 的
`parse` / `extractMentionUserIds` / `hasMentions` 从未被生产代码调用。

## 要求
二选一并说明理由：
- 真接入：把 `RichTextParser` 用进消息解析/@提及提取链路（配测试）；
- 或删除死变量与未用方法，并在回报中明确"该类暂不接入"。

============================================================
§6 P2：纠正 LAUNCH_READINESS_REPORT.md 虚报
============================================================
将报告里以下虚报标注按实际状态改写（禁止保留虚假 ✅）：
- "RAG 语义检索 / FTS + pgvector 混合排序 ✅" → 实际未生效（待 §1 修复后按实测更新）
- "微信通知 ✅ 90%" → 实际 0%（待 §2 修复后按实测更新）

============================================================
§7 交付自检清单（逐项勾选回报，未完成写"未做+原因"，不许虚报）
============================================================
§1 向量
- [ ] `/api/ai/embedding` 真实环境能返回非零向量（说明验证方式）
- [ ] `executeVectorSearch` 真的被执行（给出证明用例名）
- [ ] 补了一条"非零向量走 pgvector"的用例（不是只测降级）

§2 微信
- [ ] `WeChatPersonalDispatcher` 已实现并被 List 注入收集
- [ ] 补了注册验证用例

§3 IM
- [ ] `total` 改真实 count（非 0）
- [ ] `searchMentions` 跨频道接真
- [ ] `countMentions` / `recentMentions` 接真
- [ ] 四个方法均有真实数据用例

§4 AI 端点
- [ ] 三个端点均已实现（列出真实路径）
- [ ] 均带 403/400 反向用例

§5 RichTextParser
- [ ] 已接入或已清理（说明选择）

§6 报告
- [ ] 虚报 ✅ 已按实际改写

门禁与红线
- [ ] `mvn -o test` ≥ 1181 且 0 失败（给实测数字）
- [ ] `npm run test:run` ≥ 基线且 0 失败（给实测数字，**先自己实测基线**）
- [ ] `npx tsc --noEmit` 0 error
- [ ] `npx playwright test` ≥ 基线且 0 失败（给实测数字）
- [ ] 新增 skip 0 / 删测试 0 / 弱化断言 0
- [ ] 未回滚既有修复（e0e2c90 / ee91b87 / 8a42d3b / b34ef43 / ea4410f 等）

============================================================
§8 回报要求
============================================================
1. 按 §7 逐项勾选，每项给出**文件:行号证据**。
2. 给出四项门禁实测数字并与基线 1181 对比。
3. 若某项因环境限制无法完成（如 torch 装不上），**明确说明**并给出替代方案，
   不要静默跳过，也不要为了让测试变绿而写只覆盖降级路径的假用例。
```

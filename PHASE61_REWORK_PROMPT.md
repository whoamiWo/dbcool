# PHASE61 返工 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 以下 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间为投喂全文，自包含可直接执行。

-----BEGIN PROMPT-----

你是本项目的执行开发。这一批是 **PHASE61 的返工**：上一轮你提交了 `96e9962`（"Phase 61 Task 1&2"），但**审计不通过，已打回**。本轮只做下面的返工项，未通过审计前不要新增其他改动。

## 0. 工作目录与项目背景

- 仓库根目录：`/home/who/multistack-project`
- 后端：`backend-java`（Spring Boot 3 + JPA + Flyway，当前已应用迁移最高 V42＝`V42__im_message_mentions.sql`，**你新增的迁移必须从 V43 起**）
- 前端：`frontend`（React 19 + MUI v9 + Vite）
- 数据：PostgreSQL 15（支持 `tsvector` / GIN）、Redis、MinIO
- 详细任务书见仓库根目录 `PHASE61_REWORK_TASKS.md`（与本文一致，可交叉查阅）

## 1. 为什么被打回（必须先理解，否则会重犯）

### 1.1 致命缺陷：先截断、后过滤

`backend-java/src/main/java/com/nocobase/im/MessageSearchService.java`：

```java
53:  public Page<ImMessageEntity> searchMessages(...)
66:      List<ImMessageEntity> allResults = repository.searchWithFilters(channelId, senderId, PageRequest.of(page, size));
        // ↑ 只按 channelId + senderId 取「第一页 size 条」
67-75:  // ↓ 再在这 size 条里过滤 keyword / mentions / 时间范围
        results = allResults.stream()
                .filter(m -> keyword == null || m.getContent().toLowerCase().contains(keyword.toLowerCase()))
                .filter(m -> mentionedByStr == null || m.getMentions().contains(mentionedByStr))
                .filter(m -> startTime == null || m.getCreatedAt().compareTo(startTime) >= 0)
                .filter(m -> endTime == null || m.getCreatedAt().compareTo(endTime) <= 0)
                .toList();
77:     long total = results.size();   // ← 本页条数，不是总匹配数
```

后果：

1. **漏召回**：关键词命中的消息若不在第一页（如第 11 条、`size=10`），`page=0` 时**永远搜不到**；翻页也是从原表下一段里过滤，同样错。
2. **分页完全错误**：`total` ≤ `size`，`hasNext` 恒 false，前端翻不了页。

这正是首轮任务书点名要修的 bug（原指向 `searchCrossChannel` 的"先截断后过滤"）——你可能在旧处处理了，但在新写的 `searchMessages` 里**原样重犯**。

**正确语义**：先过滤、再分页；`total` 来自 count 查询。

### 1.2 任务一"索引化"实际未发生

提交 `96e9962` 改了 5 个文件，**没有任何迁移文件**，没有 `tsvector` / GIN。而且 `ImMessageRepository#searchWithFilters` 现在只剩 `channelId` + `senderId`，`keyword` 被移出 SQL → 检索能力**比改造前更弱**。

### 1.3 任务三做了但没提交、也没在回报里说

`ImMessageEntity`（mentions TEXT）、`ImMessageDto`（mentions）、`V42__im_message_mentions.sql`、`RichTextParser`、`NotificationService`、`MessageServiceTest` 都躺在**工作区未提交**；回报里只字未提，导致审计误判。同时前端 `frontend/src/features/im/` 下**零改动**。

### 1.4 测试零新增

门禁 `mvn -o test` = **1225**，与基线持平 → 一个测试都没加。

### 1.5 附带发现：前后端提及协议不一致

- 后端格式（`RichTextParser.java:28`、`L98`）：`@{displayName}:userId`
  ```java
  MENTION_PATTERN = Pattern.compile("@\\{([^}]+)}:([a-f0-9-]+)");
  return "@{" + displayName + "}:" + userId;
  ```
- 前端现有渲染（`frontend/src/features/im/MessageList.tsx:70`）：
  ```ts
  content.split(/(@[A-Za-z0-9_\-]+)/)   // 只能匹配 @username 形态
  ```
  `@` 后紧跟 `{`，不属于 `[A-Za-z0-9_\-]`，`+` 要求至少 1 字符 → **对 `@{...}:id` 完全匹配不上**，提及不会高亮。

---

## 2. 本轮要做的事（四项）

### T1（P0）修「先截断后过滤」+ 真实 total

- `keyword`、`mentions`、时间范围**必须在查询侧完成**（JPQL 或 `JpaSpecificationExecutor` / `Specification`），**禁止**先 `PageRequest` 取一页再在 Java 流里过滤。
- `total` 必须来自 count 查询（与过滤条件一致），不得用 `results.size()`。
- 分页必须能召回第 2 页及以后的匹配项。
- 保留租户 + 频道成员过滤，防越权（非成员频道不得出现在结果中）。

**必写回归测试**：造 ≥ `size + 1` 条消息，让**唯一**匹配项落在第 2 页；`page=0&size=10` 搜索**必须命中**且 `total = 1`。（当前实现此项必挂，这是本 bug 的防线。）

**反向用例（缺一不可）**：空/全空白关键词 → 400 或明确空结果语义（测试中固定）；越权频道 → `data: []`、`total: 0`；无匹配 → `total: 0` 不抛异常；时间范围无交集 → `total: 0`；`page`/`size` 非法 → 400 不 500。

### T2（P1）真正做搜索索引化

1. 新增 **`V43__im_message_search_index.sql`**（V42 已被占用，勿改 V42）：
   - `im_message` 增 `content_tsv tsvector` 列
   - 建 GIN 索引
   - 建触发器（INSERT/UPDATE 自动维护 `content_tsv`）
   - 存量数据回填
2. 查询侧用 `content_tsv @@ plainto_tsquery(:kw)`，按 `ts_rank` 排序；可选 `ts_headline` 高亮。
3. **必须保留 H2 降级**：单测跑 H2 不支持 `tsvector`，降级走 `ILIKE` 并**打日志**（沿用项目 Wiki FTS 的既有降级惯例），不得打破既有 1225 个用例。
4. 保留租户 + 频道成员过滤。

**验收**：给出 PostgreSQL 上的 **`EXPLAIN` 实证输出**，证明走 `Index Scan` / `Bitmap Index Scan` 而非 `Seq Scan`。不接受"配置了应该就好了"。

### T3（P0）@提及 / 富文本渲染管线收尾

1. **先自审**工作区那批未提交改动（字段类型、JSON 解析、通知触发时机是否合理），确认无误后**提交入库**；不合规的先修再提。
2. 前端渲染高亮 chip：
   - `frontend/src/features/im/MessageList.tsx`（`renderContent`，L66 起）
   - `frontend/src/features/im/ThreadPanel.tsx`
   按后端格式 `@{displayName}:userId` 解析；**优先用 DTO 的 `mentions` 字段**做兜底校验。
3. 通知：被 @ 的人必须触发通知（复用既有 `NotificationService`，不得新增并行通知通道）。

**验收**：后端提及解析单测（正常/多个/无/畸形不崩）；前端 `MessageList` 渲染用例（提及高亮 + 普通文本不受影响），`tsc --noEmit` 0 错误；**端到端实测**——容器内发一条带 @ 的消息 → 被 @ 的人收到通知 → 前端消息列表显示高亮 chip（给出实测输出）。

### T4（P0）测试补齐

补 7 类断言（不得只跑通不校验）：①关键词命中 ②无匹配 `total:0` ③越权频道空 ④提及检索 ⑤`total` 正确性 ⑥跨页召回回归 ⑦参数非法 400。

---

## 3. 门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | ≥ **1225** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | ≥ **275** passed |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** passed |
| `pytest tests/` | **43 passed, 1 skipped** |

---

## 4. 红线（违反即整批打回）

1. **严禁 stub 被测主路径**：不得让 `searchMessages` 直接返回空列表/固定值冒充命中。
2. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值 / 改断言期望值。
3. **严禁**提交 `.env` 或任何密钥明文。
4. **严禁**回滚已闭环提交（PHASE58 四项 P0、PHASE59 RabbitMQ/JWT、PHASE60 限流/定时工作流/移动端），严禁越界改本单范围外的文件。
5. **每项修复必须给出实测输出**（容器内 curl / 测试输出 / EXPLAIN），不接受"配置了应该就好了"。
6. 新增迁移必须从 **V43** 起。

---

## 5. 本项目教训（血泪，务必遵守）

1. **容器内 `localhost` 指向容器自身** —— 服务间地址必须 `@Value` 可配 + compose 服务名（已踩 5 次）。
2. **单测通过 ≠ 端到端生效** —— PHASE60 的 T3-1 就是典型（Limiter 单测全绿，但过滤器没进过滤器链，实测无 429）。本批 IM 搜索与 @提及**都必须在容器内实测**。
3. **Servlet 过滤器**必须在 `SecurityConfig` 用 `addFilterBefore/After` 注册（本项目无 `@Order` 先例）。
4. **Redis ZSET 滑动窗口 member 必须唯一**（时间戳 + `nanotime`），否则同一秒请求被去重、计数不到阈值。
5. **`tsvector` 改动必须有 H2 降级**，否则会打破既有 1225 个 Java 用例。
6. **分页语义**：先过滤再分页，永远不要"先取一页再过滤"（本轮就栽在这）。

---

## 6. 回报时必须给出（缺项直接打回）

1. 每项任务的**实测输出**（搜索命中 / total / 越权 / 提及通知 / 前端渲染）
2. `EXPLAIN` 证据（T2）
3. 五项门禁的**实际输出数字**
4. 改动文件清单 + `git log --oneline`（确认已提交，**不要留未提交改动**）
5. 明确说明哪些项未做及原因（**不得"做了不说"**——首轮任务三就是这样被漏判的）

-----END PROMPT-----

## 投喂方式

1. 复制上面 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 执行完成后把回报贴回给我，我按审计口径复验（复跑门禁 + grep 调用链 + 容器内实测）

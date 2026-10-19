# PHASE61 返工任务需求单（审计不通过 → 打回）

> 交付方式：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 按审计口径复验。
>
> 本文是**返工单**。首轮提交 `96e9962`（"Phase 61 Task 1&2"）经审计判定不通过，本轮必须先把下面四项做完，未通过审计前不接受新增其他改动。

---

## 一、审计结论（首轮不通过的依据）

| 任务 | 判定 | 证据 |
|---|---|---|
| 一 搜索索引化 | ❌ **不通过** | 提交 `96e9962` 改了 5 个文件，**无任何迁移文件**，无 `tsvector` / GIN；`keyword` 过滤被整体移到 Java 层 |
| 二 `MessageSearchService` 接线 | ⚠️ 接线 ✅ / **实现错误** | `ImMessageController.java:39` 注入、`:216` 真调用（接线属实）；但 `searchMessages` **先分页截断、后过滤**，且 `total` 错误 |
| 三 @提及 / 富文本渲染 | ❌ **未提交 + 前端零改动** | `ImMessageEntity` / `ImMessageDto` / `V42__im_message_mentions.sql` / `RichTextParser` / `NotificationService` 均在工作区**未提交**；`frontend/src/features/im/` 下**无任何提及渲染改动** |
| 测试 | ❌ **零新增** | 门禁 `mvn -o test` = **1225**（与基线持平，一个数没涨）；无新增测试文件 |

### 致命缺陷：先截断、后过滤（本轮首要修复项）

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

**两个后果**：

1. **漏召回（致命）**：关键词命中的消息若不在第一页（例如第 11 条，`size=10`），`page=0` 时**永远搜不到**；翻到第 1 页也是从原表第 11–20 条里过滤，同样错。
2. **分页完全错误**：`total` ≤ `size`，`hasNext` 恒为 false，前端无法翻页。

> 这正是首轮任务书 §2 第 3 点点名要求修的 bug（原文指向 `searchCrossChannel` 的「先截断后过滤」）。执行方在旧处可能已处理，但在新写的 `searchMessages` 里**原样重犯**。

### 附带发现：前后端提及协议不一致（本轮必须一并解决）

- 后端格式（`RichTextParser.java:28`、`L98`）：`@{displayName}:userId`
  ```java
  MENTION_PATTERN = Pattern.compile("@\\{([^}]+)}:([a-f0-9-]+)");
  return "@{" + displayName + "}:" + userId;
  ```
- 前端现有渲染（`frontend/src/features/im/MessageList.tsx:70`）：
  ```ts
  content.split(/(@[A-Za-z0-9_\-]+)/)   // 只能匹配 @username 形态
  ```
  `@` 后紧跟 `{`，不属于 `[A-Za-z0-9_\-]`，`+` 要求至少 1 字符 → **该正则对 `@{...}:id` 完全匹配不上**，提及不会高亮。

---

## 二、返工任务

### T1（P0，阻塞上线）修「先截断后过滤」+ 真实 total

**现状**：见上文行号（`MessageSearchService.java` L53 / L66 / L67-75 / L77）。

**要求**：

1. `keyword`、`mentions`、时间范围**必须在查询侧完成**（JPQL 或 `JpaSpecificationExecutor` / `Specification`），**禁止**先 `PageRequest` 取一页再在 Java 流里过滤。
2. `total` 必须来自 count 查询（与过滤条件一致的 `count`），不得用 `results.size()`。
3. 分页必须能召回**第 2 页及以后**的匹配项。
4. 保留租户 + 频道成员过滤，防越权（非成员频道不得出现在结果中）。

**验收**：

- 单测：造 ≥ `size + 1` 条消息，让**唯一**匹配项落在第 2 页；`page=0&size=10` 搜索**必须命中**，且 `total = 1`。当前实现此项必挂（这是本 bug 的回归防线，必写）。
- 实测：容器内发消息后调 `/api/im/messages/search` 能命中，`total` 与实际匹配数一致。

**反向用例（必须写，缺一不可）**：

- 空关键词 / 全空白关键词 → 按接口约定返回 400（或明确的空结果语义，需在测试中固定）
- 越权频道（非成员 `channelId`）→ `data` 为空、`total = 0`
- 无匹配关键词 → `data: []`、`total: 0`，不得抛异常
- 时间范围无交集 → `total: 0`
- `page` 越界 / `size` 非法 → 400，不 500

---

### T2（P1）真正做搜索索引化

**现状**：首轮提交**没有迁移文件**；`ImMessageRepository#searchWithFilters` 现仅剩 `channelId` + `senderId` 两个条件，`keyword` 已不在 SQL 中 → 所谓"索引化"实际未发生，检索能力反而弱于改造前。

**要求**：

1. 新增 **`V43__im_message_search_index.sql`**（注意：`V42` 已被 `V42__im_message_mentions.sql` 占用，必须从 **V43** 起，勿改 V42 以免破坏已应用的迁移）：
   - `im_message` 增 `content_tsv tsvector` 列
   - 建 GIN 索引
   - 建触发器（`INSERT` / `UPDATE` 时自动维护 `content_tsv`）
   - 存量数据回填
2. 查询侧改为 `content_tsv @@ plainto_tsquery(:kw)`，按 `ts_rank` 排序；可选 `ts_headline` 高亮。
3. **必须保留 H2 降级路径**：单测跑 H2，不支持 `tsvector`，降级走 `ILIKE` 并**打日志**（沿用项目 Wiki FTS 的既有降级惯例）。降级不得打破既有 1225 个用例。
4. 保留租户 + 频道成员过滤（防越权）。

**验收**：给出 PostgreSQL 上的 **`EXPLAIN` 实证输出**，证明走索引（`Index Scan` / `Bitmap Index Scan`）而非 `Seq Scan`。**不接受"配置了应该就好了"。**

---

### T3（P0）@提及 / 富文本渲染管线收尾

**现状**：

- 已改但**未提交**（工作区）：`ImMessageEntity`（`mentions` TEXT 列）、`ImMessageDto`（`mentions` 字段）、`V42__im_message_mentions.sql`、`RichTextParser`、`NotificationService`、`MessageServiceTest`。
- 前端 `frontend/src/features/im/` 下**零改动**；且现有 `MessageList.tsx:70` 正则与后端 `@{displayName}:userId` 格式不匹配（见上文）。

**要求**：

1. **先自审**上述未提交改动（字段类型、JSON 解析、通知触发时机是否合理），确认无误后**提交入库**；不合规的先修再提。
2. 前端渲染：
   - `frontend/src/features/im/MessageList.tsx`（`renderContent`，L66 起）
   - `frontend/src/features/im/ThreadPanel.tsx`
   将 @提及渲染为**高亮 chip**（可点击/悬浮显示用户名），按后端格式 `@{displayName}:userId` 解析；**优先使用 DTO 的 `mentions` 字段**做兜底校验。
3. 通知：被 @ 的人必须触发通知（复用既有 `NotificationService`，不得新增并行通知通道）。

**验收**：

- 后端：提及解析单测（正常 / 多个提及 / 无提及 / 畸形格式不崩）。
- 前端：`MessageList` 渲染用例（提及高亮 + 普通文本不受影响）；`tsc --noEmit` 0 错误。
- 端到端：容器内发一条带 @ 的消息 → 被 @ 的人收到通知 → 前端消息列表显示高亮 chip（需给出实测输出/截图说明）。

---

### T4（P0）测试补齐

**现状**：门禁 1225 = 基线，零新增测试。

**要求**（每项都要有断言，不得只跑通不校验）：

1. 关键词命中（能搜到）
2. 无匹配 → `total: 0`
3. 越权频道 → 空
4. 提及检索（按 `mentionedBy` 过滤）
5. **`total` 正确性**（匹配总数，非本页条数）
6. **跨页召回回归**（T1 那条第 2 页命中用例）
7. 参数非法 → 400

---

## 三、门禁基线（执行方须 ≥ 且 0 失败）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | **≥ 1225** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | **≥ 275** passed |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | **≥ 64** passed |
| `pytest tests/` | **43 passed, 1 skipped** |

> 每项必须贴**实际命令输出**，不接受口头声称。

---

## 四、红线（违反即整批打回）

1. **严禁 stub 被测主路径**：不得让 `searchMessages` 直接返回空列表 / 固定值冒充命中。
2. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值 / 改断言期望值。
3. **严禁**提交 `.env` 或任何密钥明文。
4. **严禁**回滚已闭环提交（PHASE58 四项 P0、PHASE59 RabbitMQ/JWT、PHASE60 限流/定时工作流/移动端），严禁越界改动本单范围外的文件。
5. **每项修复必须给出实测输出**（容器内 curl / 测试输出 / EXPLAIN），不接受"配置了应该就好了"。
6. 新增迁移必须从 **V43** 起。

---

## 五、本项目教训（血泪，务必遵守）

1. **容器内 `localhost` 指向容器自身** —— 服务间地址必须 `@Value` 可配 + compose 服务名（已踩 5 次）。
2. **单测通过 ≠ 端到端生效** —— PHASE60 的 T3-1 就是典型（Limiter 单测全绿，但过滤器没进过滤器链，实测无 429）。本次 IM 搜索与 @提及**都必须在容器内实测**。
3. **Servlet 过滤器**必须在 `SecurityConfig` 用 `addFilterBefore/After` 注册（本项目无 `@Order` 先例）。
4. **Redis ZSET 滑动窗口 member 必须唯一**（时间戳 + `nanotime`），否则同一秒请求被去重、计数不到阈值。
5. **`tsvector` 改动必须有 H2 降级**，否则会打破既有 1225 个 Java 用例。
6. **分页语义**：先过滤再分页，永远不要"先取一页再过滤"（本轮就是栽在这）。

---

## 六、交付清单（执行方回报时必须给出）

1. 每项任务的**实测输出**（搜索命中 / total / 越权 / 提及通知 / 前端渲染）
2. `EXPLAIN` 证据（T2）
3. 五项门禁的**实际输出数字**
4. 改动文件清单 + `git log --oneline`（确认已提交，不要留未提交改动）
5. 明确说明哪些项未做及原因（不得"做了不说"——首轮任务三就是这样被漏判的）

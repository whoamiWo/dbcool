# Phase 61 任务书：IM 能力补齐（投喂 GLM-5.3）

**编排方**：CodeBuddy　**执行方**：GLM-5.3（Kilo Code）　**审计/验收方**：CodeBuddy
**基线提交**：`22a51eb`（PHASE 58/59/60 已全部闭环并推送）
**创建日期**：2026-09-30

> 本任务书每项「现状」由 CodeBuddy 于 2026-09-30 **实际读源码核实**（附文件与行号），可直接采信，
> **无需重新排查**。但未列出的签名/字段，动手前必须自己读代码确认。

---

## §0 背景：已完成的工作（严禁回滚）

| 提交 | 内容 |
|---|---|
| `3d06532` | [java] P0-3 `batch-upsert` + jsonb 序列化 |
| `6441307` | [python] P0-1 备份覆盖 Postgres 主库 |
| `17de634` | [frontend] P0-2 钉钉/企微 405 |
| `b3afd73` | [infra] P0-4 Huddle 粘滞 + compose 注入 POSTGRES_*/REDIS_* |
| `809eba5` | [infra] 全局排查 localhost（AI_PYTHON_URL） |
| `5d8203a` | [infra] RabbitMQ `SPRING_RABBITMQ_*` + 测试隔离 MQ + JWT 多算法 |
| `f43ba90` | [infra] 清理 EOF 残留 + 注入 AI_ENABLED |
| `de67970`/`d35ba68`/`46b2c1b` | PHASE60 T2（修 11 个失败）/ T3-1（限流）/ T3-2（定时工作流） |
| `059581c`/`99cf84c` | 限流真生效（SecurityConfig 注册 + ZSET member 唯一） |
| `e0ac7c9` | 移动端布局适配 |
| `e5dd3c9`/`22a51eb` | 限流阈值可配 / 补提文档 |

**门禁基线（执行前复跑，最终 ≥ 基线且 0 失败）**：

| 门禁 | 命令 | 基线 |
|---|---|---|
| 后端 | `cd backend-java && mvn -o test` | **1225 / 0 / 0 / 0** BUILD SUCCESS |
| 前端单测 | `cd frontend && npm run test:run` | **275 passed** |
| 前端类型 | `cd frontend && npx tsc --noEmit` | **0** |
| E2E | `cd frontend && npx playwright test` | **64 passed** |
| Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | **43 passed, 1 skipped** |

**当前迁移编号最大为 `V41`，新迁移从 `V42` 起。**

---

## §1 全局红线（违反即打回）

1. 严禁 `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改断言阈值。
2. 严禁回滚 §0 任何已完成修复。
3. 严禁臆造 API/字段名，先确认再写。
4. **严禁 stub 被测主路径函数**（如让搜索直接返回空列表冒充"命中"）。
5. 严禁 `catch` 后静默吞异常（至少要 `log.warn` 说明原因）。
6. **严禁提交 `.env`** 或密钥明文。
7. **严禁扩大范围**：只做任务一/二/三（§5 明确排除的不要碰）。
8. 每项必须有**实测输出**（容器内实测，非仅单测绿）。

### 本项目方法论（反复踩的坑，务必遵守）

1. **容器内 `localhost` 指向容器自身**；服务间地址必须 `@Value` 可配 + compose 服务名（已踩 5 次）。
2. **单测通过 ≠ 端到端生效**：PHASE60 的 T3-1 就是典型（Limiter 单测绿，但过滤器没进链、实测无 429）。
3. Servlet 过滤器必须在 `SecurityConfig` 用 `addFilterBefore/After` 注册（本项目**无 `@Order` 先例**）。
4. Redis ZSET 滑动窗口 member 必须唯一（时间戳+nanotime），否则同一秒请求被去重、计数不到阈值。
5. **H2 测试环境 Flyway 关闭**（`application.yml` L161 `flyway.enabled: false`），靠 `ddl-auto: create-drop`
   从 Entity 反推建表 → **新增 tsvector 列必须标 `@Transient`**，且**查询 SQL 必须两库都能跑**。

---

## §2 任务一：IM 搜索索引化（当前 LIKE 全表扫）

### 现状（已核实）

`backend-java/src/main/java/com/nocobase/im/ImMessageRepository.java`（134 行）—— 搜索相关**全部是 LIKE**：

| 行号 | 方法 | 匹配方式 |
|---|---|---|
| 39 | `search(channelId, kw, pageable)`（@Query 起 35） | L37 `lower(m.content) like lower(concat('%', :kw, '%'))` |
| 49 | `searchCrossChannel(...)`（@Query 起 44） | L46 同上 |
| 75 | `searchWithFilters(...)`（@Query 起 67） | L69 LIKE；L70 `m.content like '%@{%' \|\| :mentionedBy \|\| '}%'` |
| 93 | `countWithFilters(...)`（@Query 起 86） | L88/89 同上 |
| 107 | `countCrossChannel(...)`（@Query 起 103） | L105 LIKE |
| 119 | `findMentions(...)`（@Query 起 114） | L116 `m.content like '%@{%' \|\| :userId \|\| '}%'` |
| 131 | `countMentions(...)`（@Query 起 127） | L129 同上 |

补充事实：
- `V23:25` 已有 `idx_imessage_content_search(tenant_id, content)` 是 **btree**，对**前导通配符 `%kw%` 完全无效** → 必然 Seq Scan。
- `MessageService.search`（L114）与 `searchCrossChannel`（L128）被调用；其中 `searchCrossChannel`
  **L141-144 先按 limit 截断 50 条、再在 JVM 内存里过滤频道成员** → 结果数不准（分页语义错误）。

### 要求

1. **提升检索质量与性能**，二选一（说明理由）：
   - **A（推荐，沿用 Wiki 惯例）**：应用层分词（复用 `com.nocobase.search.ChineseSegmenter`，纯 Java 无需 PG 扩展）
     + JPQL `ILIKE` —— **H2 与 PostgreSQL 双库通用**（`WikiPageRepository` L63-69 就是这个范式）。
   - **B**：新增 `content_tsv` 列（V42 迁移，参考 `V20__wiki.sql` L82-90 + `V38`）+ GIN 索引 + 触发器，
     查询在 PG 下走 `to_tsquery`，**但必须保留 H2 的 ILIKE 回退**，且回退要打日志。
   > ⚠️ **严禁用「PG 原生 SQL + try/catch 吞异常降级为空」的写法**
   > （`UnifiedSearchService` 就是这么干的，代价是 H2 下功能为 0、测试覆盖不到）。
2. 若选 B：新列必须照抄 `WikiPageEntity` L69-71 的三注解组合：
   ```java
   @Column(name = "content_tsv", columnDefinition = "TSVECTOR", insertable = false, updatable = false)
   @JdbcTypeCode(SqlTypes.OTHER)
   @Transient
   private String contentTsv;
   ```
3. **必须修 `searchCrossChannel` 的先截断后过滤**（L141-144）：改为在查询侧按成员过滤，或先取足量再过滤并返回真实 total。
4. **保留租户 + 频道成员过滤**（防越权，这是硬要求）。
5. 迁移脚本从 **V42** 起，幂等（`ADD COLUMN IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`）。

### 验收
- `mvn -o test` ≥ 1225 且 0 失败
- **在容器内实测**：发一条测试消息 → 搜索能命中（贴命令与响应）
- 若选 B：贴 PostgreSQL 上 `EXPLAIN` 证据（改造后走索引 / 或至少给出中文分词召回提升的前后对比）
- 反向用例：越权频道搜不到（403/空）、空关键词、超长关键词

---

## §3 任务二：`MessageSearchService` 接线（当前死代码）

### 现状（已核实）

`backend-java/src/main/java/com/nocobase/im/MessageSearchService.java`（144 行）说明：
- 类 **L43**（`@Service` 41、`@Transactional(readOnly=true)` 42）；构造器 **L47**
- public 方法：`searchMessages(...)` **L64**、 `searchMentions(...)` **L95**、 `getPreview(...)` **L120**

**grep 证据（0 注入点、0 调用点）**：在 `backend-java/src/main` 搜 `MessageSearchService` 仅 3 处命中，
全部是自身定义（L43）、构造器（L47）与 `RichTextParser` L20 的**注释**；`src/test` 搜 **0 命中**。
其下游 `searchWithFilters`/`countWithFilters`/`countCrossChannel`/`findMentions`/`countMentions` 同样只被它调用 → **整条链路约 100 行是死代码**。

`ImMessageController.java`（`@RequestMapping("/api/im/messages")` L32）：
- 构造器 **L40** 仅注入 `MessageService` / `ReactionService` / `PinService` / `ApplicationEventPublisher`
- 搜索端点：L177 `@GetMapping("/search")` → L186 `messageService.search(...)`；
  L196 `@GetMapping("/search/cross")` → L203 `messageService.searchCrossChannel(...)`
- **Controller 完全没有引用 `MessageSearchService`**，高级能力（多维度过滤、提及检索、total、高亮预览）**一个都没暴露成 HTTP 接口**。

### 已知缺陷（接真前必须先修）

1. `MessageSearchService.searchMentions` L104/L107 调用 `findMentions(null, ...)` 与 `countMentions(null, ...)`
   —— **tenantId 硬传 null**，而 JPQL 里 `m.tenantId = :tenantId` 无 null 兜底 → 即便接真也恒返回空。
2. `searchCrossChannel` 的先截断后过滤（见任务一第 3 点）。

### 要求

1. 把 `MessageSearchService` 接入 `ImMessageController`（或收敛进 `MessageService`），
   让搜索走它 —— **职责收敛**：不要让 `MessageService#search` 与 `MessageSearchService` 两份实现长期并存，
   旧实现要么删除、要么明确降级为内部方法（说明理由）。
2. **暴露高级搜索能力**为 HTTP 端点（至少支持：关键词 + 提及我 + 发送者 + 时间范围，带 total 与分页）。
3. **修 `tenantId = null`**（L104/L107）与**先截断后过滤**（L141-144）。
4. `getPreview` 的高亮（L139 `replaceAll` 拼 `<strong>`）若暴露到前端，必须防 XSS（转义关键词）；
   否则不要在后端拼 HTML，改为返回纯文本 + 前端高亮（推荐）。
5. 补测试（后端）：命中、空结果、越权频道返回空、提及检索、total 正确、参数非法 400。

### 验收
- `mvn -o test` > 1225 且 0 失败
- **grep 证明 `MessageSearchService` 真被注入调用**（贴 grep 结果：Controller/Service 中出现注入点）
- 容器内实测：高级搜索端点能按条件过滤并返回真实 total（贴响应）

---

## §4 任务三：@提及 / 富文本渲染管线（当前未接入）

### 现状（已核实）—— **三套提及格式互不兼容，这是核心矛盾**

| 位置 | 格式 | 行号 |
|---|---|---|
| `RichTextParser`（静态工具类，**L22 无 @Component 不是 Bean**） | `@{displayName}:userId` | 正则 **L24**；`parse` L52；`formatMention` L93；`extractMentionUserIds` L113 |
| `MentionParser`（`@Component` **L27 但 0 注入点，孤儿 Bean**） | `@user` / `@channel` / `@here` | 正则 **L32-33**；`parse` L39（L50 吞异常降级空） |
| `ImMessageRepository` 的 LIKE 过滤 | `'%@{%' \|\| userId \|\| '}%'`（把 userId 塞进 `{}` 内，与上面两种都**对不上**） | L70/L89/L116/L129 |
| 前端 `MessageList.tsx` | `@[A-Za-z0-9_-]+`（**只认 ASCII，识别不了中文名，也与后端格式不匹配**） | `renderContent` **L66**，split **L70**；调用点 L278 |

其他关键事实：
- `RichTextParser` **L19** 自述「该类暂不接入生产代码（@提及链路将在后续迭代）」；0 调用点。
- `MessageService.send` **L59**（`@Transactional` L58）：L77 `setContent(body)` **原样落库**，
  **不解析提及、不归一化、不提取 mentions**；`edit` **L88**（L91 同样不重算）；**无通知触发**。
- `ImMessageEntity`（79 行，11 字段）**无 mentions、无 content_tsv**。
- `ImMessageDto`（record L13-23）**无 mentions 字段** → 后端算出提及也带不出去；
  而前端 `frontend/src/features/im/api.ts` **L185** 已单方面声明 `mentions?: string[]`（恒为 undefined）。
- `ThreadPanel.tsx`（204 行）：原消息渲染 **L103**、回复渲染 **L138** —— **纯文本，未走 renderContent，无提及高亮**。
- `SearchResults.tsx`：L69-71 用 `dangerouslySetInnerHTML` 高亮，且 **L70 `<mark>` 前景背景同色（实际不可读）** → 可顺手修。

### 要求

1. **先选定一种提及格式并全链路统一**（推荐 `@{displayName}:userId`，即 `RichTextParser` 的格式，因其已带 userId 便于通知与检索）。
   - 统一：后端解析、Repository 过滤、DTO 输出、前端渲染 —— 四处必须一致。
   - 旧格式（`MentionParser` 的 `@user/@channel/@here`、`MessageList.tsx` 的正则）要么兼容解析，要么明确废弃（说明废弃理由与影响面）。
2. **写路径**：`MessageService.send`（L59）落库前解析提及并持久化 mentions（新列 + V42/V43 迁移，或存结构化字段）；`edit`（L88）编辑后**必须重算**提及。
3. **读路径**：`ImMessageDto` 增加 `mentions` 字段（对齐前端 `api.ts` L185），使 WebSocket 广播与 REST 都能带出。
4. **通知**：被 @ 的人触发通知（复用既有 `NotificationService`），**不许静默失败**。
5. **前端**：消息列表与线程面板渲染 @提及为高亮 chip（可点击），`ThreadPanel` 也要支持（不能只改 MessageList）。
6. 补测试：
   - 后端：提及解析（含中文名、多个提及、边界）、无提及、非法格式容错
   - 后端：发送带 @ 的消息后 mentions 正确落库 + DTO 带出 + 通知被触发
   - 前端：提及渲染用例（`MessageList` 与 `ThreadPanel` 各一条）

### 验收
- `mvn -o test` > 1225、`npm run test:run` > 275、`tsc` 0、`playwright` ≥ 64
- **容器内端到端实测**：发一条 `@{某人}:userId` 的消息 → 后端能解析、DTO 带 mentions、
  被 @ 人收到通知、前端渲染为高亮（贴命令/响应/截图说明）
- 证明三套格式已统一（贴关键代码位置）

---

## §5 明确不在本轮范围（严禁触碰）

- **IM 已真接真、不要动**：频道、私信、线程回复、Reaction、已读回执、附件（MinIO）、
  斜杠命令、Pin、在线状态、**Huddle 语音**（PHASE59 P0-4 已用 K8s cookie 粘滞解决多副本）。
- 不做：Slack/Mattermost 入站消费者、集成市场 UI、钉钉组织架构接真。
- 不做：字段类型扩展、视图 group by、日历翻月、虚拟滚动。
- 不做：压测容量基线、死代码清理、两套看板合并、crdt-service Dockerfile、CRDT 字符级协同。

---

## §6 交付自检清单

**任务一 搜索**
- [ ] LIKE 已替换/降级路径明确（H2 可跑，不吞异常）
- [ ] `searchCrossChannel` 先截断后过滤已修（total 真实）
- [ ] 租户 + 频道成员过滤保留（防越权）
- [ ] 反向用例：越权空、空关键词、超长关键词
- [ ] 容器内实测命中 +（选 B 时）EXPLAIN 证据

**任务二 接线**
- [ ] `MessageSearchService` 真被注入（grep 证据）
- [ ] 高级搜索端点暴露（多条件 + total + 分页）
- [ ] `tenantId=null` 已修
- [ ] 高亮不引入 XSS（或改前端高亮）
- [ ] 后端测试齐全

**任务三 @提及**
- [ ] 三套格式已统一（说明选定格式与废弃项）
- [ ] `send`/`edit` 都解析并持久化 mentions
- [ ] `ImMessageDto` 带 mentions（对齐前端 api.ts L185）
- [ ] 通知触发被 @ 人（非静默）
- [ ] 前端 `MessageList` + `ThreadPanel` 都渲染高亮
- [ ] 后端 + 前端测试齐全

**门禁与红线**
- [ ] `mvn -o test` > 1225 且 0 失败
- [ ] `npm run test:run` > 275、`tsc` 0、`playwright` ≥ 64
- [ ] Python 43 passed / 1 skipped（未被影响）
- [ ] 新增 skip 0 / 删测试 0 / 弱化断言 0
- [ ] 未提交 `.env`
- [ ] 无 §5 之外的越界改动

---

## §7 提交规范

按栈分开提交，信息写清「现状 → 改动 → 实测数字」：
`[java]`（搜索 + 接线 + 提及后端 + V42/V43 迁移）、`[frontend]`（提及渲染）。
每提交一次都跑对应门禁。

---

## §8 回报要求（缺一即打回）

1. 三项逐项勾选（未完成写"未做"及原因）
2. 任务一：容器内搜索命中实测输出 +（选 B）EXPLAIN
3. 任务二：`MessageSearchService` 注入点 grep 证据 + 高级搜索端点响应
4. 任务三：提及格式统一说明 + 端到端实测（发消息 → 解析 → DTO → 通知 → 渲染）
5. 全部门禁数字（与基线对比）

---

## §9 审计口径（提前告知）

1. 复跑全部门禁
2. **亲自在容器内**：发消息 → 搜索命中（验证走新链路，非 LIKE 空转）
3. grep 确认 `MessageSearchService` 真被调用（不是又一个死代码）
4. 亲自发一条带 @ 的消息，确认后端解析、DTO 带 mentions、通知触发、前端高亮
5. 检查 H2 下所有搜索用例仍能跑通（防止破坏 1225 基线）
6. 反作弊（skip/删测试/弱化断言）与范围检查（§5 越界一律打回）

一句话：**IM 这三项都要在真实消息流里看到效果，不是在单测里看到绿。**

# Phase 61 投喂提示词：IM 能力补齐（投喂 GLM-5.3）

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3（Kilo Code）**
> 基线提交：`22a51eb`（PHASE 58/59/60 已闭环并推送）
> 创建日期：2026-09-30
> **用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行**
> 详细规格（含文件行号证据）见同目录 `PHASE61_GLM53_IM_TASKS.md`

---

## 背景

PHASE 58（4 项 P0）、59（RabbitMQ + 跨服务 JWT）、60（限流/定时工作流/移动端/修 11 个失败）均已闭环。
本轮做 **IM 能力补齐**三项：搜索索引化、`MessageSearchService` 接线、@提及与富文本渲染。

---

## 投喂提示词（以下整段复制）

```
请执行 Phase 61 任务：IM 能力补齐。基线 origin/main = 22a51eb。
本轮三项：① IM 搜索索引化（当前 LIKE 全表扫）② MessageSearchService 接线（当前死代码）
③ @提及 / 富文本渲染管线（RichTextParser 未接入）。

执行前必读：同目录 PHASE61_GLM53_IM_TASKS.md（每项含文件行号证据）。冲突时以任务书为准。

============================================================
§0 严禁回滚 / 严禁越界
============================================================
已闭环提交：3d06532、6441307、17de634、b3afd73、809eba5、5d8203a、f43ba90、
de67970、d35ba68、46b2c1b、059581c、99cf84c、e0ac7c9、e5dd3c9、22a51eb —— 不要回滚。

IM 中**已真接真、不要动**：频道、私信、线程回复、Reaction、已读回执、附件（MinIO）、
斜杠命令、Pin、在线状态、**Huddle 语音**（P0-4 已用 K8s cookie 粘滞解决多副本）。

不做：Slack/Mattermost 入站、集成市场 UI、钉钉组织架构接真、字段类型扩展、
视图 group by、日历翻月、虚拟滚动、压测基线、死代码清理、两套看板合并、
crdt-service Dockerfile、CRDT 字符级协同。

门禁基线（执行前复跑，最终须 ≥ 基线且 0 失败）：
  mvn -o test = 1225/0/0/0 BUILD SUCCESS ｜ npm run test:run = 275 ｜ tsc = 0
  playwright = 64 ｜ cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q = 43 passed, 1 skipped
迁移编号最大为 V41 → 新迁移从 **V42** 起。

============================================================
§1 红线（违反即打回）
============================================================
1. 严禁 it.skip / @Disabled / 删测试 / 弱化断言 / 改断言阈值。
2. 严禁 stub 被测主路径函数（如让搜索直接返回空列表冒充命中）。
3. 严禁 catch 后静默吞异常（至少 log.warn 说明原因）。
4. 严禁提交 .env 或密钥明文。
5. **严禁扩大范围**：只做三项任务。
6. 每项必须有容器内实测输出，不接受「配置了应该就好了」。

本项目方法论（已踩多次）：
- 容器内 localhost 指向容器自身；服务间地址必须 @Value 可配 + compose 服务名。
- **单测通过 ≠ 端到端生效**（PHASE60 的 T3-1 就是典型：Limiter 单测绿，但过滤器没进链、实测无 429）。
- Servlet 过滤器必须在 SecurityConfig 用 addFilterBefore/After（本项目无 @Order 先例）。
- **H2 测试环境 Flyway 关闭**（application.yml L161），靠 ddl-auto: create-drop 从 Entity 建表
  → 新增 tsvector 列必须标 @Transient，且查询 SQL 必须 H2 与 PG 都能跑。

============================================================
§2 任务一：IM 搜索索引化（当前 LIKE 全表扫）
============================================================
现状（已核实）：ImMessageRepository.java（134 行），搜索方法**全部 LIKE**：
  L39 search（@Query 起 L35）      → L37 lower(m.content) like lower(concat('%', :kw, '%'))
  L49 searchCrossChannel（起 L44） → L46 同上
  L75 searchWithFilters（起 L67）  → L69 LIKE；L70 m.content like '%@{%' || :mentionedBy || '}%'
  L93 countWithFilters（起 L86）   → L88/89 同上
  L107 countCrossChannel（起 L103）→ L105 LIKE
  L119 findMentions（起 L114）     → L116 m.content like '%@{%' || :userId || '}%'
  L131 countMentions（起 L127）    → L129 同上
- V23:25 的 idx_imessage_content_search(tenant_id, content) 是 btree，
  对**前导通配符 %kw% 完全无效** → 必然 Seq Scan
- MessageService.search L114、searchCrossChannel L128；
  其中 **searchCrossChannel L141-144 先按 limit 截断 50 条、再在 JVM 内存过滤频道成员**
  → 结果数不准（分页语义错误），必须修

要求：
1. 提升检索质量与性能，二选一（说明理由）：
   A（推荐，沿用 Wiki 惯例）：应用层分词（复用 com.nocobase.search.ChineseSegmenter，
     纯 Java 无需 PG 扩展）+ JPQL **ILIKE** —— H2 与 PG 双库通用（WikiPageRepository L63-69 范式）。
   B：新增 content_tsv 列（V42，参考 V20__wiki.sql L82-90 + V38）+ GIN + 触发器，
     PG 下走 to_tsquery，**但必须保留 H2 的 ILIKE 回退并打日志**。
   ⚠️ **严禁「PG 原生 SQL + try/catch 吞异常降级为空」**（UnifiedSearchService 就是这个反例，
      代价是 H2 下功能为 0、测试覆盖不到）。
2. 若选 B，新列必须照抄 WikiPageEntity L69-71 的三注解组合：
   @Column(name="content_tsv", columnDefinition="TSVECTOR", insertable=false, updatable=false)
   @JdbcTypeCode(SqlTypes.OTHER) @Transient
3. 必须修 searchCrossChannel 的先截断后过滤（L141-144）：查询侧按成员过滤，或取足量后过滤并返回真实 total。
4. 保留租户 + 频道成员过滤（防越权，硬要求）。
5. 迁移从 V42 起，幂等（ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS）。

验收：mvn ≥1225 且 0 失败；**容器内实测**发消息→搜索命中（贴命令与响应）；
选 B 时贴 PG 的 EXPLAIN；反向用例：越权频道空、空关键词、超长关键词。

============================================================
§3 任务二：MessageSearchService 接线（当前死代码）
============================================================
现状（已核实）：MessageSearchService.java（144 行）
  类 L43、构造器 L47；public 方法 searchMessages L64、searchMentions L95、getPreview L120
  **grep 证据**：src/main 搜 MessageSearchService 仅 3 处命中（自身 L43/L47 + RichTextParser L20 注释），
  src/test **0 命中** → 0 注入点、0 调用点；其下游 searchWithFilters/countWithFilters/
  countCrossChannel/findMentions/countMentions 整条约 100 行同样是死代码。

ImMessageController.java（@RequestMapping("/api/im/messages"）L32）：
  构造器 L40 仅注入 MessageService/ReactionService/PinService/ApplicationEventPublisher
  L177 @GetMapping("/search") → L186 messageService.search(...)
  L196 @GetMapping("/search/cross") → L203 messageService.searchCrossChannel(...)
  → **Controller 完全没引用 MessageSearchService**，高级能力（多维度过滤、提及检索、
    total、高亮预览）一个都没暴露成 HTTP 接口。

已知缺陷（接真前必须先修）：
1. searchMentions L104/L107 传 **tenantId = null**，而 JPQL 里 m.tenantId = :tenantId
   无 null 兜底 → 即便接真也恒返回空。
2. searchCrossChannel 的先截断后过滤（L141-144）。

要求：
1. 把 MessageSearchService 接入 ImMessageController 或收敛进 MessageService，让搜索走它；
   **职责收敛**：不要让 MessageService#search 与 MessageSearchService 两份实现长期并存
   （旧实现删除或明确降级为内部方法，说明理由）。
2. 暴露高级搜索端点（至少：关键词 + 提及我 + 发送者 + 时间范围，带 total 与分页）。
3. 修 tenantId=null 与先截断后过滤。
4. getPreview（L139 用 replaceAll 拼 <strong>）若暴露前端必须防 XSS；
   否则改为返回纯文本 + 前端高亮（推荐）。
5. 补后端测试：命中、空结果、越权频道空、提及检索、total 正确、参数非法 400。

验收：mvn >1225 且 0 失败；**贴 grep 证明 MessageSearchService 真被注入调用**；
容器内实测高级搜索端点按条件过滤并返回真实 total（贴响应）。

============================================================
§4 任务三：@提及 / 富文本渲染管线（三套格式互不兼容）
============================================================
现状（已核实）——**核心矛盾：三套提及格式互不兼容**：
  RichTextParser（L22，**无 @Component 不是 Bean**）→ `@{displayName}:userId`（正则 L24；
    parse L52；formatMention L93；extractMentionUserIds L113）
  MentionParser（L28，**@Component L27 但 0 注入点，孤儿 Bean**）→ `@user/@channel/@here`
    （正则 L32-33；parse L39，L50 吞异常降级空）
  ImMessageRepository 的 LIKE → `'%@{%' || userId || '}%'`（userId 塞进 {} 内，与上面两种都**对不上**）
    （L70/L89/L116/L129）
  前端 MessageList.tsx → `@[A-Za-z0-9_-]+`（**只认 ASCII，识别不了中文名，与后端都不匹配**）
    （renderContent L66，split L70，调用点 L278）

其他关键事实：
- RichTextParser **L19** 自述「该类暂不接入生产代码」；0 调用点。
- **MessageService.send L59**（@Transactional L58）：L77 setContent(body) **原样落库**，
  不解析提及、不归一化、不提取 mentions；**edit L88**（L91 同样不重算）；**无通知触发**。
- ImMessageEntity（79 行，11 字段）**无 mentions、无 content_tsv**。
- ImMessageDto（record L13-23）**无 mentions 字段** → 后端算出提及也带不出去；
  前端 api.ts **L185** 已单方面声明 `mentions?: string[]`（恒 undefined）。
- ThreadPanel.tsx（204 行）：原消息渲染 **L103**、回复渲染 **L138** —— 纯文本，
  **未走 renderContent，无提及高亮**。
- SearchResults.tsx：L69-71 用 dangerouslySetInnerHTML 高亮，且 **L70 <mark> 前景背景同色（不可读）** → 可顺手修。

要求：
1. **先选定一种提及格式并全链路统一**（推荐 `@{displayName}:userId`，即 RichTextParser 格式，
   因其已带 userId 便于通知与检索）。统一四处：后端解析、Repository 过滤、DTO 输出、前端渲染。
   旧格式（MentionParser 的 @user/@channel/@here、MessageList.tsx 正则）要么兼容解析，
   要么明确废弃（说明废弃理由与影响面）。
2. 写路径：MessageService.send（L59）落库前解析提及并持久化 mentions（新列 + V42/V43 迁移）；
   edit（L88）编辑后**必须重算**。
3. 读路径：ImMessageDto 增加 mentions 字段（对齐前端 api.ts L185），
   使 WebSocket 广播与 REST 都能带出。
4. 通知：被 @ 的人触发通知（复用既有 NotificationService），**不许静默失败**。
5. 前端：消息列表**与线程面板**都渲染 @提及为高亮 chip（可点击）—— 不能只改 MessageList。
6. 补测试：后端提及解析（中文名/多个/边界/非法容错）+ 发送带 @ 后 mentions 落库 +
   DTO 带出 + 通知触发；前端 MessageList 与 ThreadPanel 各一条渲染用例。

验收：mvn >1225、npm run test:run >275、tsc 0、playwright ≥64；
**容器内端到端实测**：发 `@{某人}:userId` 消息 → 后端解析 → DTO 带 mentions → 被 @ 人收到通知 →
前端高亮（贴命令/响应/渲染说明）；并证明三套格式已统一（贴关键代码位置）。

============================================================
§5 环境与命令
============================================================
- Java 镜像必须离线重建（主 Dockerfile 拉 eclipse-temurin 极慢，禁用）：
    cd backend-java && mvn -o package -DskipTests
    docker build -f Dockerfile.offline -t nocobase-backend-java:latest .
    cd .. && docker compose up -d --no-build backend-java
- Python：docker compose up -d --no-build backend-python
- 门禁：
    cd backend-java && mvn -o test
    cd frontend && npm run test:run && npx tsc --noEmit && npx playwright test
    cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q
- 登录取 token：
    TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
      -d '{"username":"admin","password":"admin123"}' \
      | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")

============================================================
§6 提交与回报
============================================================
按栈分开提交：[java]（搜索 + 接线 + 提及后端 + V42/V43 迁移）、[frontend]（提及渲染）。
信息写清「现状 → 改动 → 实测数字」，每次提交跑对应门禁。严禁提交 .env。

回报必须包含：
1. 三项逐项勾选（未完成写"未做"及原因）
2. 任务一：容器内搜索命中实测 +（选 B）EXPLAIN
3. 任务二：MessageSearchService 注入点 grep 证据 + 高级搜索端点响应
4. 任务三：提及格式统一说明 + 端到端实测（发消息 → 解析 → DTO → 通知 → 渲染）
5. 全部门禁数字（与基线对比）

============================================================
§7 审计口径
============================================================
① 复跑全部门禁；② 亲自在容器内发消息后搜索，确认命中（走新链路非 LIKE 空转）；
③ grep 确认 MessageSearchService 真被调用（不是又一个死代码）；
④ 亲自发带 @ 的消息，确认解析 → DTO 带 mentions → 通知 → 前端高亮；
⑤ 检查 H2 下所有搜索用例仍跑得通（防止破坏 1225 基线）；
⑥ 反作弊与范围检查（越界一律打回）。

一句话：**IM 这三项都要在真实消息流里看到效果，不是在单测里看到绿。**
```

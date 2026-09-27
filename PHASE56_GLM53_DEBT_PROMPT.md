# Phase 56 任务书：清偿全部剩余技术债务（投喂 GLM-5.3）

**编排方**：CodeBuddy (HY4)　**执行方**：GLM-5.3　**验收方**：CodeBuddy
**基线提交**：`dba9266`（已推送，远端对齐）
**创建日期**：2026-09-27

> 本任务书列出的每一项"现状"均由 CodeBuddy 于 2026-09-27 **实际读取源码核实**（附文件与行号），
> 你可直接使用，无需重新审计。**但未核实的 API 签名、端点路径仍须自己读代码确认后再写**。

---

## §0 背景：已完成的工作（严禁回滚）

以下均已 commit 并推送，**禁止删除、回退、弱化**：

| 提交 | 内容 |
|---|---|
| `e0e2c90` / `01a6f47` | P0：IM 消息跨租户越权修复（`ImMessageController` list/thread/search 归属校验 + 反向用例） |
| `ee91b87` | `workflow_tasks` 跨租户越权（加 `tenant_id` + V37 迁移） |
| P1 审计 | `CollectionController.getJob` 401/403；`WikiVersion` 已核实安全（Controller 层校验） |
| `8a42d3b` | P0 可观测性：K8s 编排（`k8s/` 24 资源）+ Prometheus + 健康探针 + 补 RabbitMQ |
| `edd47e8`/`a24945d` | 备份恢复演练脚本 |
| `00307d7` | k6 压测脚本（`perf/load-test.js`）本地验证 |
| `b34ef43` | 限流改 Redis-backed（`TriggerRateLimiter` ZSET 滑动窗口 + 内存回退） |
| `dba9266` | 上线报告同步 |

**多租户方法论（务必遵守）**：本项目租户防御在 **Service/Controller 层**，不在 Repository 层。
**禁止**仅因"Repository 无 tenant 字段"就加冗余列 + 迁移 —— CodeBuddy 曾在 `WikiVersion` 上这样误判过。

---

## §1 全局红线

1. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改断言阈值。门禁**只增不减**。
2. **严禁**回滚 §0 任何已完成修复。
3. **严禁臆造 API**：任何方法名、端点路径、字段名，必须先 `grep`/读源码确认存在再使用。
   （CodeBuddy 曾臆造 `advanceToNextApprovalNode` 方法名，导致编译失败，已删除该用例。）
4. **改方法签名必须同步既有测试**，否则编译失败（`CollectionController.getJob` 那轮已踩过）。
5. 每个新增/修复的端点必须配**反向用例**（越权→403、参数非法→400、不存在→404）。
6. 新增依赖若本地 `.m2`/`node_modules` 未缓存，**先联网下载**（aliyun 仓库可用），
   下载后须验证 `mvn -o`（offline）也能构建 —— 参照 `micrometer-registry-prometheus` 的处理方式。

### 门禁（执行前先实测基线，只能增不能减）

| 门禁 | 命令 | 上次实测参考值 |
|---|---|---|
| 后端 | `cd backend-java && mvn -o test` | 1293 / 0 / 0 |
| 前端单测 | `cd frontend && npm run test:run` | 309 passed / 49 files |
| 前端类型 | `cd frontend && npx tsc --noEmit` | 0 errors |
| E2E | `cd frontend && npx playwright test` | 132 passed |

> ⚠️ 前端**必须用 `npm run test:run`**，不要用 `npx vitest run`（会进 watch 模式卡住）。
> 上表为参考值，**以你执行前实测为准**，最终交付须 ≥ 基线且 0 失败。

---

## §2 P1-1：日志采集（Loki + 结构化日志）　**优先级最高**

### 现状（已核实）
- `backend-java/src/main/resources/application.yml` **L136-142** 仅有 console 纯文本 pattern：
  ```
  logging:
    level: { root: INFO, com.nocobase: DEBUG }
    pattern:
      console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
  ```
- **无** `logback-spring.xml`（`backend-java/src/main/resources/` 下不存在）
- **无** JSON 结构化输出、**无** traceId/tenantId 关联、**无** 采集器配置
- K8s 栈已有 Prometheus + Grafana（`k8s/06-monitoring.yaml`）

### 要求
1. 新增 `backend-java/src/main/resources/logback-spring.xml`：
   - **生产 profile（非 test）**用 JSON encoder 输出到 stdout（K8s 标准做法）
     —— 依赖 `net.logstash.logback:logstash-logback-encoder`（需联网下载，下载后验证 offline 可构建）
   - **test profile 保持纯文本**，避免破坏既有日志断言（若有测试断言日志格式，必须同步）
   - 字段须包含：`timestamp / level / logger / thread / message / traceId / spanId / tenantId / userId`
     （`traceId`/`spanId` 由 §3 的 tracing 通过 MDC 自动注入；`tenantId` 需自己用 MDC Filter 注入）
   - 异常堆栈必须完整输出
2. 新增 MDC Filter（`com.nocobase.config` 包，参考既有 `JwtAuthFilter` 写法）：
   从 `AuthenticatedUser` 取 `tenantId()`/`userId()` 写入 MDC，请求结束清理（**必须 finally 清理，防线程复用串号**）
3. K8s 采集：新增 `k8s/07-logging.yaml`
   - **推荐 Loki**（与现有 Grafana 同源，比 EFK 轻量）。若你判断 EFK 更合适，须说明理由再实施
   - 采集器用 **Fluent Bit**（DaemonSet）→ Loki；或用 Grafana Alloy
   - 容器日志打 `app` 标签便于按服务过滤
4. 在 `k8s/README.md` 增补"日志"章节：查询示例（LogQL）、如何按 traceId 串联日志。

### 验收
- `mvn -o test` 全绿（≥ 基线）
- 启 profile=docker 时日志为 JSON（可用 `grep` 配置或本地启动验证，说明验证方式）
- YAML 全部 `python3 -c "import yaml; yaml.safe_load_all(...)"` 通过

---

## §3 P1-2：分布式追踪（OpenTelemetry + Jaeger）

### 现状（已核实）
- `backend-java/pom.xml` 中**无任何** `opentelemetry` / `micrometer-tracing` / `sleuth` / `jaeger` 依赖（grep 结果为 0）

### 要求
1. `pom.xml` 增加（Spring Boot 3 标准组合，均需联网下载后验证 offline 构建）：
   - `io.micrometer:micrometer-tracing-bridge-otel`
   - `io.opentelemetry:opentelemetry-exporter-otlp`
   （可选 `micrometer-tracing-bridge-brave`，但统一用 **otel** 桥）
2. `application.yml` 配置：
   - `management.tracing.sampling.probability`：生产建议 `0.1`（10% 采样，避免全量压垮 Jaeger），dev 可 `1.0`
   - OTLP exporter endpoint 走环境变量（如 `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces`）
   - 确保 traceId 注入日志（与 §2 的 JSON 日志 `traceId` 字段联动）
3. K8s：新增 Jaeger（或 `jaeger-all-in-one`）Deployment + Service，补到 `k8s/`（可并入 `06-monitoring.yaml` 或新增 `08-tracing.yaml`）
4. 关键路径加自定义 span（可选但推荐）：MQ 异步任务发布/消费、Feishu/DingTalk/WeCom 外部调用、Workflow 执行
5. `k8s/README.md` 增补"追踪"章节：如何按 traceId 查调用链。

### 验收
- `mvn -o test` 全绿
- 与 §2 的日志 traceId 一致（同一请求日志与追踪可串联）
- YAML 校验通过

---

## §4 P2-1：i18n 国际化（前端为主）

### 现状（已核实）
- `frontend/package.json` **无任何 i18n 库**（无 `react-i18next` / `i18next` / `vue-i18n`）
- UI 文案全部硬编码中文

### 要求
1. 引入 `i18next` + `react-i18next`（`npm install`，可能需联网）
2. 建立 `frontend/src/i18n/`：
   - `index.ts`（初始化，从 localStorage 或 `navigator.language` 探测语言）
   - `locales/zh-CN.json`（**中文为默认，把现有硬编码文案原样迁移，禁止机翻乱改**）
   - `locales/en-US.json`（英文翻译）
3. 加语言切换器（放进 `AppLayout` 顶部），切换后持久化
4. **渐进范围**（全量替换 100+ 文件不现实，先做核心）：
   优先替换：`Login` / `AppLayout`（导航菜单） / `Home` / IM 模块（`MessageList`/`ChannelList`/`MessageComposer`）
   / 项目模块（`ProjectPage`/`TaskBoard`）。其余页面保留中文，但**必须不报错**（未翻译的 key 回退中文）
5. 后端错误消息国际化（**可选，量力而行**）：若做，用 `Accept-Language` + `MessageSource`；
   注意错误消息改动可能影响既有测试断言（如 `assertEquals("xxx", message)`），须同步

### 验收
- `npm run test:run` 全绿、`npx tsc --noEmit` 0 error、E2E 全绿
- 语言切换生效且刷新后保持
- **E2E 注意**：若测试断言了中文文案，切换默认语言为中文可保持兼容 —— 默认语言必须是 **zh-CN**

---

## §5 P2-2：Trello 看板前端接线

### 现状（已核实）
- `frontend/src/features/project/BoardView.tsx`（L24 `export function BoardView`）、`BoardColumn.tsx` **存在**
- **但全项目 grep `BoardView` 仅命中自身定义文件** → 无任何页面 import，**确认是坏死代码**
- 后端已就绪：`ProjectBoardController`（列 CRUD、卡片移动、Checklist、Label），已有 17 个用例覆盖

### 要求
1. **先读 `backend-java/src/main/java/com/nocobase/project/ProjectBoardController.java`**
   确认真实端点路径与方法（**禁止凭想象写 URL**），再读 `frontend/src/api/` 下对应 api 封装是否存在
2. 把 `BoardView` 接入 `ProjectPage.tsx`（或项目详情页的视图切换器，参考 `TaskBoard`/`GanttView` 的接入方式）
3. 拖拽：项目已有 `@dnd-kit/core` + `@dnd-kit/sortable`（`package.json` L22-24），用它实现
   - 卡片跨列拖拽 → 调用后端"移动卡片"端点
   - 列排序 → 调用后端列更新端点
4. 数据用 `@tanstack/react-query`（已在依赖）拉取，带 loading / error 态
5. 补前端单测（`BoardView.test.tsx`）：渲染列与卡片、拖拽后调用移动 API（mock `api` 层）

### 验收
- `npm run test:run` / `tsc --noEmit` / E2E 全绿
- 页面上真实可见看板并能拖动（如 E2E 可覆盖则加一条，不可则手工说明验证方式）

---

## §6 P2-3：FTS 中文分词（**禁止走编译扩展路线**）

### 现状（已核实）
- `backend-java/src/main/java/com/nocobase/wiki/WikiPageRepository.java`：
  - **L59-69** `searchByContent`、L71-81 `searchByContentAndKb`
  - 注释虽写"PostgreSQL 使用 to_tsvector + plainto_tsquery"，**实际实现是 `ILIKE %:query%`**（为兼容 H2 测试）
  - 即：当前**无分词能力**，中文长句只能整串子串匹配，检索质量差

### 环境硬约束（必须先理解）
- 运行/Alpine 镜像**无 gcc / make / git**，**无法编译** `zhparser`、`pg_jieba`、`pg_bigm` 等 PG 扩展
- CodeBuddy 已尝试并确认不可行，**禁止再次尝试编译扩展**（会浪费大量时间并失败）

### 要求（应用层方案，不依赖数据库扩展）
1. 引入**纯 Java 中文分词库**（Maven 依赖，无需编译）：
   推荐 `com.huaban:jieba-analysis` 或 IKAnalyzer；二选一并说明理由
2. 新增页面检索向量列：
   - 新建迁移 `V38__wiki_search_vector.sql`（注意 `V37` 已被 workflow_task_tenant_id 占用，**须从 V38 起**）
   - 加 `search_vector tsvector` 列（或用生成列），写入/更新 Wiki 页面时由应用层分词后写入
   - 建 GIN 索引
3. 查询改造：查询串同样分词 → 用 `to_tsquery`/`plainto_tsquery` 匹配 `search_vector`
4. **H2 兼容**：test profile 仍走 `ILIKE` 回退（保持既有测试通过），生产(PG)走 tsvector
   —— 注意 `@Query` 需在两种方言都可执行，参考现有代码如何兼容
5. 补测试：中文分词命中（如"项目管理"能匹配含"项目 管理"文本）、H2 回退、越权隔离（tenant 过滤必须在）

### 验收
- `mvn -o test` 全绿（**H2 测试环境必须仍能跑通**，这是最容易翻车的地方）
- 中文检索质量有可验证提升（给出 before/after 对比用例）

---

## §7 P2-4：批量操作 API 补测试（**注意：API 已实现，只缺测试**）

### 现状（已核实 —— 报告标注"缺失"是错的，实际已实现）
- `CollectionController`：
  - **L582** `POST /{name}/batch-insert`
  - **L597** `POST /{name}/batch-update`
  - **L612** `POST /{name}/batch-delete`
  - Request record 定义：L624/626/628
- `CollectionService`：**L580** `batchInsert` / **L602** `batchUpdate` / **L623** `batchDelete`
- **测试覆盖 0**：`backend-java/src/test` 下 grep `batchInsert|batchUpdate|batchDelete` **命中 0**

### 要求
在 `CollectionControllerTest`（或新建 `CollectionBatchControllerTest`）补用例，至少覆盖：
- 批量插入成功（返回 count 正确）
- 批量更新成功（返回 updatedIds）
- 批量删除成功（返回 count）
- 参数校验：空列表 → 400；item 缺少 id → 400
- 越权/租户：操作他租户 collection → 403（**若现有实现未做租户校验，须按 §0 方法论补上并说明理由**）
- 部分失败语义：确认现有实现是"全部成功/全部回滚"还是"部分成功"，按实际行为写断言（**不许臆造**）

---

## §8 已核实为「非债务」，禁止重复劳动

以下曾出现在债务清单里，CodeBuddy 本次**已核实实际已完成**，你**不要再做**：

| 项 | 核实结论（证据） |
|---|---|
| 微信客服未独立 | ❌ 非债务：`integration/wecom/WeComController.java` 已独立实现（`/api/wecom`：auth-url / callback / sync-contacts / message/send，含 state 防 CSRF、一次性删除防重放） |
| 批量操作 API 缺失 | ❌ 非债务：`CollectionController` L582/597/612 已实现 → **只需补测试**（见 §7） |
| `WikiVersion` 租户加固 | ❌ 非债务：`WikiController.listVersions/getVersion` 已在 Controller 层校验 tenant（403），且已有跨租户用例 |
| 限流内存态 | ✅ 已修：`b34ef43` 改 Redis-backed ZSET 滑动窗口 |
| 备份 / 压测 / K8s / Prometheus | ✅ 均已完成并推送 |

如你核查发现上表某项判断有误，**必须先给出证据（文件+行号）说明，再动手**，不要闷头改。

---

## §9 交付自检清单（逐项勾选回报）

**P1-1 日志**
- [ ] `logback-spring.xml` 已建，生产 JSON / test 纯文本
- [ ] MDC Filter 注入 tenantId/userId 且 finally 清理
- [ ] `k8s/07-logging.yaml`（Loki/EFK）已建且 YAML 校验通过
- [ ] `k8s/README.md` 日志章节已补

**P1-2 追踪**
- [ ] pom 已加 micrometer-tracing-bridge-otel + otlp exporter，offline 可构建
- [ ] 采样率已配（生产 0.1），traceId 与日志联动
- [ ] Jaeger 部署 YAML 已建且校验通过

**P2-1 i18n**
- [ ] i18n 初始化 + zh-CN（默认）/ en-US 语言包
- [ ] 语言切换器已加且持久化
- [ ] 核心页面（Login/AppLayout/Home/IM/项目）已替换，默认语言为 zh-CN

**P2-2 Trello 前端**
- [ ] 已读后端 Controller 确认真实端点（列出路径）
- [ ] `BoardView` 已接线进项目页，拖拽可用
- [ ] `BoardView.test.tsx` 已加

**P2-3 FTS 中文**
- [ ] 已确认未尝试编译 PG 扩展（Alpine 无 gcc）
- [ ] Java 分词库已引入，`V38` 迁移已建，GIN 索引已建
- [ ] H2 测试回退 ILIKE 仍通过

**P2-4 批量测试**
- [ ] batch-insert/update/delete 用例已补（含 400/403 反向用例）

**门禁与红线**
- [ ] `mvn -o test` ≥ 基线且 0 失败（给出实测数字）
- [ ] `npm run test:run` ≥ 基线且 0 失败（给出实测数字）
- [ ] `npx tsc --noEmit` 0 error
- [ ] `npx playwright test` ≥ 基线且 0 失败（给出实测数字）
- [ ] 新增 skip 0 / 删测试 0 / 弱化断言 0
- [ ] §0 既有修复未被回滚

---

## §10 提交规范

按栈分开提交，信息写清「现状 → 改动 → 门禁实测数字」：
- `[java]` — 日志配置、MDC Filter、tracing 依赖、FTS 分词、批量测试
- `[frontend]` — i18n、BoardView 接线
- `[infra]` — k8s 日志/追踪 YAML、README

每提交一次都须跑对应门禁；**禁止攒到最后一次性提交**。

---

## §11 回报要求

1. 按 §9 清单逐项勾选（未完成项明确写"未做"及原因，不许虚报）
2. 给出四项门禁**实测数字**（mvn / vitest / tsc / playwright），并与基线对比
3. §7 中若发现现有批量实现缺租户校验，须明确报告并说明是否已修
4. 若某项因环境限制无法完成（如无法联网装依赖），**明确说明**并给出替代方案，不要静默跳过

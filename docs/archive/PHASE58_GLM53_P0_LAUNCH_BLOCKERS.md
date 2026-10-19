# Phase 58 任务书：清偿 4 项 P0 上线阻塞项（投喂 GLM-5.3）

**编排方**：CodeBuddy (HY4)　**执行方**：GLM-5.3（Kilo Code）　**审计/验收方**：CodeBuddy
**基线提交**：`79be8d3`（已推送，远端对齐）
**创建日期**：2026-09-29
**来源**：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md`（2026-09-29 实证审计，评估基线同为 `79be8d3`）

> 本任务书每项「现状」均由 CodeBuddy 于 2026-09-29 **实际读源码 + 执行命令核实**（附文件与行号），
> 你可直接采信，**无需重新审计现状**。但**未列出的 API 签名、字段名、端点路径，动手前必须自己读代码确认**。
>
> 目标：修完本任务书 4 项 P0，平台即达「企业内部 200 人自用上线」标准。

---

## §0 背景：已完成的工作（严禁回滚）

以下均已 commit 并推送，**禁止删除、回退、弱化**：

| 提交 | 内容 |
|---|---|
| `79be8d3` | 容器部署三处缺陷修复：AI 服务地址改可配（`ai.service-url`）、HEALTHCHECK 改 `/api/health`、Python `HF_HUB_OFFLINE` 防启动卡死 |
| `c4a3b18` | `executeVectorSearch` NPE 修复（回查完整实体）+ V40 Wiki ACL 种子 |
| `aad4868` | 服务间 token 认证恢复（Python `/api/ai/embedding` 恢复认证 + Java 携带 `AI_INTERNAL_TOKEN`） |
| 历史 | 跨租户越权封堵 6 类、K8s 24 资源、Prometheus+5 告警、备份/压测脚本、Redis 限流、i18n、OTel 追踪、FTS 中文分词 |

**已知的架构事实（不要重复发现，也不要写错）**：
- 多租户是 **Schema 级隔离**（`SchemaTenantConnectionProvider` 取连接 `SET search_path`、归还复位）+ 业务层租户过滤，**双保险**。
  → **不要**因为"Repository 无 tenant 字段"就加冗余列 + 迁移（CodeBuddy 曾在 `WikiVersion` 上这样误判过）。
- 真语义向量已端到端跑通：Python `sentence-transformers` 768 维；实证 `hybrid` 语义召回 `total=1` / 纯 FTS `total=0`。**不要**动这条链路。
- Java 容器镜像用 **`backend-java/Dockerfile.offline`** 零网络重建（主 Dockerfile 拉 `eclipse-temurin` 极慢，勿用）。见 §10。

---

## §1 全局红线（违反即打回）

1. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改断言阈值。门禁**只增不减**。
2. **严禁回滚** §0 任何已完成修复。
3. **严禁臆造 API**：任何方法名、端点路径、字段名，必须先 `grep` / 读源码确认存在再使用。
   （CodeBuddy 曾臆造 `advanceToNextApprovalNode` 方法名导致编译失败。）
4. **改方法签名必须同步既有测试**，否则编译失败。
5. 新增/修复的端点必须配**反向用例**（越权→403、参数非法→400、不存在→404、未认证→401）。
6. **严禁「只打日志」式修复**：本项目已有 `DingTalkAppService#syncOrganization`（只 `log.info` 返回"已触发"）被审计判为桩。
   本次四项**都必须真实现**，不许用 `log.info` + `return "已触发"` 交差。
7. **严禁静默吞异常**：`catch` 后仅 `console.error` / 空 catch **一律视为未完成**（P0-3 就是这么翻车的）。
8. **严禁提交 `.env`**（含真实 `JWT_SECRET` / `INTERNAL_SERVICE_TOKEN`）与任何密钥明文。

### 门禁基线（CodeBuddy 2026-09-29 实测，执行前你须自己复跑一次，最终 ≥ 基线且 0 失败）

| 门禁 | 命令 | 基线实测值 |
|---|---|---|
| 后端 | `cd backend-java && mvn -o test` | **1210** / Failures 0 / Errors 0 / Skipped 0 |
| 前端单测 | `cd frontend && npm run test:run` | **267 passed / 35 files** |
| 前端类型 | `cd frontend && npx tsc --noEmit` | **0 errors** |
| E2E | `cd frontend && npx playwright test` | **64 passed** |
| 容器 | `docker compose ps`（6 个服务） | 全部 **healthy** |

> ⚠️ 前端**必须用 `npm run test:run`**，不要用 `npx vitest run`（会进 watch 模式卡住）。
> 容器重建流程见 §10。

---

## §2 P0-1：备份必须覆盖 PostgreSQL 主库（数据安全）　**优先级最高**

### 现状（已核实）
- `backend-python/src/nocobase_py/services/backup.py`
  - **L36-38**：注释写"备份主数据源为本地 SQLite 告警库"，`_DB_PATHS = [Path("alerts.db")]` + media + Redis
  - **L99-100**：只遍历 `_DB_PATHS` 打包
  - **L66-72**：`_redis_snapshot()` 硬编码 `docker exec nocobase-redis redis-cli --rdb -`
- **PostgreSQL 主库完全不在备份范围** —— 而 collections / records / wiki / IM / workflow **全部业务数据都在 Postgres**
- K8s 环境下无 `docker` 命令 → `FileNotFoundError` → **整个备份任务失败**
- 既有测试 `backend-python/tests/test_backup_restore.py` 只覆盖 SQLite/文件/Redis，**掩盖了主库缺失**

### 要求
1. **把 Postgres 主库纳入备份**：
   - 用 `pg_dump`（自定义格式 `-Fc` 优先，便于 `pg_restore` 选择性恢复）
   - 连接参数**全部从环境变量**读取（`POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB`），**禁止硬编码**
   - 注意：容器内 `POSTGRES_HOST=postgres`，本地跑是 `localhost`，配置要能两边都工作
2. **移除 `docker exec` 硬依赖**：
   - Redis 快照改为直连（`REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD`），用 `redis-cli --rdb` 或等价方式
   - **K8s 下必须可用**（无 docker 命令也能跑完）
3. **恢复路径真可用**：主库恢复（`pg_restore` / `psql < dump`）必须真实可执行，不是 TODO
4. **失败不得静默**：备份元数据须逐组件记录成功/失败；任一关键组件（尤其主库）失败必须让任务整体失败并报错，**不许吞异常继续返回"成功"**
5. **补测试**（`test_backup_restore.py` 或新建）：
   - 主库 dump 生成且文件大小 > 0
   - 主库恢复后**记录数一致**（给出真实数字作为断言依据，不许臆造）
   - Redis 快照在无 docker 环境下的分支（若你实现了非 docker 路径，须覆盖）
   - 主库备份失败时任务整体失败（反向用例）
6. **真实演练**：对本地 docker 的 postgres 执行一次完整「备份 → 破坏 → 恢复 → 校验」，**在回报中给出证据**（dump 字节数、恢复后记录数、耗时），并说明 RPO/RTO。

### 验收
- `backend-python` 测试全绿
- **主库真实恢复演练成功**（这是硬性验收项，只跑单元测试不算完成）
- 无 `docker exec` 硬依赖残留（`grep -rn "docker exec" backend-python/src` 应为 0）

---

## §3 P0-2：钉钉登录主入口 405（POST → GET-only）　**成本最低、收益最高**

### 现状（已核实）
- `frontend/src/pages/auth/DingTalkLoginPage.tsx` **L27-30**：
  ```js
  const response = await fetch('/api/dingtalk/auth-url', {
    method: 'POST',                       // ← POST
    headers: { 'Content-Type': 'application/json' },
  });
  ```
- `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkController.java` **L69**：`@GetMapping("/auth-url")` ← **GET**
- → **405 Method Not Allowed**，该页面是钉钉登录主入口之一（路由 `/auth/dingtalk`），**用户点登录必然失败**
- 同一功能另一条路径 `frontend/src/pages/dingtalk/DingTalkPage.tsx` **L40** 用 `window.location.href='/api/dingtalk/auth-url'`（GET）✅ 正确
  → **同功能两条路径、一条坏**，属回归遗漏

### 要求
1. **先读 `DingTalkController` 确认 `/auth-url` 的真实签名与参数**（是否需 query 参数如 `redirectUri`），再决定改哪边。
   **二选一**（不许两边都改造成新不一致）：
   - A）**推荐**：前端改 GET（与 `DingTalkPage` 已验证正确的写法对齐），若需传参则用 query string
   - B）后端改 POST —— 仅在确认有必须走 body 的参数时才选，且须同步改 `DingTalkPage`
2. **补回归测试防再次错配**（必须做，否则还会回归）：
   - 前端单测：mock `fetch`，断言 `DingTalkLoginPage` 触发的请求 method 与后端一致
   - 或后端契约测试断言 `/auth-url` 的 method
   - 二者选一即可，但必须**能真实拦截 POST/GET 不一致**
3. **顺带核对企微是否同病**（要求你自查并报告）：
   `frontend/src/pages/auth/WeComLoginPage.tsx` 与 `backend-java/.../integration/wecom/WeComController.java` 的 `auth-url` method 是否一致。
   **若不一致一并修复**；若一致则在回报中明确说明"已核对一致"（不许不查就跳过）。
4. 若发现 `useDingTalkAuth.ts` 的 `isInDingTalk()` 依赖 UA 嗅探而非钉钉 JS-SDK，**只需报告**，本轮**不要求改**（属 P1）。

### 验收
- `npm run test:run` / `npx tsc --noEmit` / `npx playwright test` 全绿
- 回报中给出：**后端实际 method**、**前端修改后 method**、**企微核对结论**

---

## §4 P0-3：块编辑器自动保存 404 且静默失败（假完成 + 数据丢失）

### 现状（已核实）
- `frontend/src/components/wiki/NotionStyleEditor.tsx`
  - **L264-265**：每 2 秒防抖 `POST /api/wiki/blocks/batch-upsert`
  - **L281**：失败仅 `console.error('保存失败:')` → **静默吞掉**
- 后端全仓 `grep -rn "batch-upsert" backend-java/src` **0 命中**
  后端现有块端点（`WikiController`）：**L410** `POST /pages/{id}/blocks`、**L423** `PUT /blocks/{blockId}`、
  **L438** `DELETE /blocks/{blockId}`、**L449** `PUT /pages/{id}/blocks/reorder`
- → **每次自动保存必然 404，Block 树从不落库**；用户以为在用 Notion 式块编辑并已保存，实际只有整篇 markdown 生效
- 同文件另两处已核实缺陷：
  - **L319**：`fetch('/api/wiki/templates')`，后端只有 `@GetMapping("/kb/{kbId}/templates")`（L604）→ 404，模板下拉永远为空
  - **L306-307**：`setBacklinks(data || [])` —— 后端返回 `{code:0,data:[...]}` 信封却**未 unwrap**，随后 L861 `backlinks.map()` 会抛 `is not a function`

### 要求
1. **二选一，但必须真落库**（不许只改前端让它不报错了事）：
   - **A）推荐**：后端新增 `POST /api/wiki/blocks/batch-upsert`（批量 upsert + 可选 reorder，**单事务**），对齐前端语义
   - B）前端改为调用现有 `POST /pages/{id}/blocks` + `PUT /pages/{id}/blocks/reorder`
   > 无论选哪个：都须保证"块编辑 → 保存 → Block 树真落库"可验证。
2. **失败必须让用户看见**：保存失败要有 **UI 可见提示**（如 Snackbar/Alert），**禁止仅 `console.error`**。
   这是本项的核心验收点之一 —— 静默失败比报错更危险。
3. **修模板端点**（L319）：改为 `/kb/{kbId}/templates` 或后端补对应端点，二选一，保证模板列表真能加载。
4. **修反向链接 unwrap**（L306-307）：按后端信封取 `data.data`；参考 `frontend/src/api/endpoints.ts` L36 已有的 `wikiBacklinks` 定义（目前未被使用，可复用）。
5. **补测试**：
   - 后端：`batch-upsert`（或所选方案）的用例 —— 成功落库、块重排、越权 403、跨租户隔离、非法参数 400
   - 前端：保存失败时**显示错误提示**（断言 UI 出现错误元素，不是断言 console 被调用）
   - 前端：模板加载成功用例（mock 正确端点）
6. **端到端自证**：回报中给出「块编辑保存 → 查库/查 API 确认 Block 记录存在」的证据。

### 验收
- `mvn -o test` / `npm run test:run` / `tsc` / `playwright` 全绿
- Block 树真落库（有证据）
- 保存失败有 UI 提示（有用例）

---

## §5 P0-4：Huddle 语音内存路由与 K8s 多副本冲突

### 现状（已核实）
- `backend-java/src/main/java/com/nocobase/im/HuddleSignalingHandler.java` **L32**：
  `Map<String, Set<WebSocketSession>> rooms` —— **进程内内存路由**
  （类注释自认"多实例部署需迁移到 Redis pub/sub"）
- `k8s/02-backend-java.yaml`：**replicas: 3** + HPA 3→10 + PDB minAvailable 2
- → 同一房间的用户连到不同副本就**互相看不见**，语音/WebRTC 信令随机失效

### 要求（二选一，**必须在回报中说明选了哪个及理由**）
- **A）正解（推荐）**：迁移到 **Redis pub/sub** 跨实例广播信令
  - 项目已有 `backend-java/src/main/java/com/nocobase/im/RedisStompBridge.java`（或同目录类似实现），**先读它参考写法**
  - 保持现有 WS 端点 `/ws/huddle` 与 JWT 握手鉴权不变（这块是真接真的，别改坏）
  - 房间成员/会话仍可本地持有，但**信令转发必须跨实例**
  - 补测试：至少用两个 handler 实例 + mock/真实 Redis 证明「A 实例的信令能被 B 实例的成员收到」
- **B）短期方案**：不改代码，改 K8s —— Ingress 加**会话粘滞**（如 nginx `affinity: cookie`），
  并在 `k8s/02-backend-java.yaml` 注释中**明确标注这是临时方案、正解是 Redis pub/sub（TODO 指向本任务）**
  - 选择 B 时，回报必须写明"未做 A"及原因，不许含糊

> 若你判断 A 的工作量远超预期（> 5 人日）才可选 B；否则默认做 A。

### 验收
- `mvn -o test` 全绿
- 所选方案有可验证证据（A：跨实例广播测试通过；B：YAML 含粘滞配置 + 注释）
- 现有 Huddle 前端（`frontend/src/components/im/HuddlePanel.tsx` + `useHuddle.ts`）**不被改坏**

---

## §6 已核实为「非债务」，禁止重复劳动

以下曾疑似问题，审计已核实**实际已完成或不属于本轮范围**，你**不要再做**（若你认为判断有误，须先给文件+行号证据说明）：

| 项 | 核实结论 |
|---|---|
| 多租户隔离 | ✅ 已 Schema 级 + 业务层双保险，**不需要**加冗余列或改架构 |
| 真语义向量 / RAG | ✅ 已端到端跑通（768 维 sentence-transformers），**不要**动 |
| Wiki 全员 403 | ✅ 已由 V40 迁移修复 |
| AI 服务地址硬编码 | ✅ 已改 `ai.service-url` 可配 |
| 容器 HEALTHCHECK | ✅ 已改 `/api/health` |
| Python HF 启动卡死 | ✅ 已加 `HF_HUB_OFFLINE` |
| 跨租户越权 6 类 | ✅ 均已封堵 |
| K8s / Prometheus / Grafana / 追踪 | ✅ 均已就位 |

**明确不属于本轮**（在评估报告中列为 P1/P2，本任务书**不做**）：
CRDT 字符级协同、全局 API 限流、定时工作流去内存态、Cron 表达式、移动端响应式、IM 搜索索引、
Slack/Mattermost 入站消费者、集成市场 UI、字段类型扩展、视图级 group by、RocketChat（代码 0 实现）。
> 你**可以**在回报里补充发现，但**不许**擅自扩大改动范围 —— 范围外的改动一律打回。

---

## §7 交付自检清单（逐项勾选，未完成明确写"未做"）

**P0-1 备份**
- [ ] Postgres 主库已纳入备份（`pg_dump`），连接参数来自环境变量、无硬编码
- [ ] `docker exec` 依赖已移除（`grep "docker exec" backend-python/src` = 0）
- [ ] 主库恢复路径真可用（非 TODO）
- [ ] 关键组件失败时整体失败并报错（不静默）
- [ ] 测试覆盖主库备份/恢复/失败路径
- [ ] **真实演练完成**：给出 dump 字节数、恢复后记录数、耗时、RPO/RTO

**P0-2 钉钉登录**
- [ ] 已读 `DingTalkController` 确认 `/auth-url` 真实签名
- [ ] 前端与后端 method 已一致（写明各自是什么）
- [ ] 已补回归测试（能拦截 POST/GET 错配）
- [ ] 企微 `auth-url` method 已核对（一致/不一致并修）——**必须明确报告**

**P0-3 块编辑**
- [ ] 选择了方案 A 或 B（写明）
- [ ] 块编辑保存后 **Block 树真落库**（给出验证证据）
- [ ] 保存失败有 **UI 可见提示**（非 console.error）
- [ ] 模板端点已修（能加载）
- [ ] 反向链接 unwrap 已修
- [ ] 后端用例（含 403/400/租户隔离）+ 前端用例（失败提示）已补

**P0-4 Huddle**
- [ ] 选 A（Redis pub/sub）或 B（会话粘滞），已说明理由
- [ ] 若 A：跨实例广播测试通过；若 B：YAML 含粘滞配置 + 明确 TODO 注释
- [ ] Huddle 前端未被改坏

**门禁与红线**
- [ ] `mvn -o test` ≥ 1210 且 0 失败（给出实测数字）
- [ ] `npm run test:run` ≥ 267 且 0 失败（给出实测数字）
- [ ] `npx tsc --noEmit` = 0 error
- [ ] `npx playwright test` ≥ 64 且 0 失败（给出实测数字）
- [ ] 新增 skip 0 / 删测试 0 / 弱化断言 0
- [ ] §0 既有修复未被回滚
- [ ] 未提交 `.env` 或任何密钥明文
- [ ] 无擅自扩大范围（§6 之外）的改动

---

## §8 提交规范

按栈分开提交，信息写清「现状 → 改动 → 门禁实测数字」：
- `[python]` — 备份主库、去 docker 依赖、演练
- `[java]` — `batch-upsert` 端点（若选 A）、Huddle Redis pub/sub（若选 A）、相关测试
- `[frontend]` — 钉钉 method、企微核对、块编辑保存/失败提示/模板/反向链接
- `[infra]` — 若选 B：K8s 粘滞配置 + 注释

**每提交一次都须跑对应门禁**，禁止攒到最后一次性提交。

---

## §9 回报要求

1. 按 §7 清单**逐项勾选**，未完成项明确写"未做"及原因 —— **不许虚报**（审计会逐条复核）
2. 给出四项门禁**实测数字**并与 §1 基线对比
3. P0-1 必须附**真实演练证据**（命令 + 输出片段：dump 大小、恢复后记录数）
4. P0-3 必须附**落库证据**（保存后查询 Block 的结果）
5. P0-4 必须写明所选方案及理由；选 B 须写明未完成 A 的原因
6. 企微 `auth-url` 核对结论必须明确写出
7. 若某项因环境限制无法完成，**明确说明**并给出替代方案，不要静默跳过

---

## §10 环境说明（执行前必读）

### 容器与镜像
- 6 个服务：`postgres` / `redis` / `rabbitmq` / `minio` / `backend-java` / `backend-python`（全部 healthy）
- **Java 镜像必须用离线方式重建**（主 Dockerfile 拉 `eclipse-temurin` 极慢，实测 597s 仅 8MB/53MB，**不要用**）：
  ```bash
  cd backend-java
  mvn -o package -DskipTests
  docker build -f Dockerfile.offline -t nocobase-backend-java:latest .   # 零网络，秒级
  cd .. && docker compose up -d --no-build backend-java
  ```
  （`Dockerfile.offline` 以本地已有镜像为 base，仅覆盖新 jar）
- Python 服务改代码后：`docker compose up -d --no-build backend-python`

### 环境变量（`.env`，**禁止提交**）
- 已有：`POSTGRES_PASSWORD`、`JWT_SECRET`（真实 64 hex）、`INTERNAL_SERVICE_TOKEN`、`INSTALL_SEMANTIC=true`
- `docker` profile 下 `KeyRingService` 会**拒绝 dev 占位 JWT secret** → 容器启动失败（这是正确的安全设计，别去绕过它）
- Python 容器已设 `HF_HUB_OFFLINE=1`（模型走构建期缓存，勿删，否则启动卡死）

### 验证命令速查
```bash
curl -s localhost:8080/api/health          # Java
curl -s localhost:8000/api/health          # Python
docker compose ps                          # 全部 healthy
grep -rn "docker exec" backend-python/src  # P0-1 应为 0
grep -rn "batch-upsert" backend-java/src   # P0-3 选 A 后应 > 0
```

---

## §11 审计口径（CodeBuddy 将如何验收 —— 提前告知，请勿抱侥幸）

1. **追溯调用链，而非看文件是否存在**：本项目多次出现"有代码但从未调用"的假完成（如 `MessageSearchService` 0 注入、
   `workflow/ExpressionEvaluator` 0 引用）。验收时会 `grep` 引用点、检查是否被真实调用。
2. **复跑四项门禁**（mvn / vitest / tsc / playwright），数字须 ≥ 基线。
3. **逐项抽查证据**：
   - P0-1：要求看到真实演练的 dump 大小与恢复后记录数（会自己再跑一次恢复验证）
   - P0-2：核对后端 method 注解与前端 fetch method，并跑你补的回归测试
   - P0-3：亲自改一个块 → 保存 → 查库确认 Block 存在；并断开后端模拟失败，确认 UI 有提示
   - P0-4：看所选方案与证据；若选 B，检查 YAML 注释是否诚实标注为临时方案
4. **反作弊检查**：新增 `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值 —— 任一命中即打回。
5. **范围检查**：§6 之外的擅自改动一律打回（哪怕看起来是"顺手优化"）。

> 一句话：**要真修，不要让它看起来修好了。**

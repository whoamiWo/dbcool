# Phase53 执行提示词（GLM-5.3 执行版 W1–W6）

> 编排方：CodeBuddy(HY4)　执行方：GLM-5.3　复审计方：CodeBuddy
> 生成时间：2026-09-22　基线提交：`31aa9ed`（Phase52 全部收口，三栈全绿）
> 前置：`PHASE52_GLM53_PROMPT.md`(T1–T10)、REWORK(R1–R9)、REWORK2(F1–F7)、REWORK3(G1–G2)、REWORK4(G2-R)、REWORK5(H1)

---

## 0. 背景：Phase52 已收口，本轮转向**生产化闭环**

CodeBuddy 于 2026-09-22 完成**实测复核**（非静态推断），Phase52 状态：

| 项 | 实测 |
|---|---|
| Java `mvn test` | **1075 PASS / BUILD SUCCESS** ✅ |
| 前端 `npx vitest run`（默认配置） | **250/250 全绿，×0** ✅ |
| `tsc --noEmit` / `vite build` | **0 errors / 成功** ✅ |
| Python `compileall` | **OK** ✅ |
| 六大能力域导航 | 首页/Tables/Docs/Chat/Projects/Automations/AI **七项齐全** ✅ |
| 深度特性 | FormulaEngine / RollupEngine / Lookup / IM 的 Reaction·Pin·Burn·Slash **均真实存在** ✅ |

**本轮不做新功能堆砌，只做"让 v1.0 可真实交付"的生产化闭环。**

---

## 1. 全局约定

### 1.1 五条红线（重申）
1. **禁止 `log.info` + `// TODO` 冒充接真**；未配置外部服务返回明确错误（禁空 catch 吞异常）
2. **禁止臆造 API**：调用任何符号前用 code-explorer / lsp 确认真实存在与**参数签名**
3. **前端禁硬编码亮色**：一律 `var(--color-*)` + MUI `sx`
4. **迁移版本号从 V36 起**（V35=livechat_ticket 已被占用）；本轮尽量不改 schema
5. **不引入重型依赖**（redis-py 属轻量且项目已用 Redis，允许；pgvector 仍不引入）

### 1.2 特别禁令
- ❌ 禁删测试 / `it.skip` / `it.todo` / 弱化断言
- ❌ 禁改 `vite.config.ts` 的 `pool:'forks' + isolate:true + sequence.concurrent:false`（改回 threads 会立刻复现 ~98 失败）
- ❌ 禁改 `src/test-setup.ts` 打补丁掩盖
- ✅ 允许：改配置、补全局清理、消除模块级单例、把歧义选择器改精确

### 1.3 门禁命令（每项完成后立即跑）
```bash
cd backend-java && mvn test 2>&1 | grep -E "Tests run:|BUILD" | tail -3   # 须 ≥1075
cd frontend && npx vitest run --reporter=verbose > /tmp/w.log 2>&1        # 须 250/250、×0
cd frontend && npx tsc --noEmit                                           # 须 0 errors
cd frontend && npm run build                                              # 须成功
cd backend-python && python3 -m compileall -q src/nocobase_py && echo PY_OK
```

### 1.4 提交规范
按栈分提交：`[java]` / `[python]` / `[js]` / `[docs]`。

---

## 2. 任务（按优先级，先做 P0）

### W1（P0）限流 / 缓存 / 配额迁 Redis —— 自托管多副本正确性阻塞

**现状证据（实测）**
```
backend-python/src/nocobase_py/middleware/rate_limit.py
  class SlidingWindowRateLimiter:
    self._buckets: dict[str, list[float]] = defaultdict(list)   # ← 内存桶

backend-python/src/nocobase_py/services/llm_cache.py
  class LLMCache: "LRU 内存缓存(带 TTL)"                        # ← 内存 LRU

backend-python/src/nocobase_py/services/quota.py
  from collections import defaultdict                            # ← 内存配额
```
三者**均为进程内存态** → 多副本部署时限流/缓存/配额各自独立，**功能正确性失效**。
（Java 侧 Redis 已在用，Python 侧 `config.py:46-47` 已有 `redis_host="localhost"` / `redis_port=6379`。）

**实现要点**
1. **先确认**：`pyproject.toml` 是否已有 redis 客户端依赖；无则添加**轻量** `redis`（async 用 `redis.asyncio`），不得引入重型依赖
2. 新增统一 Redis 客户端（如 `src/nocobase_py/services/redis_client.py`），从 `config` 读 `redis_host/redis_port`，**连接失败须返回明确错误而非静默降级到内存**（红线1：禁假成功）
3. `SlidingWindowRateLimiter` 改 Redis 后端：用 **sorted set（ZADD/ZREMRANGEBYSCORE/ZCARD）+ 过期** 实现滑动窗口，保证原子性（可用 Lua 脚本）
4. `LLMCache` 改 Redis：保留 LRU 语义可用 Redis `maxmemory-policy=allkeys-lru`，或应用层维护 key + TTL
5. `quota.py` 改 Redis：日/月计数用 `INCR` + `EXPIRE`，跨副本一致
6. **保留内存实现作为 fallback**：仅当显式配置 `REDIS_ENABLED=false`（且日志 WARN 明确提示"单副本模式"）时启用，禁止无感降级

**验收**
```bash
cd backend-python && python3 -m compileall -q src/nocobase_py && echo PY_OK
# 新增单测（mock redis 或 fakeredis）：
#   - 限流：N+1 次请求第 N+1 次抛 429
#   - 配额：并发 INCR 计数正确
#   - Redis 不可用时返回明确错误（断言非静默成功）
python3 -m pytest -q   # 若环境无 pytest，至少做 compileall + import 冒烟并说明
```

---

### W2（P0）TIMELINE 视图后端业务接通 —— 前端已通、后端仅枚举

**现状证据（实测）**
```
后端：grep -rn "TIMELINE" backend-java/src/main/java/com/nocobase/
  → 仅 1 处命中：view/ViewEntity.java:30  TABLE, KANBAN, DETAIL, GALLERY, CALENDAR, TIMELINE
前端（已通）：
  features/collection/TimelineView.tsx 存在
  router.tsx:131-132 lazy 导入；:198 路由 'views/:id/timeline'
```
→ **半完成**：UI 与路由已通，但后端 service 层**无 TIMELINE 特有处理**（排序/区间查询/字段映射）。

**实现要点**
1. **先确认** `view` 包现有视图查询链路（如 `ViewService` / `ViewDataService`）与 `ViewEntity` 用法
2. 为 TIMELINE 补：按**开始/结束日期字段**排序 + 区间过滤（沿用 `CollectionService.listRecords` 后再排序，或新增带 orderBy 的查询；**不得臆造方法**，先确认真实签名）
3. 视图配置需支持指定"开始字段/结束字段"（存 `view.config` JSONB，不改表结构 → 无需迁移）
4. 前端 `TimelineView` 若已有 mock 数据，**改为调用真实端点**（先确认端点真实存在）

**验收**
```bash
cd backend-java && mvn test 2>&1 | grep -E "Tests run:|BUILD" | tail -3   # ≥1075 + 新增用例
# 新增 ViewServiceTest / TimelineViewTest：断言 TIMELINE 返回结果按开始字段升序、区间过滤生效
```

---

### W3（P1）批量操作 API —— 全仓无 `/batch`

**现状证据（实测）**：`grep -rn "/batch" backend-java/...` → **0 命中**

**真实签名（已确认，可直接复用）**
```java
UUID   insertRecord(String collectionName, Map<String,Object> data, String tenantId)   // :242
List<Map<String,Object>> listRecords(String collectionName, String tenantId, int limit) // :305
boolean updateRecord(String collectionName, String id, ...)                             // :509
boolean deleteRecord(String collectionName, String id, String tenantId)                 // :531
```

**实现要点**
1. 在 `meta` 包新增批量方法：`batchInsert` / `batchUpdate` / `batchDelete`，**内部复用上述已确认签名**，循环 or 批量 SQL
2. 事务性：整批成功或整批回滚（`@Transactional`），部分失败须返回**逐条结果 + 明确错误**，禁止静默吞掉
3. 单次上限（如 100 条），超限返回明确 400（禁假成功）
4. Controller 暴露 `POST /api/collections/{name}/records/batch`（路径先确认与现有 `CollectionController` 一致）

**验收**
```bash
cd backend-java && mvn test 2>&1 | grep -E "Tests run:|BUILD" | tail -3
# 新增 CollectionServiceBatchTest：批插 100 条成功 / 超限 400 / 部分失败返回逐条错误且事务回滚
```

---

### W4（P1）清理双 lock 文件 —— npm 与 pnpm 并存隐患

**现状证据（实测）**
```
frontend/package-lock.json   317660 B  (Sep 20)
frontend/pnpm-lock.yaml      189068 B  (Sep 19)
```
两个 lock 文件并存 → 依赖解析可能不一致。项目实际用 **npm**（`npm run build` / `npx vitest run` 均已跑通）。

**实现要点**
1. 确认并记录当前 `node_modules` 由哪个 lock 安装（看 `.package-lock.json` 或 npm 版本）
2. **删除 `pnpm-lock.yaml`**（保留 npm 单一来源），或反之——**二选一，不得并存**
3. 删除后重跑门禁验证依赖未破坏

**验收**
```bash
cd frontend && ls | grep -E "package-lock|pnpm-lock"   # 只应剩 1 个
cd frontend && npx vitest run --reporter=verbose | tail -3   # 仍 250/250
cd frontend && npx tsc --noEmit && npm run build
```

---

### W5（P2）跑通已有 9 个 E2E 场景（**不是新建**）

**现状证据（实测）**：`frontend/e2e/` 已有 **9 个 spec**
```
login / collection-delete / dingtalk-login / form-submit / full-demo-path
users-crud / wiki-permission / wiki-phase1 / workflow-designer
```
（注意：有建议称"需新建 5+ E2E 场景"——**与实际不符**，应是**跑通并维护已有 9 个**）

**实现要点**
1. 确认 Playwright 是否已配置（`playwright.config.*`）；无则补
2. 逐个跑，记录通过/失败；失败的**优先修产品/选择器**，不得 skip
3. 输出一份 `E2E_STATUS.md` 记录每个场景结果

**验收**
```bash
cd frontend && npx playwright test 2>&1 | tail -20
# 目标：9 个场景通过（或明确列出失败场景 + 原因，不得 skip）
```

---

### W6（P2）中文分词 —— **先验证可行性再动手**

**现状证据（实测）**：搜索用 `'simple'` 解析器
```
UnifiedSearchIndexRepository.java:39  ts_headline('simple', e.content, ...)
                              :49  e.content_tsv @@ to_tsquery('simple', :keyword)
```
`'simple'` 对中文**无词级切分**，中文搜索体验明显弱于英文。

**⚠️ 技术纠错（重要，勿照抄错误方案）**
PostgreSQL **没有内置的 `chinese` 文本搜索配置**（内置仅 `simple`/`english` 等）。
网上流传的 `to_tsvector('chinese', content)` **在未装扩展的环境会直接报错**。
正确做法需 **zhparser 扩展**：
```sql
CREATE EXTENSION zhparser;
CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);
ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR n,v,a,i,e,l WITH simple;
```

**实现要点**
1. **第一步只做可行性验证**：确认目标 PostgreSQL 能否安装 `zhparser` 扩展（需编译安装，非默认自带）
   - 可装 → 走 zhparser 方案，写 **V36 迁移**改触发器与 `ts_headline/tsquery` 的解析器参数
   - 不可装 → **降级方案**：应用层中文预处理（如 bigram 拆分）或维持 simple + 文档说明，**不得声称"已支持中文分词"**
2. 若改 schema/触发器：**迁移版本号用 V36**（V35 已占用）
3. 注意 `content_tsv` 由**触发器维护**（`insertable=false`），改解析器须同步改触发器定义

**验收**
```bash
# 可装 zhparser：
cd backend-java && mvn test 2>&1 | grep -E "Tests run:|BUILD" | tail -3
# 新增用例：中文关键词能命中中文内容（如搜"架构"命中含"架构"的文档）
# 不可装：输出明确结论 + 降级方案说明，禁止虚假声称
```

---

## 3. 范围边界（**不要越界**）

- ❌ **不做** ABAC 属性权限（大工程，与"收口"定位冲突，另立项）
- ❌ **不做** AI Agent 多 Agent 协作（新功能，另立项）
- ❌ **不动** 已通过审计的产品功能代码（除非上面任务必需）
- ✅ 本轮性质：**生产化闭环 + 断层补齐**

---

## 4. 附录 A — 实测证据速查（可复现）

```bash
# 内存态三件套（W1 依据）
grep -n "defaultdict" backend-python/src/nocobase_py/middleware/rate_limit.py
grep -n "class LLMCache" backend-python/src/nocobase_py/services/llm_cache.py
grep -n "defaultdict" backend-python/src/nocobase_py/services/quota.py
grep -n "redis_host\|redis_port" backend-python/src/nocobase_py/config.py

# TIMELINE 仅枚举（W2 依据）
grep -rn "TIMELINE" backend-java/src/main/java/com/nocobase/   # 只应命中 ViewEntity.java:30

# 批量 API 缺失（W3 依据）
grep -rn "/batch" backend-java/src/main/java/com/nocobase/     # 应为 0

# 双 lock（W4 依据）
ls frontend/package-lock.json frontend/pnpm-lock.yaml

# E2E 已有 9 个（W5 依据）
ls frontend/e2e/*.spec.ts | wc -l

# 搜索解析器（W6 依据）
grep -n "simple" backend-java/src/main/java/com/nocobase/search/UnifiedSearchIndexRepository.java
```

## 5. 附录 B — 纠错提示（避免踩坑）

1. **PG 无内置 `chinese` 配置**：见 W6，须先验证 zhparser 可装性
2. **E2E 不是 0 而是 9**：别新建，跑通已有
3. **企微(wecom) ≠ 微信客服**：本轮不混淆二者
4. **`pnpm` 非项目主包管器**：以 npm 为准（W4 统一）
5. **不得臆造 commit 哈希/API 签名**：所有引用须实测核验
6. **全量测试必须见汇总行**：缺 `Test Files|Tests` 汇总行即未跑完，数字无效

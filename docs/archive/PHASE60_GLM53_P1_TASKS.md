# Phase 60 任务书：补降级日志、修既有测试失败、清 P1 三项（投喂 GLM-5.3）

**编排方**：CodeBuddy (HY4)　**执行方**：GLM-5.3（Kilo Code）　**审计/验收方**：CodeBuddy
**基线提交**：`f43ba90`（PHASE 58 四项 P0 + PHASE 59 两项已全部闭环并推送）
**创建日期**：2026-09-30

> 本任务书每项「现状」由 CodeBuddy 于 2026-09-30 **实际读源码 + 实测命令核实**（附文件与行号），
> 可直接采信。**但未列出的签名/字段名，动手前必须自己读代码确认**。

---

## §0 背景：已完成的工作（严禁回滚）

| 提交 | 内容 |
|---|---|
| `3d06532` | [java] P0-3 `batch-upsert` + jsonb 序列化 + 4 用例 |
| `6441307` | [python] P0-1 备份覆盖 Postgres 主库 + Dockerfile 装客户端 |
| `17de634` | [frontend] P0-2 钉钉/企微 405 + 真回归测试 |
| `b3afd73` | [infra] P0-4 Huddle 粘滞 + compose 注入 POSTGRES_*/REDIS_* |
| `809eba5` | [infra] 全局排查 localhost（修 `AI_PYTHON_URL`） |
| `5d8203a` | [infra] RabbitMQ `SPRING_RABBITMQ_*` + 测试 profile 禁用 MQ auto-startup + JWT 多算法 |
| `f43ba90` | [infra] 清理 `EOF` 残留 + 注入 `AI_ENABLED` |

**当前门禁基线（执行前复跑，最终 ≥ 基线且 0 失败）**：

| 门禁 | 命令 | 基线实测 |
|---|---|---|
| 后端 | `cd backend-java && mvn -o test` | **1214 / 0 / 0 / 0** BUILD SUCCESS |
| 前端单测 | `cd frontend && npm run test:run` | **271 passed** |
| 前端类型 | `cd frontend && npx tsc --noEmit` | **0** |
| E2E | `cd frontend && npx playwright test` | **64 passed** |
| Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | **11 failed / 32 passed / 1 skipped**（T2 目标：failed → 0） |

**方法论提醒（本项目已踩 5 次）**：容器内 `localhost` 指向容器自身；
服务间地址必须 `@Value` 可配 + compose 用服务名注入。
另：**降级分支必须打日志**（T1 就是因为这个才排查了很久）。

---

## §1 全局红线（违反即打回）

1. 严禁 `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改断言阈值。
2. 严禁回滚 §0 任何已完成修复。
3. 严禁臆造 API/字段名，必须先确认再写。
4. **严禁「只打日志」式修复**；严禁 `catch` 后仅 `console.error` 或空 catch（但 T1 要求的**补日志**是正当的）。
5. 严禁提交 `.env` 或密钥明文。
6. **严禁扩大范围**：只做 T1、T2、T3-1、T3-2、T3-3（§7 明确排除的不要碰）。
7. 每项修复必须给**实测输出**。

---

## §2 T1：给 AI 降级分支补 WARN 日志（小，但很有价值）

### 现状（已核实）
`backend-java/src/main/java/com/nocobase/ai/AiAssistantService.java`
- **L38**：`@Value("${ai.enabled:false}")`
- **L87-89**（`callLlm` 开头）：
  ```java
  if (!enabled) {
      return Map.of("code", 0, "message", "AI 未启用", "data", mapper.apply(UNAVAILABLE));
  }
  ```
  → **该分支不打任何日志**

### 为什么必须修
CodeBuddy 排查「AI 端点一直降级」时，因为日志里**完全没有**相关输出，
先后误判为网络问题、JWT 问题、限流问题，绕了一大圈才发现只是 `ai.enabled=false`。
**静默降级 = 排查黑洞**。

### 要求
1. 在 `if (!enabled)` 分支加 `log.warn(...)`，内容须包含：
   - 提示 `ai.enabled=false`，AI 助手未启用
   - 提示需要配置 `ai.enabled` 与 `ai.python-url`
   - 当前 `pythonUrl` 的值（脱敏后，便于确认配置是否注入）
2. 同理，检查 `WikiController` 中 `/ask` 的 fallback 分支（约 **L776-789**，拼接
   「（AI 服务不可用，以下为知识库检索结果）」），也应加 WARN 说明为何回退。
3. 若类中无 `log` 字段，按项目既有风格添加（参考同类 Service 的 `LoggerFactory`）。
4. 补一条测试：验证 `ai.enabled=false` 时返回降级文案（**且不影响既有 1214 用例**）。

### 验收
- `mvn -o test` ≥ 1214 且 0 失败
- 贴出 `ai.enabled=false` 时新 WARN 日志的实际输出（证明真的打了）

---

## §3 T2：修复 Python 11 个既有测试失败

### 现状（已核实，实测）
```
cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q
→ 11 failed, 32 passed, 1 skipped
```

失败构成：
- **8 个**：`tests/test_r11.py`（`TestSlidingWindowRateLimiter`、`TestLLMCache`、`TestQuotaService`）
  报 `RuntimeError: Event loop is closed` / `attached to a different loop`
- **2 个**：`tests/test_r11_integration_verify.py`
  （`test_feishu_signature_invalid`、`test_mattermost_token_match`）—— 签名断言失败
- **1 个**：其他（需你自行确认是哪一条并归类）

相关配置：
- `backend-python/pyproject.toml` **L46**：`pytest-asyncio>=0.24.0`
- `backend-python/pyproject.toml` **L89**：`asyncio_mode = "auto"`

### 要求
1. **先分类再修**：明确列出 11 个失败各自的根因，不许笼统说"既有问题"。
2. 针对 asyncio 失败（8 个）：
   - 确认 Python 版本（本机为 **3.14**）与 `pytest-asyncio` 版本的兼容性；
   - 可选修法：升级 `pytest-asyncio`、调整 `asyncio_mode`、或在 `conftest.py` 提供
     `event_loop` / `anyio` fixture；**选一种并说明理由**；
   - **不许**通过 `pytest.mark.skip` 或直接删测试来"修"。
3. 针对 feishu/mattermost 签名断言失败（2 个）：
   - 先确认是**测试断言写错**还是**被测签名逻辑真有 bug**；
   - 若是实现 bug → 修实现；若是测试写错 → 修测试并说明；
   - **不许**通过放宽断言（如只断言"不为空"）来通过。
4. 目标：`pytest tests/ -q` → **0 failed**（32+11=43 passed，1 skipped）。

### 验收
- 贴出修复前后的汇总行对比
- 逐个说明 11 个失败的根因与修法

---

## §4 T3-1：全局 API 限流（P1）

### 现状（已核实）
- 现有限流只有 **工作流触发限流**：`backend-java/src/main/java/com/nocobase/workflow/TriggerRateLimiter.java`
  （Redis ZSET 滑动窗口，被 `WorkflowTriggerListener` 调用）
- **登录、消息发送、文件上传、API Key 调用均无全局限流**
- 可参考的过滤器写法：`com.nocobase.apikey.ApiKeyFilter`、`com.nocobase.auth.MdcFilter`（`OncePerRequestFilter`）

### 要求
1. 新增全局限流过滤器（建议 `com.nocobase.ratelimit` 包），基于 **Redis 滑动窗口**（复用
   `TriggerRateLimiter` 的 ZSET 思路），Redis 不可用时降级为本地/放行（需明确策略并打日志）。
2. 至少覆盖三类敏感端点：
   - **登录** `/api/auth/login`（防暴力破解 —— 最高优先）
   - **IM 消息发送**（防刷屏）
   - **文件上传**（防滥用存储）
3. 配置化：阈值/窗口用 `@Value` 或配置属性，禁止硬编码。
4. 返回 **429** 与明确错误信息；限流命中需打日志（便于观测）。
5. 补测试：
   - 超过阈值返回 429（正向）
   - 未超过正常放行（正向）
   - Redis 不可用时的降级路径（不阻断业务）
   - 不同 key（IP/用户）互不干扰

### 验收
- `mvn -o test` > 1214（新增用例）且 0 失败
- 贴出 429 触发的实测输出

---

## §5 T3-2：定时工作流去内存态 + Cron 表达式（P1）

### 现状（已核实）
`backend-java/src/main/java/com/nocobase/workflow/WorkflowScheduler.java`
- **L27-28** 注释自认："上次触发时间记在内存，重启后会重置（即重启后立刻触发一次）；**多实例部署会重复触发**"
- **L39**：`/** workflowId → 上次触发时间(内存态,重启重置)。 */`
- **L48**：`@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)`
- **L67**：只解析 `intervalMinutes`（**无 Cron 表达式能力**）

### 要求
1. **触发时间持久化**：把"上次触发时间"落库（新增列 + 迁移，注意当前最高迁移为 `V40`，
   新迁移从 **V41** 起），或用 Redis 记录；重启不再重置。
2. **多实例去重**：加分布式锁（Redis SETNX + TTL 或数据库唯一约束），
   确保 3 副本下**每个周期只触发一次**。
3. **支持 Cron 表达式**：在 `intervalMinutes` 之外支持 `cron` 字段
   （可用 Spring `CronExpression` 或 Quartz cron 解析），并保持 `intervalMinutes` 向后兼容。
4. 补测试：
   - 重启后不重复立即触发（用持久化验证）
   - 多实例去重（模拟两个实例抢占）
   - Cron 表达式解析与触发时机
   - 非法 cron → 明确的错误（400 或启动校验），不许静默忽略

### 验收
- `mvn -o test` > 1214 且 0 失败
- 迁移脚本符合 Flyway 命名（V41__）
- 贴出去重/持久化的验证方式

---

## §6 T3-3：移动端响应式（P1）

### 现状（已核实）
- 仅两处生效：
  - `frontend/src/components/AppLayout.tsx` **L31** `isMobile = useMediaQuery('(max-width:768px)')`、
    **L196** 移动端底部导航（真实施）
  - `frontend/src/styles.css`：**仅 1 处** `@media`（且只覆盖 IM 布局）
- 其余 40+ 业务页面为固定桌面布局：`TableView` 横向滚动、`ProjectPage`/`BoardView`
  固定列宽 280-320px、`GanttView` 固定 240px 标题列
- `MOBILE_ADAPT.md` 自述"已支持响应式"与实际不符

### 要求（范围控制：先做关键页面，不要求全量）
1. 为以下高频页面做移动端适配：
   - **Wiki 阅读页 / 列表**（`pages/wiki/`）
   - **IM 消息页**（虽有部分 CSS，需完善）
   - **表格视图**（`pages/TableView.tsx`，移动端改为卡片式或横向优化）
   - **项目看板**（`features/project/BoardView.tsx`，移动端单列 + 可滚动）
2. 建立基础能力：把 `isMobile` 抽成 hook（如 `useIsMobile`）供页面复用，
   避免各处重复写 `useMediaQuery`。
3. 修正 `MOBILE_ADAPT.md`：如实写明当前适配范围与剩余待办（不许再写"已支持响应式"）。
4. 补前端测试：`useIsMobile` 的断点行为 + 至少一个页面在移动端渲染的用例。

### 验收
- `npm run test:run` > 271、`tsc` 0、`playwright` ≥ 64
- 贴出关键页面在 375px 宽度下的渲染说明（如 E2E 可覆盖则加用例）

---

## §7 明确不在本轮范围（严禁触碰）

以下属于 P1 其他项或 P2，**本轮不要做**：
IM 搜索索引化 / `MessageSearchService` 接线、Slack/Mattermost 入站消费者、
集成市场 UI、字段类型扩展（currency/rating 等）、视图级 group by、
CRDT 字符级协同、RocketChat、模板市场、原生移动 App、AI 深度能力。

> 若你在排查中发现上述某项**阻塞**了本轮任务，先说明再动手，不许顺手改。

---

## §8 交付自检清单

**T1**
- [ ] `if(!enabled)` 分支打了 WARN（含配置提示）
- [ ] `/ask` fallback 也说明了回退原因
- [ ] 贴出 WARN 实际输出

**T2**
- [ ] 11 个失败逐个说明根因
- [ ] asyncio 8 个已修（未用 skip/删测试）
- [ ] feishu/mattermost 2 个判定为实现 bug 或测试错误并修复
- [ ] `pytest tests/` → 0 failed

**T3-1 限流**
- [ ] 覆盖登录/消息/上传
- [ ] Redis 滑动窗口 + 降级策略
- [ ] 429 与日志
- [ ] 测试（429/放行/降级/key 隔离）

**T3-2 定时工作流**
- [ ] 触发时间持久化（V41 迁移）
- [ ] 多实例去重（分布式锁）
- [ ] 支持 Cron（兼容 intervalMinutes）
- [ ] 测试（去重/Cron/非法 cron）

**T3-3 移动端**
- [ ] 4 类关键页面适配
- [ ] `useIsMobile` hook
- [ ] `MOBILE_ADAPT.md` 已如实更新
- [ ] 前端测试已补

**门禁与红线**
- [ ] `mvn -o test` > 1214 且 0 失败
- [ ] `npm run test:run` > 271、`tsc` 0、`playwright` ≥ 64
- [ ] Python `pytest` 0 failed
- [ ] 新增 skip 0 / 删测试 0 / 弱化断言 0
- [ ] 未提交 `.env`
- [ ] 无 §7 之外的越界改动

---

## §9 提交规范

按栈分开提交，信息写清「现状 → 改动 → 实测数字」：
`[java]`（T1、T3-1、T3-2）、`[python]`（T2）、`[frontend]`（T3-3）、`[docs]`（MOBILE_ADAPT.md）。
**Cron/持久化若涉及迁移脚本，随 `[java]` 一并提交。**
每提交一次都跑对应门禁。

---

## §10 回报要求（缺一即打回）

1. 五项任务逐项勾选，未完成写"未做"及原因
2. T1：WARN 日志实际输出
3. T2：11 个失败的根因清单 + 修复前后汇总行对比
4. T3-1：429 触发实测输出 + 新增用例名
5. T3-2：去重与 Cron 的验证方式 + V41 迁移文件名
6. T3-3：适配页面清单 + 前端测试数字
7. 四项门禁 + Python 测试数字（与基线对比）

---

## §11 审计口径（提前告知）

1. 复跑全部门禁与 Python 测试，核对数字
2. T1：把 `ai.enabled` 设为 false 亲自看 WARN 是否出现
3. T2：确认没有用 skip/删测试/放宽断言
4. T3-1：连续请求登录接口验证 429
5. T3-2：查持久化记录与锁；构造非法 cron 看是否明确报错
6. T3-3：用 375px 宽度实测页面
7. 反作弊与范围检查（§7 越界一律打回）

一句话：**这轮要的是真修 + 真数字，尤其是 T2 不许靠跳过测试变绿。**

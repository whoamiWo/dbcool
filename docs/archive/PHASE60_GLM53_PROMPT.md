# Phase 60 投喂提示词（投喂 GLM-5.3）

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3（Kilo Code）**
> 基线提交：`f43ba90`（PHASE 58 + 59 已全部闭环并推送）
> 创建日期：2026-09-30
> **用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行**
> 详细规格（含文件行号证据）见同目录 `PHASE60_GLM53_P1_TASKS.md`

---

## 背景：本轮做什么

PHASE 58（4 项 P0）与 PHASE 59（RabbitMQ、跨服务 JWT）均已闭环。
本轮做 5 件事：
- **T1** 给 AI 静默降级分支补 WARN 日志（小但关键）
- **T2** 修 Python 11 个既有测试失败（**不许靠 skip 变绿**）
- **T3-1** 全局 API 限流（登录/消息/上传）
- **T3-2** 定时工作流去内存态 + 支持 Cron
- **T3-3** 移动端响应式（关键页面）

---

## 投喂提示词（以下整段复制）

```
请执行 Phase 60 任务。基线 origin/main = f43ba90（PHASE 58 + 59 已闭环）。
本轮做 5 件事：T1 补降级日志、T2 修 Python 既有 11 个失败、T3-1 全局限流、
T3-2 定时工作流去内存态 + Cron、T3-3 移动端响应式。

执行前必读：同目录 PHASE60_GLM53_P1_TASKS.md（每项含文件行号证据）。
冲突时以该任务书的行号证据为准。

============================================================
§0 严禁回滚（以下均已推送）
============================================================
3d06532 [java] P0-3 batch-upsert + jsonb 序列化
6441307 [python] P0-1 备份覆盖 Postgres 主库
17de634 [frontend] P0-2 钉钉/企微 405
b3afd73 [infra] P0-4 Huddle 粘滞 + POSTGRES_*/REDIS_* 注入
809eba5 [infra] 全局排查 localhost（AI_PYTHON_URL）
5d8203a [infra] RabbitMQ SPRING_RABBITMQ_* + 测试隔离 MQ + JWT 多算法
f43ba90 [infra] 清理 EOF 残留 + AI_ENABLED 注入

门禁基线（执行前复跑，最终须 ≥ 基线且 0 失败）：
  mvn -o test = 1214/0/0/0 BUILD SUCCESS ｜ npm run test:run = 271 ｜ tsc = 0 ｜ playwright = 64
  cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q
    = 11 failed, 32 passed, 1 skipped（T2 目标：failed → 0）

============================================================
§1 红线（违反即打回）
============================================================
1. 严禁 it.skip / @Disabled / 删测试 / 弱化断言 / 改断言阈值。
2. 严禁臆造 API/字段名，先确认再写。
3. 严禁「只打日志」式修复；严禁 catch 后仅 console.error 或空 catch
   （但 T1 要求的补日志是正当的）。
4. 严禁提交 .env 或密钥明文。
5. **严禁扩大范围**：只做 T1/T2/T3-1/T3-2/T3-3（见 §6 排除项）。
6. 每项修复必须给实测输出。

============================================================
§2 T1：给 AI 降级分支补 WARN 日志
============================================================
现状（已核实）：
  AiAssistantService.java L38  @Value("${ai.enabled:false}")
  L87-89  if (!enabled) { return Map.of("code",0,"message","AI 未启用", ...); }
  → **该分支不打任何日志**

为什么必须修：CodeBuddy 排查「AI 端点一直降级」时，因日志里完全没有输出，
先后误判为网络问题、JWT 问题、限流问题，绕了一大圈才发现只是 ai.enabled=false。
**静默降级 = 排查黑洞**。

要求：
1. 在 if(!enabled) 分支加 log.warn，内容须含：
   - ai.enabled=false、AI 助手未启用
   - 需配置 ai.enabled 与 ai.python-url
   - 当前 pythonUrl（脱敏，便于确认配置是否注入）
2. WikiController 中 /ask 的 fallback（约 L776-789，拼接
   「（AI 服务不可用，以下为知识库检索结果）」）也应加 WARN 说明为何回退。
3. 补一条测试：ai.enabled=false 时返回降级文案（不得影响既有 1214 用例）。
验收：mvn ≥1214 且 0 失败；贴出 WARN 实际输出。

============================================================
§3 T2：修 Python 11 个既有失败（不许靠 skip 变绿）
============================================================
实测：pytest tests/ -q → 11 failed, 32 passed, 1 skipped
构成：
  - 8 个 test_r11.py（TestSlidingWindowRateLimiter / TestLLMCache / TestQuotaService）
    → RuntimeError: Event loop is closed / attached to a different loop
  - 2 个 test_r11_integration_verify.py（test_feishu_signature_invalid、
    test_mattermost_token_match）→ 签名断言失败
  - 1 个其他（需你自行确认并归类）
相关配置：pyproject.toml L46 pytest-asyncio>=0.24.0；L89 asyncio_mode = "auto"
本机 Python 为 3.14。

要求：
1. **先分类再修**：逐个列出 11 个失败的根因，不许笼统说"既有问题"。
2. asyncio 8 个：
   - 确认 Python 3.14 与 pytest-asyncio 版本兼容性；
   - 可选修法：升级 pytest-asyncio / 调整 asyncio_mode / 在 conftest.py 提供
     event_loop 或 anyio fixture —— 选一种并说明理由；
   - **严禁**用 pytest.mark.skip 或删测试来"修"。
3. feishu/mattermost 2 个：
   - 先判定是**测试断言写错**还是**签名实现真有 bug**；
   - 实现 bug → 修实现；测试写错 → 修测试并说明；
   - **严禁**放宽断言（如只断言"不为空"）来通过。
4. 目标：pytest tests/ → **0 failed**（43 passed, 1 skipped）。
验收：贴修复前后汇总行对比 + 11 个根因清单。

============================================================
§4 T3-1：全局 API 限流
============================================================
现状：现有限流只有 workflow/TriggerRateLimiter.java（Redis ZSET，
被 WorkflowTriggerListener 调用）；**登录、消息发送、文件上传、API Key 均无全局限流**。
可参考的过滤器写法：apikey/ApiKeyFilter.java、auth/MdcFilter.java（OncePerRequestFilter）。

要求：
1. 新增全局限流过滤器（建议 com.nocobase.ratelimit 包），Redis 滑动窗口
   （复用 TriggerRateLimiter 的 ZSET 思路），Redis 不可用时降级策略需明确并打日志。
2. 至少覆盖：登录 /api/auth/login（最高优先，防暴力破解）、IM 消息发送、文件上传。
3. 阈值/窗口配置化（@Value 或配置属性），禁止硬编码。
4. 返回 429 + 明确信息；限流命中打日志。
5. 补测试：超阈值 429、未超正常放行、Redis 不可用降级、不同 key（IP/用户）隔离。
验收：mvn >1214 且 0 失败；贴 429 实测输出。

============================================================
§5 T3-2：定时工作流去内存态 + Cron
============================================================
现状（已核实）：WorkflowScheduler.java
  L27-28 注释自认："上次触发时间记在内存，重启后会重置（重启后立刻触发一次）；
                   **多实例部署会重复触发**"
  L39  /** workflowId → 上次触发时间(内存态,重启重置)。 */
  L48  @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
  L67  只解析 intervalMinutes（**无 Cron**）

要求：
1. 触发时间持久化：落库（新增列 + 迁移，当前最高为 V40，新迁移从 **V41** 起）或 Redis。
2. 多实例去重：分布式锁（Redis SETNX + TTL 或 DB 唯一约束），
   确保 3 副本下每周期只触发一次。
3. 支持 Cron 表达式（可用 Spring CronExpression），并保持 intervalMinutes 向后兼容。
4. 补测试：重启后不重复立即触发、多实例去重、Cron 解析与触发时机、
   非法 cron → 明确报错（不许静默忽略）。
验收：mvn >1214 且 0 失败；贴去重/Cron 的验证方式与 V41 迁移文件名。

============================================================
§6 T3-3：移动端响应式（范围受控）
============================================================
现状：仅两处生效 —— AppLayout.tsx L31 useMediaQuery('(max-width:768px)') +
L196 移动端底部导航；styles.css **仅 1 处** @media（且只覆盖 IM）。
其余 40+ 页面为固定桌面布局（TableView 横向滚动、BoardView 固定列宽、
GanttView 固定 240px 标题列）。MOBILE_ADAPT.md 自述"已支持响应式"与实际不符。

要求（先做关键页面，不要求全量）：
1. 适配：Wiki 阅读/列表、IM 消息页、表格视图（移动端卡片式或横向优化）、
   项目看板（单列 + 可滚动）。
2. 抽 useIsMobile hook 供复用，避免各处重复 useMediaQuery。
3. **如实更新 MOBILE_ADAPT.md**（不许再写"已支持响应式"）。
4. 补前端测试：useIsMobile 断点行为 + 至少一个页面移动端渲染用例。
验收：npm run test:run >271、tsc 0、playwright ≥64；贴 375px 下的渲染说明。

============================================================
§7 明确不做（严禁触碰）
============================================================
IM 搜索索引化 / MessageSearchService 接线、Slack/Mattermost 入站消费者、
集成市场 UI、字段类型扩展、视图级 group by、CRDT 字符级协同、RocketChat、
模板市场、原生移动 App、AI 深度能力。
若其中某项阻塞本轮任务，先说明再动手，不许顺手改。

============================================================
§8 环境与命令
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
- 容器内 localhost 指向容器自身：服务间地址必须 @Value 可配 + compose 服务名。

============================================================
§9 提交与回报
============================================================
按栈分开提交：[java]（T1、T3-1、T3-2 + V41 迁移）、[python]（T2）、
[frontend]（T3-3）、[docs]（MOBILE_ADAPT.md）。
信息写清「现状 → 改动 → 实测数字」，每次提交跑对应门禁。严禁提交 .env。

回报必须包含：
1. 五项逐项勾选（未完成写"未做"及原因）
2. T1 WARN 实际输出
3. T2 11 个失败根因清单 + 修复前后汇总行对比
4. T3-1 429 实测输出 + 新增用例名
5. T3-2 去重/Cron 验证方式 + V41 迁移文件名
6. T3-3 适配页面清单 + 前端测试数字
7. 四项门禁 + Python 测试数字（与基线对比）

============================================================
§10 审计口径
============================================================
① 复跑全部门禁与 Python 测试；② 把 ai.enabled 设 false 亲自看 WARN；
③ 确认 T2 没用 skip/删测试/放宽断言；④ 连续请求登录接口验证 429；
⑤ 查持久化与锁，构造非法 cron 看是否明确报错；⑥ 375px 实测页面；
⑦ 反作弊与范围检查（§7 越界一律打回）。

一句话：**这轮要的是真修 + 真数字，尤其 T2 不许靠跳过测试变绿。**
```

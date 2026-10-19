# Phase 60 返工任务书（投喂 GLM-5.3）

**编排方**：CodeBuddy　**执行方**：GLM-5.3（Kilo Code）　**审计/验收方**：CodeBuddy
**基线提交**：`46b2c1b`（T1/T2/T3-2 已通过；T3-1 不通过；T3-3 未提交）
**创建日期**：2026-09-30

> 本任务书的「现状」由 CodeBuddy 于 2026-09-30 **实测核实**（附命令与输出），可直接采信。

---

## §0 上轮审计结论

| 任务 | 判定 | 说明 |
|---|---|---|
| T1 补 WARN 日志 | ✅ 通过 | `AiAssistantService` L89 已有 `log.warn("[AI] ai.enabled=false...")` |
| T2 修 11 个失败 | ✅ **通过（优秀）** | `pytest` = **43 passed, 1 skipped**，11 failed → 0 failed，未靠 skip |
| T3-1 全局限流 | ❌ **不通过（假生效）** | 单测绿，但**端到端实测不触发 429**，见 §2 |
| T3-2 定时工作流 | ✅ 通过 | `V41__workflow_last_triggered_at.sql` + Redis SETNX 锁 + `CronExpression` |
| T3-3 移动端 | ✅ 测试通过 / ❌ **未提交** | vitest **275**（+4）、`tsc` 0；改动仍在工作区 |

门禁（我实测）：`mvn` **1223/0/0/0** BUILD SUCCESS、`vitest` 275、`tsc` 0、Python 43 passed ✅

**本轮只做 R1（修 T3-1）与 R2（提交 T3-3）。其余不要动。**

---

## §1 全局红线

1. 严禁 `it.skip` / 删测试 / 弱化断言 / 改断言阈值。
2. 严禁回滚已通过的 T1/T2/T3-2。
3. **严禁提交 `.env`** 或密钥明文。
4. **严禁扩大范围**：只做 R1、R2。
5. 每项必须有**实测输出**，不接受"配置了应该就好了"。

**本项目方法论（已踩多次）**：
- 容器内 `localhost` 指向容器自身；服务间地址必须 `@Value` 可配 + compose 服务名。
- **单测通过 ≠ 端到端生效**（T3-1 就是典型：Limiter 单测绿，但过滤器没进链）。

---

## §2 R1：让全局限流真生效（T3-1 返工）

### 现状（CodeBuddy 实测，勿重复排查）

代码层看起来是完整的：
- `backend-java/src/main/java/com/nocobase/ratelimit/` 下有三个文件：
  `GlobalRateLimitFilter.java`、`GlobalRateLimiter.java`、`GlobalRateLimitProperties.java`
- 覆盖了三类路径（L25-27）：
  ```java
  LOGIN_PATTERN     = ^/api/auth/login$
  IM_SEND_PATTERN   = ^/api/im/messages$
  UPLOAD_PATTERN    = ^/api/upload$
  ```
- 命中返回 429（L64）
- 阈值：`GlobalRateLimitProperties.Login` → `limit = 5`、`windowSeconds = 300`（即 **5 次 / 5 分钟**）

**但端到端实测不生效**：
```
重建镜像 + 重启容器后，连续 8 次登录请求
→ 第 1~8 次全部 HTTP 401，没有任何一次 429
（阈值是 5 次/300 秒，第 6 次就该触发）
```

### 三个根因（均已核实）

1. **过滤器没进对位置**：`GlobalRateLimitFilter` 只有 `@Component`（L17），
   **没有 `@Order`，也没在 `SecurityConfig` 注册**
   → 默认 `LOWEST_PRECEDENCE`，排在 **Spring Security 之后**；
   登录失败时 Security 走 `AuthenticationEntryPoint` 返回 401 并**终止过滤器链**，
   限流过滤器根本没执行。
2. **配置属性未启用**：`@ConfigurationProperties(prefix = "ratelimit")`（L7）
   但全仓**没有** `@EnableConfigurationProperties` / `@ConfigurationPropertiesScan`
   → 外部配置无法覆盖阈值（目前只靠字段默认值生效）。
3. **测试没覆盖顺序**：测试只有 `GlobalRateLimiterTest`（测 Limiter 本身），
   **没有走完整过滤器链的端到端用例** → 所以单测绿但线上无效。

### 项目既有惯例（必须遵循）

`SecurityConfig.java` L96-100 的写法：
```java
.addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterAfter(new MdcFilter(), JwtAuthFilter.class);
```
→ 本项目**不用 `@Order`**，而是在 `SecurityConfig` 里显式 `addFilterBefore/After`。

### 要求

1. **注册过滤器**（推荐与项目惯例一致）：
   在 `SecurityConfig` 中把 `GlobalRateLimitFilter` 注册到
   `UsernamePasswordAuthenticationFilter.class` **之前**（与 apiKeyFilter/jwtAuthFilter 同款），
   或显式声明 `@Order`（值需早于 Security，如 `SecurityProperties.DEFAULT_FILTER_ORDER - 1`）。
   **二选一并说明理由**；无论哪种，必须保证限流**早于**认证失败返回 401。
2. **启用配置属性**：加 `@ConfigurationPropertiesScan`（或 `@EnableConfigurationProperties`），
   使 `ratelimit.*` 可被 `application.yml` / 环境变量覆盖。
3. **补端到端测试**（关键，缺这个不予验收）：
   用 **MockMvc 走完整过滤器链**（`@SpringBootTest` + `@AutoConfigureMockMvc`），
   断言：连续请求 `/api/auth/login` 超过阈值后返回 **429**；未超过前正常放行。
   **不要**只测 `GlobalRateLimiter` 本身。
4. **实测证据**：重建镜像 → 重启容器 → 连续请求登录接口，
   贴出**第 6 次（或对应阈值）返回 429 的实际输出**。

### 验收
- `mvn -o test` > 1223 且 0 失败（新增端到端用例）
- 容器内实测出现 **429**（贴命令与逐次 HTTP 码）
- `docker compose logs backend-java` 能查到限流命中日志

---

## §3 R2：提交 T3-3 前端移动端改动

### 现状（已核实）
工作区未提交改动（9 文件 +223/-68）：
```
frontend/src/components/AppLayout.tsx          （4）
frontend/src/features/im/ImLayout.tsx          （62）
frontend/src/pages/KanbanView.tsx              （11）
frontend/src/pages/TableView.tsx               （4）
frontend/src/pages/wiki/WikiPageList.tsx       （32）
frontend/src/pages/wiki/WikiPageRead.tsx       （63）
frontend/src/styles.css                        （95）
frontend/src/pages/wiki/WikiPageList.test.tsx  （18，新增用例）
frontend/e2e/wiki-permission.spec.ts           （2）
```
新增：`frontend/src/hooks/useIsMobile.ts`、`frontend/src/hooks/useIsMobile.test.ts`

已实测：`npm run test:run` **275 passed**（基线 271 → +4）、`npx tsc --noEmit` **0** ✅

### 一处需你确认并说明
`frontend/e2e/wiki-permission.spec.ts` 的改动是把断言
`'知识库'` → `'📚 知识库'`（标题加了 emoji）。
- 我判断这是**同步 UI 文案**（非弱化断言），可接受；
- 但请在提交信息中**明确说明**：是为适配新增的 emoji 标题而同步断言，
  不是为了让测试通过而放宽。

### 要求
1. 按 `[frontend]` 提交上述改动（`[docs]` 若有 MOBILE_ADAPT.md 更新）。
2. 提交信息写清「现状 → 改动 → 实测数字（275 passed / tsc 0）」。
3. 提交后确认 `git status` 不含 `.env`。

### 验收
- 提交 hash 列表
- 确认工作区无遗留（除 `PHASE59_GLM53_FINALIZE_PROMPT.md` 等未跟踪文档外）

---

## §4 明确不做（严禁触碰）

T1、T2、T3-2 已通过，**不要重做或"优化"**；
也不要碰：`MessageSearchService` 接线、Slack/Mattermost 入站、集成市场 UI、
字段类型扩展、视图 group by、CRDT 字符级、RocketChat、模板市场、原生 App、AI 深度。

---

## §5 自检清单

**R1**
- [ ] 过滤器已注册（SecurityConfig addFilterBefore 或 @Order），位置早于认证失败
- [ ] `@ConfigurationProperties` 已启用
- [ ] 补了 MockMvc **端到端**用例（走完整过滤器链，断言 429）
- [ ] 容器内实测出现 429（贴逐次 HTTP 码）
- [ ] 限流命中日志可见

**R2**
- [ ] 前端 9 文件 + 2 新文件已提交
- [ ] 提交信息说明了 e2e 断言同步 emoji 的原因
- [ ] 不含 `.env`

**门禁**
- [ ] `mvn -o test` > 1223 且 0 失败
- [ ] `npm run test:run` ≥ 275、`tsc` 0
- [ ] Python `pytest` 43 passed / 1 skipped
- [ ] 新增 skip 0 / 删测试 0 / 弱化断言 0

---

## §6 提交与回报

- `[java]` R1（限流注册 + 配置启用 + 端到端测试）
- `[frontend]` R2（移动端改动）

回报必须包含：
1. R1：容器内逐次登录请求的 HTTP 码（须出现 429）+ 限流日志片段 + 新增用例名
2. R1：过滤器注册方式（addFilterBefore 还是 @Order）及理由
3. R2：提交 hash + e2e 断言同步说明
4. 全部门禁数字

---

## §7 审计口径

我会：① 复跑门禁；② **亲自重建镜像重启容器，连续请求登录接口验证 429 真的出现**；
③ 检查是否真有 MockMvc 端到端用例（而非只测 Limiter）；
④ 确认 `ratelimit.*` 可被配置覆盖；⑤ 核对提交范围不含 `.env`、无越界。

一句话：**限流要在真实请求里看到 429，不是在单测里看到绿。**

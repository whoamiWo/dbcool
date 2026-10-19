# Phase 60 返工投喂提示词（投喂 GLM-5.3）

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3（Kilo Code）**
> 基线：`46b2c1b`（T1/T2/T3-2 已通过；T3-1 不通过；T3-3 未提交）
> 创建日期：2026-09-30
> **用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行**
> 详细规格见同目录 `PHASE60_GLM53_REWORK_TASKS.md`

---

## 背景

上轮 5 项任务：T1、T2、T3-2 **通过**（T2 尤其优秀，11 failed → 0 failed）；
**T3-1 不通过**（单测绿但端到端不生效）；**T3-3 未提交**。
本轮只做两件事：**R1 让限流真生效**、**R2 提交前端改动**。

---

## 投喂提示词（以下整段复制）

```
请执行 Phase 60 返工。基线 origin/main = 46b2c1b。
上轮结果：T1（WARN 日志）、T2（修 11 个失败，43 passed 0 failed）、
T3-2（定时工作流 V41 + Redis 锁 + Cron）**均已通过**；
但 **T3-1 全局限流不通过**（单测绿，端到端不触发 429）；
**T3-3 移动端改动未提交**。
本轮只做 R1 与 R2，其余不要动。

============================================================
§0 严禁回滚 / 严禁越界
============================================================
已通过：T1（AiAssistantService L89 的 WARN）、T2（pytest 43 passed, 1 skipped）、
T3-2（V41__workflow_last_triggered_at.sql + Redis SETNX + CronExpression）
—— 不要重做、不要"顺手优化"。
本轮只做：R1（限流真生效）、R2（提交前端）。
不要碰：MessageSearchService 接线、Slack/Mattermost 入站、集成市场 UI、
字段类型扩展、视图 group by、CRDT 字符级、RocketChat、模板市场、原生 App、AI 深度。

============================================================
§1 R1：让全局限流真生效（关键）
============================================================
你的代码本身是完整的：
  ratelimit/ 下 GlobalRateLimitFilter / GlobalRateLimiter / GlobalRateLimitProperties
  覆盖 L25-27：^/api/auth/login$、^/api/im/messages$、^/api/upload$
  命中返回 429（L64）；Login 阈值 limit=5、windowSeconds=300

**但实测不生效**（我重建镜像重启容器后）：
  连续 8 次 POST /api/auth/login → 第 1~8 次全部 HTTP 401，一次 429 都没有
  （阈值 5 次/300 秒，第 6 次就该触发）

三个根因（我已核实）：
1. **过滤器没进对位置**：GlobalRateLimitFilter 只有 @Component（L17），
   无 @Order、也没在 SecurityConfig 注册 → 默认 LOWEST_PRECEDENCE，
   排在 Spring Security **之后**；登录失败时 Security 走 AuthenticationEntryPoint
   返回 401 并**终止过滤器链** → 你的限流过滤器根本没执行。
2. **配置属性未启用**：@ConfigurationProperties(prefix="ratelimit")（L7），
   但全仓没有 @EnableConfigurationProperties / @ConfigurationPropertiesScan
   → 外部配置无法覆盖阈值。
3. **测试没覆盖顺序**：只有 GlobalRateLimiterTest（测 Limiter 本身），
   没有走完整过滤器链的端到端用例 → 单测绿但线上无效。

项目既有惯例（必须遵循）—— SecurityConfig L96-100：
  .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
  .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
  .addFilterAfter(new MdcFilter(), JwtAuthFilter.class);
→ 本项目不用 @Order，而在 SecurityConfig 里显式 addFilterBefore/After。

要求：
1. 注册过滤器（二选一，说明理由）：
   A）推荐：在 SecurityConfig 中
      .addFilterBefore(globalRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
      （与 apiKeyFilter/jwtAuthFilter 同款）
   B）或显式 @Order（值需早于 Security，如 SecurityProperties.DEFAULT_FILTER_ORDER - 1）
   → 无论哪种，必须保证限流**早于**认证失败返回 401。
2. 启用配置属性：加 @ConfigurationPropertiesScan 或 @EnableConfigurationProperties，
   使 ratelimit.* 可被 application.yml / 环境变量覆盖。
3. **补端到端测试（缺这个不予验收）**：
   用 MockMvc 走**完整过滤器链**（@SpringBootTest + @AutoConfigureMockMvc），
   断言连续请求 /api/auth/login 超阈值后返回 **429**、未超之前正常放行。
   **不要只测 GlobalRateLimiter 本身。**
4. **实测证据**：重建镜像 → 重启容器 → 连续请求登录接口，
   贴出**出现 429 的逐次 HTTP 码**。

重建镜像命令（主 Dockerfile 拉 eclipse-temurin 极慢，禁用）：
  cd backend-java && mvn -o package -DskipTests
  docker build -f Dockerfile.offline -t nocobase-backend-java:latest .
  cd .. && docker compose up -d --no-build backend-java
验证命令：
  for i in $(seq 1 8); do curl -s -o /dev/null -w "%{http_code}\n" -X POST \
    http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"wrong"}'; done

验收：mvn >1223 且 0 失败；容器内实测出现 429；限流命中日志可见。

============================================================
§2 R2：提交 T3-3 前端移动端改动
============================================================
工作区未提交（9 文件 +223/-68）：
  AppLayout.tsx、ImLayout.tsx、KanbanView.tsx、TableView.tsx、
  wiki/WikiPageList.tsx、wiki/WikiPageRead.tsx、styles.css、
  wiki/WikiPageList.test.tsx、e2e/wiki-permission.spec.ts
新增：hooks/useIsMobile.ts、hooks/useIsMobile.test.ts
已实测：npm run test:run = 275 passed（基线 271 → +4）、npx tsc --noEmit = 0 ✅

一处需你确认并说明：
  e2e/wiki-permission.spec.ts 把断言 '知识库' 改为 '📚 知识库'。
  我判断这是**同步 UI 新增的 emoji 标题**（非弱化断言），可接受；
  但请在提交信息中明确说明这一点，不要让人误以为是为了让测试通过而放宽。

要求：
1. 按 [frontend] 提交（MOBILE_ADAPT.md 若有更新放 [docs]）。
2. 提交信息写清「现状 → 改动 → 实测数字 275 passed / tsc 0」。
3. 提交后确认 git status 不含 .env。

============================================================
§3 回报要求（缺一即打回）
============================================================
1. R1：容器内逐次登录请求的 HTTP 码（**必须出现 429**）+ 限流日志片段 + 新增用例名
2. R1：过滤器注册方式（addFilterBefore 还是 @Order）及理由
3. R2：提交 hash + e2e 断言同步 emoji 的说明
4. 全部门禁数字：mvn（>1223）、npm run test:run（≥275）、tsc（0）、
   playwright（≥64）、pytest（43 passed / 1 skipped）

============================================================
§4 审计口径
============================================================
① 复跑门禁；② **亲自重建镜像重启容器，连续请求登录接口验证 429 真的出现**；
③ 检查是否真有 MockMvc 端到端用例（而非只测 Limiter）；
④ 确认 ratelimit.* 可被配置覆盖；⑤ 核对提交范围不含 .env、无越界。

一句话：**限流要在真实请求里看到 429，不是在单测里看到绿。**
```

# Phase 59 收尾提示词（投喂 GLM-5.3）— 已按审计实测更正

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3（Kilo Code）**
> 收尾日期：2026-09-30　基线：`0e3fb3a`（你的 F1/F2 改动**仍在工作区未提交**）
> **用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行**
>
> ⚠️ 本版**更正了上一版的一处误判**：CodeBuddy 初判「40 errors 是虚报」不成立，
> 实测确认 40 errors **真实存在且由 F1 引入**（见 §2）。以本版为准。

---

## 审计实测结论（CodeBuddy 2026-09-30 亲自执行）

| 项 | 结论 | 实测证据 |
|---|---|---|
| **F1 效果** | ✅ 生效 | 容器内 `AmqpConnectException` = **0**（修复前大量刷屏）；compose L128-131 注入正确 |
| **40 errors** | ❌ **真实，且是 F1 引入的回归** | `mvn -o test`：`Tests run: 1214, Failures: 0, **Errors: 40**` + **BUILD FAILURE**；根因是 `com.rabbitmq.client.AuthenticationFailureException: ACCESS_REFUSED - Login was refused`（×15）→ `FatalListenerStartupException` → 上下文启动失败 |
| **F2 效果** | ✅ 生效 | 用 Java 签发的 token 直连 Python `/api/ai/chat` → **成功返回** `{"response":"[simulated] Model=gpt-4..."}`；你判断 Java 实际签发 **HS512** 是正确的（三算法列表必须包含 HS512） |
| **AI /ask 仍降级** | ⚠️ **与你无关的另一个原因** | 容器内 curl `backend-python:8000` = **HTTP 200**、token 校验通过；真正原因是 `@Value("${ai.enabled:false}")` 默认 false 且 compose **未注入 `AI_ENABLED`** → `callLlm` 走 `if(!enabled)` 直接返回降级文案（该分支不打日志） |
| **Python 测试** | ⚠️ 10 failed（既有） | 全量 `pytest tests/` = **10 failed, 33 passed, 1 skipped**；失败全部是 `RuntimeError: Event loop is closed`（pytest-asyncio 在 Python 3.14 的环境问题），**非本轮引入**，但你回报的「14 passed / 1 skipped」只是子集（新增 6 + 备份 8），未反映全量 |
| **提交** | ❌ 未提交 | `git log` 最新仍为 `0e3fb3a` |
| **.env** | ✅ 安全 | 未被 git 跟踪（.gitignore 生效） |

---

## 投喂提示词（以下整段复制）

```
请执行 Phase 59 收尾。审计已实测确认：F1、F2 **技术均生效**，不用重做；
但存在一个**由 F1 引入的真实回归**（40 errors）必须修复，且改动**仍未提交**。

============================================================
§0 技术部分已通过，严禁改动（改坏即打回）
============================================================
- F1 compose L128-131 的 SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD（保留原
  RABBITMQ_HOST/PORT）→ 容器内 MQ 已实测 0 次 AmqpConnectException ✅
- F2 config.py L37 jwt_algorithms=["HS256","HS384","HS512"]、security.py L31 改用列表、
  test_jwt_algorithms.py 6 个真断言用例 ✅
  （特别肯定：你判断 Java 实际签发 HS512 是对的，只支持 HS384 会失败）
以上不要改。

============================================================
§1 必须修复：40 errors（F1 引入的回归）
============================================================
实测：
  cd backend-java && mvn -o test
  → Tests run: 1214, Failures: 0, Errors: 40, Skipped: 0   BUILD FAILURE

根因（mvn 日志原文）：
  Caused by: com.rabbitmq.client.AuthenticationFailureException:
             ACCESS_REFUSED - Login was refused using authentication mechanism PLAIN （×15）
  Caused by: org.springframework.amqp.rabbit.listener.exception.FatalListenerStartupException:
             Authentication failure （×9）
  → Failed to start bean ...internalRabbitListenerEndpointRegistry → 上下文启动失败 → 40 个类 error

原因：你把 RabbitMQ 重置为 nocobase 用户后，**本地/CI 跑 mvn test 时仍以 guest 连接
localhost:5672**（Spring 默认凭据），而 guest 已被移除 → 认证被拒。
（容器内部署是对的，问题只出在测试环境。）

要求（二选一，优先 A）：
A）**测试环境隔离 MQ**（推荐，单测本就不该依赖真实 MQ）：
   - 在 test profile 禁用 MQ listener，例如 application.yml 的 test 段加
     `spring.rabbitmq.listener.simple.auto-startup: false`，
     或让 AmqpConfig 在 test profile 下不注册 listener 容器；
   - 确保 `mvn -o test` 恢复为 1214/0/0 且 BUILD SUCCESS。
B）给测试环境注入正确凭据：
   - 在 CI/本地环境变量提供 SPRING_RABBITMQ_USERNAME=nocobase / PASSWORD=<实际值>，
     或写进 test profile 配置；**不要把真实密码硬编码进仓库**。
验收：`mvn -o test` 输出 `Tests run: 1214, Failures: 0, Errors: 0` + **BUILD SUCCESS**，
并贴完整汇总行。

============================================================
§2 如实报告 Python 测试数字（不要只报子集）
============================================================
实测全量：cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q
  → **10 failed, 33 passed, 1 skipped**
失败全部是 `RuntimeError: Event loop is closed` / "attached to a different loop"
（pytest-asyncio 与 Python 3.14 的环境问题，涉及 SlidingWindowRateLimiter、LLMCache、
QuotaService 等），**与你的改动无关**。
要求：
- 如实贴出**全量**数字（不要只贴新增 6 + 备份 8 的子集 14）；
- 说明这 10 个失败是既有异步测试环境问题；
- （可选加分）若你能顺手修复事件循环问题，需保证不弱化任何断言。

============================================================
§3 AI 端点降级——已知原因，本轮可选处理
============================================================
容器内 /ask 仍返回「（AI 服务不可用，以下为知识库检索结果）」，但**不是你的问题**：
- 容器内 curl http://backend-python:8000/api/health → HTTP 200（网络通）
- 用 Java token 直连 Python /api/ai/chat → 成功（F2 生效）
- 真正原因：AiAssistantService L38 `@Value("${ai.enabled:false}")` 默认 false，
  且 docker-compose.yml **未注入 AI_ENABLED** → callLlm 在 L87 直接返回降级文案
  （该分支不打日志，所以日志里没有 warn）
本轮要求：
- **最低要求**：在回报中明确说明这一点（证明你查过）；
- **可选**：若你要让 AI 真启用，在 compose 给 backend-java 加 `AI_ENABLED: "true"`
  （注意 Python 侧无 LLM 密钥时会走 simulated 响应，这是预期行为）。

============================================================
§4 提交（红线）
============================================================
按栈分开提交并推送：
- [infra]  docker-compose.yml（SPRING_RABBITMQ_* 注入；若加了 AI_ENABLED 也在此）
- [python] config.py、security.py、tests/test_jwt_algorithms.py
- [java]   若按 §1-A 改了 application.yml 或 AmqpConfig

红线：
- **严禁提交 .env**（含 JWT_SECRET / POSTGRES_PASSWORD / INTERNAL_SERVICE_TOKEN）。
  务必确认 git status 中不含 .env。
- 严禁 it.skip / 删测试 / 弱化断言 / 改断言阈值。
- 严禁越界改动其他文件。

============================================================
§5 回报要求（缺一即不予验收）
============================================================
1. `mvn -o test` 的完整汇总行（须为 1214 / 0 / 0 + BUILD SUCCESS）+ 修复方式说明
2. Python 全量 pytest 的真实汇总行（含 10 failed 的说明）
3. AI 端点的原因说明（ai.enabled 未启用）
4. 提交 hash 列表
5. 确认 git status 不含 .env

============================================================
§6 审计口径
============================================================
我会：① 复跑 mvn 与 pytest 核对数字；② 用 Java token 调 Python 确认 HS512 被接受、
错误密钥仍被拒；③ 查 MQ 日志；④ 核对提交范围不含 .env、无越界。

一句话：**40 errors 是真的，把它修掉，然后如实提交。**
```

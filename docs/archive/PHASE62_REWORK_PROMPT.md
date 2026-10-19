# PHASE62 返工 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。详细任务书见仓库根目录 `PHASE62_REWORK_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。这是 **PHASE62 的返工**：你的提交 `da65301` 经审计——**实现达标，但三项交付不合格**。本轮**不要求重写实现**，只补**测试**、**端到端实测**与**如实回报**。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`
- 后端：`backend-java`（Spring Boot 3 + JPA + Flyway，已应用到 V46）
- 前端：`frontend`（React 19 + MUI v9 + Vite）
- 单测跑 H2；端到端用 docker compose 的容器

## 1. 你做对的部分（**必须保留，不得回退**）

| 任务 | 证据 |
|---|---|
| T1 入站落地 | `InboundMessageService:121` `messageRepository.save`、`:128` `bridge.broadcast`、`:86` `isDuplicate` 幂等、`:106` **成员校验防越权**；未配置频道/映射失败均丢弃并 WARN |
| T1 幂等有 DB 保障 | `V45__integration_external_message_log.sql:10` `UNIQUE(source, external_message_id)` |
| T2 市场持久化 | `TenantIntegrationInstallEntity/Repository` 真落库 + `IntegrationsPage.tsx` + 前端测试 |
| T3 fail-close | `MattermostAppService:61-69`：`requireToken && 未配置` → `false` + WARN；开关 `integration.mattermost.require-token` 默认 true |
| T4 钉钉回调 | `DingTalkController:190-201` 缺头 401、验签失败 401；HMAC-SHA256 + Base64；按事件 ID 幂等 |
| 迁移安全 | V45/V46 未用 `CONCURRENTLY` ✅ |

实测门禁（CodeBuddy 亲跑）：`mvn -o test` **1242/0/0/0**、vitest **281**、tsc **0** ✅。

## 2. 三项不合格

### ❌ 后端零新增测试（1242 = 基线）

两个既有测试只是被**适配**（构造签名变了）：

```java
+inboundMessageService = mock(InboundMessageService.class);
-service = new MattermostAppService();
+service = new MattermostAppService(inboundMessageService);
```

后果：核心 `InboundMessageService` 的**落库 / 幂等 / 成员校验 / 映射**，以及 **T3 fail-close**、**T4 钉钉验签**，**零测试覆盖**；T1 要求的 5 条反向用例一条没写。违反红线「禁 mock 被测主路径 Service」。

### ❌ 未提供端到端实测输出

要求的是：容器内用**真实签名** POST `/api/slack/events` → 我方 `GET /api/im/messages` 能查到该消息。你的回报里没有这类输出 → 现在只有"代码看着对"，没有"跑起来真通"的证据。

### ❌ pytest 回报不实

你报 `11 passed`，实测是 **43 passed, 1 skipped**（本地需 `PYTHONPATH=src`）。上次报的是子集。

---

## 3. 本轮三项任务

### R1（P0）补**真跑**的测试，禁止 mock `InboundMessageService`

新增集成测试（`@DataJpaTest` 或 `@SpringBootTest` + 真实 DataSource），至少覆盖：

**入站落库与广播**
- 正常入站 → `im_message` 落一条，字段（tenant/channel/sender/content）正确

**幂等 / 防环**
- **同一 `externalId` 重复投递 → 只落一条**（关键防线）
- 不同 `externalId` → 各落一条

**安全与边界**
- 发送者**不是频道成员** → 丢弃，不落库
- 未配置目标频道 → 丢弃 + WARN，不 500
- 用户映射失败 → 丢弃
- `externalMessageId` 为空 → 丢弃
- `integration.inbound.enabled=false` → 丢弃

**T3 fail-close**
- 未配置 token 且 `require-token=true` → **401**
- 配置后：正确 token → 200；错误 token → 401

**T4 钉钉回调**
- 缺签名头 → 401；签名错误 → 401；签名正确 → 成功；**重复事件 ID → 幂等**

**Slack 验签**
- 时间戳超 5 分钟 → 401；签名错误 → 401 **且不落库**

> 判据：这些用例**故意改坏实现时必须失败**。若 mock 掉核心服务，用例永远绿 = 等于没写。

### R2（P0）容器内端到端实测（必须贴输出）

**Slack 入站**（配置项已确认，直接用）：

1. 注入配置（compose 环境变量）：
   - `SLACK_SIGNING_SECRET=<测试密钥>`（对应 `slack.signing-secret`，`SlackAppService:41`，默认空=fail-close）
   - `INTEGRATION_INBOUND_DEFAULT_CHANNEL_ID=<真实频道 id>`（对应 `InboundMessageService:39`）
   - `integration.inbound.enabled` 默认已 `true`（`:42`），不用改
2. 构造签名：
   ```
   sig_base = "v0:" + timestamp + ":" + rawBody
   signature = "v0=" + HMAC_SHA256(key=signingSecret, msg=sig_base).hex()
   头: X-Slack-Request-Timestamp: <秒级时间戳>
       X-Slack-Signature: <signature>
   ```
3. POST `/api/slack/events`，body 如：
   ```json
   {"type":"event_callback","event":{"type":"message","channel":"C123","user":"U123","text":"hello from slack","ts":"1710000000.000100"}}
   ```
4. 验证并**贴输出**：
   - 返回 200
   - `GET /api/im/messages?channelId=<目标频道>` **能查到该消息**
   - **重复 POST 同一 `ts` → 消息数不增加**（幂等）
   - 签名错误 → **401 且不落库**（贴消息数对比）

**钉钉回调**（同理）：注入 `dingtalk.app-secret`，按 `timestamp + sign`（HMAC-SHA256+Base64）构造正确/错误签名，验证 401 与幂等。

⚠️ 改代码后重建镜像**必须 `mvn -o clean package`**（`target/classes` 残留会让应用起不来——PHASE61 的 V43 已踩过）。

### R3（P1）如实回报 pytest 全量

跑法：`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`
基线 **43 passed, 1 skipped**。上次报的 `11 passed` 是子集（未加 `PYTHONPATH` 导致大量收集失败）。必须报全量。

## 4. 门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1242**（R1 必须新增测试）/ 0 failures / 0 errors |
| `npm run test:run` | ≥ **281** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（全量如实） |

## 5. 红线（违反即打回）

1. **严禁 mock 被测主路径 Service**（本轮特指 `InboundMessageService`）
2. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
3. **严禁**"只写单测、不做端到端实测"—— R2 必须给出容器内真实签名的请求与响应输出
4. **严禁**回报子集数字（pytest 必须全量）
5. **严禁**提交 `.env` / 密钥明文 / `__pycache__` / `target/` / `node_modules/`
6. **严禁**回滚已闭环提交（PHASE58–62）；**严禁**回退 §1 已达标的实现
7. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` 并用 `zipfile` 校验 jar

## 6. 教训

1. **"代码看着对" ≠ "跑通了"** —— 入站类改造三步齐全才算完成：① 落库+广播 ② 真跑的集成测试 ③ 容器内真实签名端到端实测。你过了①，卡在②③
2. **实现达标也可能零回归防线** —— 测试数不涨就是没加测试
3. **回报数字必须全量实测**（pytest `11 vs 43` 已第二次发生）
4. **端到端实测前先把配置项弄清楚**（`slack.signing-secret`、`integration.inbound.default-channel-id`），否则因默认空值 401，会误判成"功能坏了"
5. 沿用：容器 `localhost` 陷阱、过滤器注册、`@EnableScheduling`、分页语义、clean 构建

## 7. 回报必须给出（缺项打回）

1. R1：新增测试实际输出 + 说明"故意改坏实现时这些用例是否会失败"
2. R2：容器内实测完整输出（正确签名 200 + 消息查得到 + 重复投递幂等 + 错误签名 401 且不落库；钉钉同理）
3. R3：pytest 全量数字
4. 五项门禁实际输出
5. 改动文件清单 + `git log --oneline`（**必须已提交**）
6. 明确说明哪些项未做及原因

-----END PROMPT-----

## 投喂方式

1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：复跑门禁（确认 mvn **>1242**）+ 检查测试是否真跑（无 mock 核心 Service）+ 核对端到端实测输出

# PHASE62 返工任务需求单（提交 `da65301` 审计：实现达标，三项交付不合格）

> 交付方式：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 复验。
> 本轮**不要求重写实现**——T1–T4 的实现已达标，只补**测试**、**端到端实测**与**如实回报**。

---

## 一、审计结论（`da65301`）

### 1.1 实测门禁（CodeBuddy 亲跑）

| 门禁 | 执行方报 | 实测 | 判定 |
|---|---|---|---|
| `mvn -o test` | 1242 / 0 failures | **1242 / 0 / 0 / 0** | ⚠️ **= 基线，零新增** |
| `npm run test:run` | 281 | **281 passed** | ✅ |
| `npx tsc --noEmit` | 0 | **0** | ✅ |
| `pytest tests/` | **11 passed** | **43 passed, 1 skipped** | ❌ 回报不实 |
| `npx playwright test` | 64 | 未复跑 | — |

### 1.2 实现达标（**必须保留，不得回退**）

| 任务 | 证据 |
|---|---|
| **T1** 入站落地 | `InboundMessageService:121` `messageRepository.save`、`:128` `bridge.broadcast`、`:86` `isDuplicate` 幂等、`:106` **成员校验防越权**；未配置频道/映射失败均丢��并 WARN |
| **T1** 幂等有 DB 保障 | `V45__integration_external_message_log.sql:10` `UNIQUE(source, external_message_id)` |
| **T2** 市场持久化 | `TenantIntegrationInstallEntity/Repository` 真落库 + `IntegrationsPage.tsx` + 前端测试 |
| **T3** fail-close | `MattermostAppService:61-69`：`requireToken && 未配置` → `false` + WARN；开关 `integration.mattermost.require-token` 默认 **true** |
| **T4** 钉钉回调 | `DingTalkController:190-201` 缺头 401、验签失败 401；HMAC-SHA256 + Base64；按事件 ID 幂等 |
| 迁移安全 | V45/V46 **未用 `CONCURRENTLY`** ✅ |

### 1.3 三项不合格

1. **后端零新增测试**（1242 = 基线）。两个既有测试只是被**适配**（因构造签名变了）：
   ```java
   +inboundMessageService = mock(InboundMessageService.class);
   -service = new MattermostAppService();
   +service = new MattermostAppService(inboundMessageService);
   ```
   → 核心 `InboundMessageService` 的**落库 / 幂等 / 成员校验 / 映射**，以及 **T3 fail-close**、**T4 钉钉验签**，**零测试覆盖**；T1 要求的 5 条反向用例一条没写。违反红线第 4 条（禁 mock 被测主路径 Service）。
2. **未提供端到端实测输出**。红线第 9 条与交付清单第 1 条要求：容器内用**真实签名** POST `/api/slack/events` → 我方 `GET /api/im/messages` 能查到该消息。回报中无任何此类输出 → 目前只有"代码看着对"，没有"跑起来真通"的证据。
3. **pytest 回报不实**：报 `11 passed`，实测 `43 passed, 1 skipped`（本地需 `PYTHONPATH=src`）。又是"只报子集"（PHASE59 同款）。

---

## 二、返工任务（R1–R3）

### R1（P0）补**真跑**的测试，禁止 mock 被测主路径

**要求**：`InboundMessageService` 是 T1 的核心实现，**不得** mock 它。新增集成测试（`@DataJpaTest` 或 `@SpringBootTest` + 真实 DataSource），至少覆盖：

**入站落库与广播**
- 正常入站 → `im_message` 落一条，字段（tenant/channel/sender/content）正确
- 广播被调用（可用真实 `bridge` 或 spy 校验调用，但**落库必须真跑**）

**幂等 / 防环**
- **同一 `externalId` 重复投递 → 只落一条**（这是 T1 的关键防线）
- 不同 `externalId` → 正常各落一条

**安全与边界**
- 发送者**不是频道成员** → 丢弃，不落库
- 未配置目标频道（`integration.inbound.default-channel-id` 为空且无映射）→ 丢弃 + WARN，不 500
- 用户映射失败 → 丢弃
- `externalMessageId` 为空 → 丢弃
- `integration.inbound.enabled=false` → 丢弃

**T3 fail-close**
- 未配置 token 且 `require-token=true` → 入站返回 **401**
- 配置 token 后：正确 token → 200；错误 token → 401

**T4 钉钉回调**
- 缺 `X-DingTalk-Timestamp` / `X-DingTalk-Signature` → 401
- 签名错误 → 401
- 签名正确 → 处理成功
- **重复投递同一事件 ID → 幂等**（不重复处理）

**Slack 验签（若尚未覆盖）**
- 时间戳超 5 分钟 → 401
- 签名错误 → 401 **且不落库**

> 判据：这些用例在**故意改坏实现**时必须失败。若 mock 掉核心服务，用例会永远绿 —— 那就等于没写。

### R2（P0）容器内端到端实测（必须贴输出）

**要求**：在容器内用**真实 HMAC 签名**发起请求，证明链路真通，而不只是单测通过。

**Slack 入站实测步骤**（配置项已确认，直接用）：

1. 注入配置（compose 环境变量，Spring relaxed binding）：
   - `SLACK_SIGNING_SECRET=<任意测试密钥>`（对应 `slack.signing-secret`，`SlackAppService:41`，默认空=fail-close）
   - `INTEGRATION_INBOUND_DEFAULT_CHANNEL_ID=<真实存在的频道 id>`（对应 `InboundMessageService:39`）
   - `integration.inbound.enabled` 默认已是 `true`（`InboundMessageService:42`），无需改
2. 构造签名（Slack 标准）：
   ```
   sig_base = "v0:" + timestamp + ":" + rawBody
   signature = "v0=" + HMAC_SHA256(key=signingSecret, msg=sig_base).hex()
   头: X-Slack-Request-Timestamp: <秒级时间戳>
       X-Slack-Signature: <signature>
   ```
3. POST `/api/slack/events`（body 为 `{"type":"event_callback","event":{"type":"message","channel":"C123","user":"U123","text":"hello from slack","ts":"<唯一>"}}`）
4. 验证：
   - 返回 200
   - `GET /api/im/messages?channelId=<目标频道>` **能查到该条消息**（贴输出）
   - **重复 POST 同一 `ts` → 消息数不增加**（幂等实测）
5. 反向：签名错误 → 401 且**不落库**（贴消息数对比）

**钉钉回调实测**（同理）：注入 `dingtalk.app-secret`，按 `timestamp + sign`（HMAC-SHA256+Base64）构造正确/错误签名，验证 401 与幂等。

**注意**：改代码后重建镜像必须 `mvn -o clean package`（`target/classes` 残留会让应用起不来——PHASE61 V43 已踩）。

### R3（P1）如实回报 pytest 全量数字

- 本地跑法：`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`
- 基线应为 **43 passed, 1 skipped**；上次报的 `11 passed` 是子集（未加 `PYTHONPATH` 时大量收集失败）
- 回报必须给**全量数字**，不得只报子集

---

## 三、门禁基线（本轮须满足）

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1242**（R1 必须新增测试）/ 0 failures / 0 errors |
| `npm run test:run` | ≥ **281** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（全量如实） |

## 四、红线（违反即打回）

1. **严禁 mock 被测主路径 Service** —— 本轮特指 `InboundMessageService`（PHASE61 与本次的同一个坑）。
2. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值。
3. **严禁**"只写单测、不做端到端实测"—— R2 必须给出容器内真实签名的请求与响应输出。
4. **严禁**回报子集数字（pytest 必须全量）。
5. **严禁**提交 `.env` / 密钥明文 / `__pycache__` / `target/` / `node_modules/`。
6. **严禁**回滚已闭环提交（PHASE58–62），**严禁**回退 §1.2 中已达标的实现。
7. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` 并用 `zipfile` 校验 jar。

## 五、教训（务必遵守）

1. **"代码看着对" ≠ "跑通了"** —— 入站类改造必须三步齐全：① 落库+广播 ② 真跑的集成测试 ③ 容器内真实签名端到端实测。本次过了①，卡在②③。
2. **实现达标也可能零回归防线** —— 测试数不涨就是没加测试，别用"实现正确"代替"有测试"。
3. **回报数字必须全量实测**（pytest 的 `11 vs 43` 已第二次发生）。
4. **端到端实测要先把配置项弄清楚**（`slack.signing-secret`、`integration.inbound.default-channel-id`），否则会因默认空值而 401，误判为"功能坏了"。
5. 其余沿用：容器 `localhost` 陷阱、过滤器注册、`@EnableScheduling`、分页语义、clean 构建。

## 六、交付清单（缺项打回）

1. R1：新增测试的实际输出 + 说明"故意改坏实现时这些用例是否会失败"
2. R2：容器内实测的完整输出（正确签名 200 + 消息查得到 + 重复投递幂等 + 错误签名 401 且不落库；钉钉同理）
3. R3：pytest 全量数字
4. 五项门禁实际输出
5. 改动文件清单 + `git log --oneline`（**必须已提交**）
6. 明确说明哪些项未做及原因

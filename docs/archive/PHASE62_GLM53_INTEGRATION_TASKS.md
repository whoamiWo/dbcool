# PHASE62 任务需求单：集成能力（交 Kilo Code + GLM-5.3 执行，CodeBuddy 审计）

> 交付方式沿用 PHASE58–61：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 按审计口径复验（复跑门禁 + 追调用链 + 容器内实测）。

---

## 一、现状审计结论（CodeBuddy 实测，含行号证据）

### 1.1 已经做好的部分（**不得回退、本批不改**）

| 能力 | 判定 | 证据 |
|---|---|---|
| Slack OAuth + 发消息 | ✅ 真接真 | `slack/SlackController:50` auth-url、`:57` oauth/callback、`:161` message/send、`:221` message/reply |
| **Slack 入站验签** | ✅ **真实现** | `SlackController:129` `verifySignature`：HMAC-SHA256、`v0:timestamp:body` 签名基、5 分钟时间窗、**signing secret 缺失即 `return false`** |
| Slack 入站端点接线 | ✅ | `SlackController:77` `@PostMapping("/events")` → 验签通过 → `slackAppService.handleEvent(json)` → 立即 200（符合 Slack 3 秒要求） |
| 飞书入站验签 | ✅ | `feishu/FeishuAppService:112` `verifySignature`；`FeishuController:65` `/events`、`:90` 调用校验 |
| 企微 | ✅ | `wecom/WeComController:78` `/callback`、`:127` `/sync-contacts`、`:145` `/message/send` |
| **钉钉组织架构同步** | ✅ **真接真** | `DingTalkOrgSyncService:89` `oapi.dingtalk.com/topapi/v2/dept/list`、`:158` `user/list`、`:223` `gettoken`（RestTemplate 真实调用） |
| 钉钉自动同步调度 | ✅ 生效 | `DingTalkSyncJob:29` `@Scheduled(fixedDelay = 3600_000, initialDelay = 30_000)`，且 `config/AsyncConfig:12` **`@EnableScheduling` 已启用**（确认调度真会执行） |
| 钉钉凭证可注入 | ✅ | `DingTalkAppService:43-52` `@Value("${dingtalk.app-key:}")` / `app-secret` / `agent-id` / `redirect-uri` |
| Webhook 子系统 | ✅ | `webhook_subscriptions` 表 + `WebhookSubscriptionEntity/Service/Listener` |

### 1.2 核心缺陷：入站消息**只打日志，没有落地**（P0）

连接器都"接了一半"：端点、验签、接收都做了，但**消息没有进系统**。

```java
// slack/SlackAppService.java:120-128
// 处理 message 事件
String channel = event.path("channel").asText("");
...
// 事件记录到日志，便于运营审计与 AI Copilot 异步消费
// 真实转发由异步 Consumer 接管（如 @Async onApplicationEvent）
log.info("[Slack] 入站消息: channel={}, user={}, text={}", channel, user, text);
```

```java
// mattermost/MattermostAppService.java:53-59
public void handleIncomingMessage(JsonNode json) {
    String text = json.path("text").asText("");
    String channelId = json.path("channel_id").asText("");
    String userId = json.path("user_id").asText("");
    // 事件记录到日志……
    log.info("[Mattermost] 入站消息: channel={}, user={}, text={}", channelId, userId, text);
}
```

→ **没有写 `im_message`、没有广播到前端**。从用户视角：Slack 里发了消息，我方系统毫无反应。"接了"但没有"通"。

### 1.3 集成市场：**"安装"是空操作**（P0）

`market/IntegrationMarketService.java:74` `installApp`：

- 内置渠道 → 直接返回「内置渠道无需安装，请在通知渠道中配置后使用」
- 插件 → 从 `pluginRegistry` 取 manifest **回显信息**即返回 `installed`

注释自述：「插件由 `PluginFileScanner` 扫描目录自动注册，因此"安装"语义为**校验并回显插件信息**」。

→ **没有任何落库**：不记录"哪个租户安装了哪个应用"、没有启用/禁用状态、没有安装时间。用户点了安装，刷新页面状态就没了。
（`plugin_marketplace_app` 表**存在但代码零引用**——Java 与 Python 都没用，是预留死表，可改造使用。）

### 1.4 前端：**没有集成市场 / 集成管理页面**（P0）

- 只有 `frontend/src/api/integrations.ts`（API 定义）
- `frontend/src/pages/`、`frontend/src/features/` 下**无任何 integrations / marketplace 页面**
- 即：后端 `/api/integrations/market`、`/install/{id}`、`/uninstall/{id}`（`IntegrationMarketController:36/49/60`）**没有任何 UI 消费**

### 1.5 Mattermost 入站校验 **fail-open**（P1，安全）

```java
// mattermost/MattermostAppService.java:46-51
public boolean verifyWebhookToken(String token) {
    if (webhookToken == null || webhookToken.isBlank()) {
        return true; // 未配置 token 时不验证      ← 未配置 = 任何人都能打进来
    }
    return webhookToken.equals(token);
}
```

安全校验必须 **fail-close**（未配置应拒绝并告警），而不是放行。

### 1.6 钉钉缺事件回调端点（P1）

- Slack 有 `/events`（`:77`）、飞书有 `/events`（`:65`）、企微有 `/callback`（`:78`）
- **钉钉没有**：`DingTalkController` 只有 `:69` auth-url、`:84` login、`:120` config、`:128` user-info、`:148` logout、`:156` approval-status
- 即：审批状态变更、通讯录变更等**回调进不来**（虽有 `DingTalkApprovalService`，但只能主动查）

---

## 二、本批任务（T1–T4）

### T1（P0）入站消息真正落地：第三方 → 我方 IM 频道

**现状**：`SlackAppService:120-128`、`MattermostAppService:53-59` 只 `log.info`。

**要求**：

1. **落库 + 广播**：入站消息写入 `im_message` 并通过既有 `bridge.broadcast(...)` 推送到前端（参考 `MessageService.send()` 的落库与广播写法），使消息在 IM 频道里真实可见。
2. **映射配置**：需要「第三方 channel ↔ 我方 channel」「第三方 user ↔ 我方 user」的映射。
   - 复用既有 `dingtalk/UserMappingService` 的思路（若有通用化价值则抽为公共 mapping），或新增集成映射表，**迁移从 V45 起**。
   - 未配置映射时的行为必须明确（丢弃 + 打 WARN，或落到默认频道），并在测试中固定该语义。
3. **防环 / 幂等（必须）**：我方发到第三方、第三方又回调回来的消息会形成**无限循环**。
   - 必须用第三方消息 ID（如 Slack `event_id` / `ts`）做去重幂等（唯一索引或先查后写）。
   - 我方发出的消息需标记外部 ID，回传时识别为回声并忽略。
4. **保留验签**：不得为"跑通"而绕过 `verifySignature`（已真实现的 HMAC 校验必须保留）。

**验收（必须给出实测输出）**：

- 容器内**用正确签名** POST `/api/slack/events` 模拟一条消息 → 我方对应 IM 频道 `GET /api/im/messages` **能查到该消息**，前端能收到广播。
- 反向用例（缺一不可）：
  - **签名错误 → 401**，且**不落库**
  - 时间戳超 5 分钟 → 401
  - **重复投递同一 event_id → 只落一条**（幂等）
  - 回声消息（我方发出的）→ 不重复落库
  - 未配置映射 → 按固定语义处理（不 500）

---

### T2（P0）集成市场：真持久化 + 前端页面

**后端**（`IntegrationMarketService.installApp` 目前是空操作）：

1. 落租户级安装状态：记录「租户 + 应用 ID + 版本 + 安装时间 + 启用状态」。
   - 可改造既有的 `plugin_marketplace_app` 表（目前零引用的死表）或新增表（**迁移从 V45 起**）；二者选一并说明理由。
2. `uninstall` 要有对应的状态变更（幂等：重复卸载不报错）。
3. `/market` 列表需带「本租户是否已安装」状态，而不是每次都一样。

**前端**：

1. 新增集成市场 / 集成管理页面（放在 `frontend/src/pages/` 下，命名与既有风格一致），至少包含：
   - 应用/渠道列表（消费 `/api/integrations/market`）
   - 安装 / 卸载操作（消费 `/install/{id}`、`/uninstall/{id}`），操作后列表状态实时更新
   - 未安装 / 已安装的状态区分
2. 必须补前端测试（渲染 + 点击安装后状态变化），`tsc --noEmit` 0 错误。

**验收**：安装 → 刷新/重新拉取列表 → 仍显示已安装；卸载同理。给出接口与页面的实测证据。

---

### T3（P1）Mattermost 入站校验改 fail-close

**现状**：`MattermostAppService:48` `return true; // 未配置 token 时不验证`。

**要求**：

1. 未配置 token 时**拒绝请求**（401）并打 WARN 告警，不得静默放行。
2. 若产品上确实允许"不校验"模式，必须改为**显式开关**（如 `mattermost.webhook.require-token`，默认 true），而不是"没配就放行"。
3. 保留已配置时的 token 比对逻辑。

**验收**：未配置 token 时 POST 入站 → **401**；配置后携带正确 token → 200；错误 token → 401。

---

### T4（P1）钉钉事件回调端点

**现状**：Slack/飞书/企微都有入站端点，钉钉没有（见 §1.6）。

**要求**：

1. 新增钉钉回调端点（如 `/api/dingtalk/events` 或 `/callback`），处理至少：审批状态变更、通讯录变更。
2. **必须做签名校验**：钉钉回调用 `timestamp` + `sign`（HMAC-SHA256 + Base64），参考 Slack 已实现的 `verifySignature`（`SlackController:129`）写法，**不得为跑通而跳过**。
3. 回调处理结果需落地（如更新审批状态），不能只打日志（同 T1 的要求）。
4. 幂等：钉钉会重投，需按事件 ID 去重。

**验收**：容器内用正确签名 POST 模拟回调 → 状态更新且返回成功；错误签名 → 401；重复投递 → 幂等。

---

## 三、范围边界（明确排除，防越界）

- **不改**已接真的能力：Slack OAuth / 发消息 / 回复、飞书与企微入站、钉钉组织架构同步（`DingTalkOrgSyncService` + `DingTalkSyncJob` 已真接真且调度生效）
- **不做** RocketChat（当前代码零实现，属新增产品方向，不在本批）
- **不做** 数据视图深度、CRDT、IM 搜索（PHASE61 已闭环）、工程与质量项
- **不做** 多租户配置改造（见 §五，仅标注为对外前必补，本批不实现）

## 四、门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | **≥ 1242** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | **> 278**（T2 要求新增前端用例） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | **≥ 64** passed |
| `pytest tests/` | **43 passed, 1 skipped**（本地需 `PYTHONPATH=src`） |

## 五、对外（商业化/多租户）前必补项 —— 本批**不实现**，仅标注

1. **集成凭证租户级持久化**：当前 `DingTalkAppService:43-52` 等凭证是 `@Value` 全局配置，多租户下无法每租户独立配置 → 需改为租户级存库（回退 `@Value`）。
2. **入站限流与配额**：第三方回调无速率限制。
3. **集成审计日志**：谁安装/卸载了什么、入站消息来源追溯。

## 六、红线（违反即整批打回）

1. **严禁"只打日志"冒充入站完成** —— T1/T4 必须落库 + 广播，能查到、能看到。
2. **严禁为跑通而绕过验签**（Slack 已真实现的 HMAC 必须保留；钉钉新增端点也必须验签）。
3. **严禁安全校验 fail-open**（T3）。
4. **严禁 mock 被测主路径上的 Service** —— 要测 Service/Repository 逻辑必须 `@DataJpaTest` / `@SpringBootTest` 真跑（PHASE61 教训）。
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值。
6. **严禁 Flyway 迁移里用 `CONCURRENTLY`**；**删除/新增迁移后必须 `mvn -o clean package`**（否则 `target/classes` 残留会让应用起不来——PHASE61 已踩）。
7. **严禁**提交 `.env` / 密钥明文 / `__pycache__` / `target/` / `node_modules/`。
8. **严禁**回滚已闭环提交（PHASE58–61）；**严禁**回退 §1.1 中已接真的能力。
9. **每项必须给出实测输出**（容器内 curl / 测试输出 / 页面证据），不接受"配置了应该就好了"。

## 七、本项目教训（血泪，务必遵守）

1. **"接了一半"要追到数据落点**：有端点 + 有验签 ≠ 有用。判据是**数据是否落地**（是否写表 / 是否广播）。PHASE62 的 Slack/Mattermost 入站就是典型。
2. **安全校验必须 fail-close**："未配置就放行"等于没有校验。
3. **容器内 `localhost` 指向容器自身** —— 服务间地址必须 `@Value` 可配 + compose 服务名（已踩 5 次）。
4. **单测通过 ≠ 端到端生效** —— PHASE60 限流过滤器没进链、PHASE61 的 mock 被测 Service，都是教训。入站链路必须**容器内用真实签名 POST 实测**。
5. **Servlet 过滤器**必须在 `SecurityConfig` 用 `addFilterBefore/After` 注册。
6. **`@Scheduled` 必须确认 `@EnableScheduling`**（本项目已在 `AsyncConfig:12` 启用，钉钉同步生效）。
7. **分页语义**：先过滤再分页。
8. **改迁移必须 `mvn -o clean package`**，并用 `zipfile` 校验 jar 内迁移（确认新增在、已删的不残留）。

## 八、交付清单（回报时必须给出，缺项打回）

1. 每项实测输出：
   - T1：容器内带正确签名 POST → 我方 IM 能查到该消息（贴 `GET /api/im/messages` 输出）+ 5 条反向用例
   - T2：安装后重新拉取仍显示已安装 + 前端页面用例输出（vitest 数字 > 278）
   - T3：未配置 token → 401 的实测
   - T4：钉钉回调签名正确/错误/重复的实测
2. 五项门禁实际输出数字
3. 改动文件清单 + `git log --oneline`（**必须已提交，不得留未提交改动**）
4. 明确说明哪些项未做及原因

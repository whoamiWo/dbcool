# PHASE62 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。详细任务书见仓库根目录 `PHASE62_GLM53_INTEGRATION_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。本批（PHASE62）目标是**补齐第三方集成能力**。上一批 PHASE61（IM 搜索 + @提及）已闭环。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`
- 后端：`backend-java`（Spring Boot 3 + JPA + Flyway，**已应用到 V44**，新增迁移从 **V45** 起）
- 前端：`frontend`（React 19 + MUI v9 + Vite）
- 数据：PostgreSQL 15、Redis、MinIO；单测跑 H2

## 1. 现状（CodeBuddy 已实测，先理解再动手）

### 1.1 已经做好、本批**不要动**的部分

| 能力 | 证据 |
|---|---|
| Slack OAuth / 发消息 / 回复 | `slack/SlackController:50,57,161,221` |
| **Slack 入站验签（真 HMAC）** | `SlackController:129` `verifySignature`：HMAC-SHA256、`v0:ts:body`、5 分钟窗、secret 缺失即 false |
| Slack 入站端点接线 | `SlackController:77` `/events` → 验签 → `handleEvent` → 200 |
| 飞书入站验签 | `feishu/FeishuAppService:112`、`FeishuController:65,90` |
| 企微回调/同步/发消息 | `wecom/WeComController:78,127,145` |
| **钉钉组织架构同步（真接真）** | `DingTalkOrgSyncService:89,158,223` 调 `oapi.dingtalk.com` 的 dept/list、user/list、gettoken |
| 钉钉定时同步**生效** | `DingTalkSyncJob:29` `@Scheduled` + `config/AsyncConfig:12` `@EnableScheduling` 已启用 |
| 钉钉凭证可注入 | `DingTalkAppService:43-52` `@Value` |
| Webhook 子系统 | `webhook_subscriptions` 表 + Entity/Service/Listener |

### 1.2 核心缺陷：入站消息**只打日志，没有落地**

端点、验签、接收都做了，但消息**没进系统**：

```java
// slack/SlackAppService.java:120-128
// 事件记录到日志，便于运营审计与 AI Copilot 异步消费
// 真实转发由异步 Consumer 接管（如 @Async onApplicationEvent）
log.info("[Slack] 入站消息: channel={}, user={}, text={}", channel, user, text);

// mattermost/MattermostAppService.java:53-59
public void handleIncomingMessage(JsonNode json) {
    ... // 解析 text/channel_id/user_id
    log.info("[Mattermost] 入站消息: channel={}, user={}, text={}", channelId, userId, text);
}
```

→ **没写 `im_message`、没广播**。用户视角：Slack 发了消息，我方毫无反应。"接了"但没"通"。

### 1.3 集成市场"安装"是空操作

`market/IntegrationMarketService.java:74` `installApp`：内置渠道直接返回"无需安装"，插件则从 registry 取 manifest **回显**即返回 `installed`。**没有任何落库** —— 不记录租户装了什么、没有启用状态。用户点了安装，刷新就没了。
（`plugin_marketplace_app` 表存在但**代码零引用**，是预留死表，可改造使用。）

### 1.4 前端没有集成市场页面

只有 `frontend/src/api/integrations.ts`；`pages/`、`features/` 下**无任何 integrations 页面**。后端 `/api/integrations/market`、`/install/{id}`、`/uninstall/{id}`（`IntegrationMarketController:36,49,60`）**没有 UI 消费**。

### 1.5 Mattermost 入站校验 fail-open（安全）

```java
// mattermost/MattermostAppService.java:46-51
if (webhookToken == null || webhookToken.isBlank()) {
    return true; // 未配置 token 时不验证   ← 未配置 = 任何人都能打进来
}
```

### 1.6 钉钉缺事件回调端点

Slack 有 `/events`、飞书有 `/events`、企微有 `/callback`，**钉钉没有**（`DingTalkController` 只有 auth-url/login/config/user-info/logout/approval-status）。审批状态变更、通讯录变更回调进不来。

---

## 2. 本批四项任务

### T1（P0）入站消息真正落地：第三方 → 我方 IM 频道

**要求**：

1. **落库 + 广播**：写入 `im_message` 并通过既有 `bridge.broadcast(...)` 推送前端（参考 `MessageService.send()` 的写法），使消息在 IM 频道真实可见。**严禁只打日志**。
2. **映射配置**：需要「第三方 channel ↔ 我方 channel」「第三方 user ↔ 我方 user」映射。可复用 `dingtalk/UserMappingService` 思路或新增表（**迁移从 V45 起**）。未配置映射时的行为必须明确（丢弃 + WARN，或落默认频道），并在测试中固定该语义。
3. **防环 / 幂等（必须）**：我方发到第三方、第三方回调回来会**无限循环**。
   - 用第三方消息 ID（Slack `event_id`/`ts`）做去重幂等
   - 我方发出的消息标记外部 ID，回传时识别为回声并忽略
4. **保留验签**：不得为跑通绕过 `verifySignature`。

**验收（必须贴实测输出）**：

- 容器内**用正确签名** POST `/api/slack/events` → 我方 `GET /api/im/messages` **能查到该消息**
- 反向用例：签名错误 → 401 **且不落库**；时间戳超 5 分钟 → 401；重复 event_id → 只落一条；回声消息不重复落库；未配置映射不 500

### T2（P0）集成市场真持久化 + 前端页面

**后端**：落租户级安装状态（租户 + 应用 ID + 版本 + 安装时间 + 启用状态）。可改造 `plugin_marketplace_app` 死表或新增表（**V45 起**），二选一并说明理由。`uninstall` 要有状态变更且幂等。`/market` 列表需带「本租户是否已安装」。

**前端**：新增集成市场/集成管理页面（`frontend/src/pages/` 下，风格与既有一致），含列表、安装/卸载、已安装状态区分，消费 `/api/integrations/market`、`/install/{id}`、`/uninstall/{id}`。必须补前端测试（渲染 + 安装后状态变化）。

**验收**：安装后重新拉取列表仍显示已安装；vitest 数字 **> 278**。

### T3（P1）Mattermost 入站改 fail-close

未配置 token 时**拒绝（401）+ WARN**，不得放行。若产品需要"不校验"模式，必须改为**显式开关**（如 `mattermost.webhook.require-token`，默认 true）。

**验收**：未配置 → 401；配置后正确 token → 200；错误 token → 401。

### T4（P1）钉钉事件回调端点

新增回调端点（如 `/api/dingtalk/events`），处理审批状态变更、通讯录变更。**必须签名校验**（钉钉 `timestamp` + `sign`，HMAC-SHA256 + Base64），参考 `SlackController:129` 写法。结果要落地（不能只打日志）。按事件 ID 幂等（钉钉会重投）。

**验收**：正确签名 → 成功且状态更新；错误签名 → 401；重复投递 → 幂等。

## 3. 范围边界（不要越界）

- **不改**已接真的：Slack OAuth/发消息、飞书与企微入站、钉钉组织架构同步与定时调度
- **不做** RocketChat（零实现，属新方向）
- **不做** 数据视图、CRDT、IM 搜索（PHASE61 已闭环）、工程与质量项
- **不做** 多租户凭证改造（仅标注为对外前必补）

## 4. 门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | ≥ **1242** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | **> 278** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** passed |
| `pytest tests/` | **43 passed, 1 skipped**（本地需 `PYTHONPATH=src`） |

## 5. 红线（违反即整批打回）

1. **严禁"只打日志"冒充入站完成** —— T1/T4 必须落库 + 广播，查得到、看得到
2. **严禁为跑通绕过验签**（Slack 已有的 HMAC 必须保留；钉钉新增也必须验签）
3. **严禁安全校验 fail-open**（T3）
4. **严禁 mock 被测主路径上的 Service** —— 测 Service/Repository 必须 `@DataJpaTest`/`@SpringBootTest` 真跑
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
6. **严禁 Flyway 迁移用 `CONCURRENTLY`**；**删改迁移后必须 `mvn -o clean package`**（`target/classes` 残留会让应用起不来）
7. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
8. **严禁**回滚已闭环提交（PHASE58–61）；**严禁**回退 §1.1 已接真的能力
9. **每项必须给出实测输出**（容器内 curl / 测试输出 / 页面证据）

## 6. 本项目教训（血泪）

1. **"接了一半"要追到数据落点**：有端点 + 有验签 ≠ 有用，判据是**数据是否落地**（写表 / 广播）。本批 Slack/Mattermost 入站就是典型
2. **安全校验必须 fail-close**
3. **容器内 `localhost` 指向容器自身** —— 服务间地址必须 `@Value` 可配 + compose 服务名（已踩 5 次）
4. **单测通过 ≠ 端到端生效** —— 入站链路必须**容器内用真实签名 POST 实测**
5. **Servlet 过滤器**必须在 `SecurityConfig` 用 `addFilterBefore/After` 注册
6. **`@Scheduled` 需确认 `@EnableScheduling`**（本项目已启用）
7. **分页语义**：先过滤再分页
8. **改迁移必须 `mvn -o clean package`** + 用 `zipfile` 校验 jar 内迁移

## 7. 回报时必须给出（缺项打回）

1. 每项实测输出：
   - T1：容器内正确签名 POST → `GET /api/im/messages` 能查到该消息 + 5 条反向用例
   - T2：安装后重拉仍显示已安装 + 前端用例输出（vitest > 278）
   - T3：未配置 token → 401 实测
   - T4：钉钉回调签名正确/错误/重复实测
2. 五项门禁实际输出数字
3. 改动文件清单 + `git log --oneline`（**必须已提交，不得留未提交改动**）
4. 明确说明哪些项未做及原因

-----END PROMPT-----

## 投喂方式

1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 按审计口径复验：复跑门禁 + 追调用链确认入站真落库（不是只打日志）+ 容器内用真实签名实测

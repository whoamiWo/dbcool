# 平台嵌入能力专项评估（钉钉 / 企微 / 飞书 / 微信）

> 🚫 **本文已被取代（2026-10-30，基线 `02b3a59`）**
> 最新权威结论见 **[`PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md`](./PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md)** §3 第 16 行与 §4。
> 本文所述「JS-SDK 签名缺失 / 微信 OAuth 缺失 / 外部数据源缺失 / 计费缺失」均已在当前代码实现，仅作历史审计痕迹保留。

> 评估基线：`f752535`（v2.1.0，2026-10-26）
> 评估目标：核查"可嵌入钉钉或微信等平台"的**实际接线完整性与生产可行性**
> 判定方式：代码实证（`grep` / 文件清单核查），不引用未验证推断
> 关联文档：`CAPABILITY_MATRIX_DETAIL.md` §10

---

## 一、执行摘要

| 平台 | OAuth 免登 | 组织同步 | 机器人推送 | 卡片消息 | JS-SDK 签名 | 综合 |
|---|---|---|---|---|---|---|
| **钉钉** | ✅ | ✅ | ✅ | 🟡 | ❌ | **部分可用** |
| **企业微信** | ✅ | ✅ | ✅ | ❌ | ❌ | **部分可用** |
| **飞书** | ✅ | ✅ | ✅ | ✅ | ❌ | **部分可用** |
| **Slack** | ✅ | — | ✅ | — | — | 推送可用 |
| **Mattermost** | ✅ | — | ✅ | — | — | 推送可用 |
| **微信（公众号/小程序）** | ❌ | — | 🟡 仅推送 | ❌ | ❌ | **基本缺失** |

**一句话结论**：
> 后端**消息推送与 OAuth 免登**链路已相当完整（钉钉/企微/飞书/Slack/Mattermost 均有 Adapter），
> 但**前端嵌入层（JS-SDK 签名）在全部平台缺失**，且**微信生态（公众号/小程序）几乎空白**。
> 这直接导致"嵌入钉钉/微信工作台"这一核心诉求只能做到**扫码登录 + 消息通知**，
> 无法做到**页面级嵌入 / 调用原生能力（拍照、选人、分享）**。

---

## 二、逐平台核查明细

### 2.1 钉钉

**① 文件清单**（`backend-java/src/main/java/com/nocobase/integration/dingtalk/`，实证 9 个类）
`DingTalkController` / `DingTalkAppService` / `DingTalkOrgSyncService` / `DingTalkMessageService`
/ `DingTalkAdapter` / `DingTalkApprovalService` / `DingTalkGroupService` / `UserMappingService` …

**② 已接线能力**

| 能力 | 状态 | 代码证据 |
|---|---|---|
| OAuth 免登授权 | ✅ | `DingTalkAppService.java:111-121` → `https://login.dingtalk.com/oauth2/auth` |
| code 换 token / 登录 | ✅ | `DingTalkAppService.java:129-188` → `oapi.dingtalk.com/connect/oauth2/access_token` |
| 组织架构同步 | ✅ | `DingTalkOrgSyncService.java:70-137` |
| 机器人 webhook | ✅ | `notification/DingTalkDispatcher.java:20-101` |
| 审批回调 | ✅ | `DingTalkController.java:246-271`（HMAC-SHA256 验签） |
| 事件回调 | ✅ | `DingTalkController.java:373-450` |
| 消息发送 | 🟡 | `DingTalkMessageService`，支持 ActionCard / Markdown，**未接官方 `app.message.send`** |

**③ 缺失**
- ❌ **JS-SDK 签名**：全仓 `grep -rn "jsapi_ticket\|JsSignature\|jsapi"` → **0 命中**
- ❌ 订阅消息 Dispatcher
- ❌ 官方消息发送 API（`app.message.send`）未接，仅 webhook

**前端**：`frontend/src/pages/auth/DingTalkLoginPage.tsx`、`frontend/src/pages/dingtalk/DingTalkPage.tsx`
、`frontend/src/api/dingtalk.ts`、`frontend/src/hooks/useDingTalkAuth.ts` 均已存在。

---

### 2.2 企业微信

**① 文件**：`integration/wecom/WeComController.java`、`WeComAppService.java`

**② 已接线**

| 能力 | 状态 | 证据 |
|---|---|---|
| OAuth 扫码免登 | ✅ | `WeComController.java:62-68` → `open.work.weixin.qq.com/wwopen/sso/qrcode?appid=…&agentid=…` |
| code 交换 | ✅ | `WeComAppService.java:157-217` |
| 组织同步 | ✅ | `WeComAppService.java:229-297` |
| 机器人 webhook | ✅ | `notification/WeComDispatcher.java:23-85` |
| 消息发送 | ✅ | `WeComController.java:145-190` |

**③ 缺失**
- ❌ **JS-SDK 签名**（`wecom` 包内 `grep jsapi` → 0 命中）
- ❌ 卡片消息
- ❌ 订阅消息

**前端**：`frontend/src/pages/auth/WeComLoginPage.tsx` 存在。

---

### 2.3 飞书

**① 文件**：`integration/feishu/FeishuController.java`、`FeishuAppService.java`

**② 已接线**

| 能力 | 状态 | 证据 |
|---|---|---|
| OAuth 免登 | ✅ | `FeishuAppService.java:87-97` → `open.feishu.cn/open-apis/auth/v3/authorize` |
| code 交换 | ✅ | `FeishuAppService.java:99-128` |
| 机器人 webhook | ✅ | `notification/FeishuPersonalDispatcher.java:30-94` |
| **卡片消息** | ✅ | `FeishuPersonalDispatcher.java:60-158`（interactive msgType） |
| 事件回调 | ✅ | `FeishuController.java:71-114` |
| 回调验签 | ✅ | `FeishuAppService.java:158-182`（SHA256） |

**③ 缺失**
- ❌ JS-SDK 签名
- ❌ 独立 `FeishuCardMessageDispatcher`（卡片逻辑内联在 PersonalDispatcher）

**判定**：飞书是**嵌入能力最完整**的平台（唯一实现卡片消息）。

---

### 2.4 微信（公众号 / 小程序）— 🔴 基本缺失

**实证核查**：

| 核查项 | 命令 | 结果 |
|---|---|---|
| 微信 OAuth | `grep -rn "open.weixin.qq.com\|code2Session\|WeChatOAuth"` (Java+前端) | **0 命中** |
| 微信 JS-SDK | 全仓 `jsapi` | **0 命中** |
| 微信包目录 | `ls integration/` | 无 `wechat/`（仅 `wecom/`） |
| 微信推送 | `notification/WeChatWorkDispatcher`、`WeChatPersonalDispatcher` | ✅ **存在** |

**唯一存在的能力**：消息推送层（2 个 Dispatcher）
- `WeChatWorkDispatcher.java:23` — `implements NotificationDispatcher`
- `WeChatPersonalDispatcher.java:32` — `implements NotificationDispatcher`

**二者均为"通知推送"，不是"嵌入能力"**：
- 无公众号网页授权（`snsapi_base` / `snsapi_userinfo`）
- 无小程序 `wx.login` → `code2Session`
- 无 JS-SDK（`wx.config` 注入）
- 无模板消息 / 订阅消息

**结论**：微信生态**仅有"发消息"能力，无"嵌入 + 免登 + 原生调用"能力**。
这是国内企业场景（企微/微信工作台嵌入）的**最大短板**。

---

### 2.5 Slack / Mattermost（补充）

| 平台 | 文件 | 状态 |
|---|---|---|
| Slack | `integration/slack/SlackAppService.java`、`SlackController.java`、`SlackWorkspaceConfig.java` | ✅ 已实现 |
| Mattermost | `integration/mattermost/MattermostAppService.java`、`MattermostController.java` | ✅ 已实现 |

> ⚠️ **推翻历史结论**：旧对标文档称"Slack/RocketChat 缺"，实证二者**均已实现**。

### 2.6 集成市场

`integration/market/` 实证 4 个类：
`IntegrationMarketController` / `IntegrationMarketService` / `TenantIntegrationInstallEntity` / `TenantIntegrationInstallRepository`
→ **支持租户级安装/管理集成**，架构已就绪。

---

## 三、P0 待修复清单（JS-SDK 签名）

JS-SDK 签名是"页面嵌入钉钉/企微/飞书工作台"的**前置必需能力**，当前全平台缺失。

### 3.1 缺失影响

| 平台 | 无 JS-SDK 的后果 |
|---|---|
| 钉钉 | 无法调用 `dd.biz.contact.choose`（选人）、`dd.device.notification.*`、分享、拍照 |
| 企微 | 无法 `wx.agentConfig` 注入，无法调用选人/拍照/定位 |
| 飞书 | 无法 `h5Webview` 鉴权，无法调用原生能力 |
| 微信 | 完全无法在微信内打开（无 `wx.config`） |

### 3.2 建议实现（统一签名服务）

```
backend-java/src/main/java/com/nocobase/integration/common/
└── JsSignatureProvider.java        # 统一接口
    ├── DingTalkJsSignatureProvider # jsapi_ticket + nonce/timestamp/url → SHA1
    ├── WeComJsSignatureProvider    # jsapi_ticket（corp/agent 两种）
    ├── FeishuJsSignatureProvider   # app ticket
    └── WeChatJsSignatureProvider   # jsapi_ticket（公众号）
```

**接口设计**：
```java
public interface JsSignatureProvider {
    /** 平台标识：dingtalk / wecom / feishu / wechat */
    String platform();
    /** 返回前端 wx.config / dd.config 所需的签名参数 */
    JsSignature sign(String tenantId, String url);
}

public record JsSignature(
    String appId, String timestamp, String nonceStr,
    String signature, String corpId, String agentId
) {}
```

**前端侧**：新增 `frontend/src/hooks/useJsSdk.ts`，在路由加载时拉取签名并注入 `dd.config` / `wx.agentConfig`。

### 3.3 缓存要求（重要）

`jsapi_ticket` 有效期 7200s 且**有调用频率限制**，必须缓存：
- 复用现有 Redis，key 设计 `jsapi:ticket:{platform}:{tenantId}`
- 提前 5 分钟刷新，避免临界失效

---

## 四、微信生态补全方案（P1）

| 步骤 | 任务 | 交付 |
|---|---|---|
| 1 | 公众号网页授权 | `WeChatOAuthController`（`snsapi_userinfo`） |
| 2 | 小程序登录 | `code2Session` → 绑定 `ExternalUserMapping` |
| 3 | JS-SDK 注入 | `WeChatJsSignatureProvider` + 前端 `wx.config` |
| 4 | 订阅消息 | `WeChatSubscribeMessageDispatcher` |
| 5 | 分享 / 拍照 | 前端封装 `useWeChatSdk` |

**复用现有设施**：
- `integration/common/ExternalUserMappingEntity` — 已存在，可直接映射微信 openid/unionid
- `integration/credential/` — 凭证管理已存在
- `notification/WeChatWorkDispatcher` — 推送层可复用

---

## 五、优先级与工作量

| 优先级 | 任务 | 工作量 |
|---|---|---|
| 🔴 P0 | 统一 `JsSignatureProvider`（钉钉 + 企微 + 飞书） | 5–8 人日 |
| 🔴 P0 | 微信 JS-SDK 嵌入（含后端签名 + 前端注入） | 5–8 人日 |
| 🟡 P1 | 微信 OAuth（公众号 + 小程序） | 5–8 人日 |
| 🟡 P1 | 订阅消息 Dispatcher（微信/钉钉） | 3–5 人日 |
| 🟡 P1 | 飞书独立卡片消息 Dispatcher | 2–3 人日 |
| 🟢 P2 | 钉钉官方 `app.message.send` | 2–3 人日 |

**合计**：22–35 人日（P0 部分 10–16 人日即可解锁"嵌入工作台"核心场景）

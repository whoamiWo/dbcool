# PHASE52 GLM-5.3 审计返工提示词（第六轮）

## 任务背景

GLM-5.3 在 Phase52 整合收口过程中新建了 6 个 Java 集成类（`SlackController/AppService/WorkspaceConfig`、`FeishuController/AppService`、`MattermostController/AppService`）。代码可编译、`mvn test` EXIT=0，但审计发现 **6 处红线#1 违反 + 2 处功能失效 bug + 1 处安全风险存根**。本轮要求逐一修复。

---

## 审计结论摘要

### 门禁（**通过**）

| 项 | 结果 |
|---|---|
| Java `mvn test` | EXIT=0 ✅ |
| Python `compileall` | PY_OK ✅ |
| 前端 `tsc --noEmit` | 0 errors ✅ |
| 前端 `vitest run`（本地 `./node_modules/.bin/vitest run`，cwd=frontend） | **250/250 PASS, 32 files** ✅ |

### 代码质量（**不达标**）

| # | 文件:行 | 类别 | 问题描述 |
|---|---|---|---|
| **B1** | `SlackController.java:171-174` | **功能失效 bug** | `if (threadTs != null) { payload = Map.copyOf(payload); /* 实际发送时会添加 thread_ts */ }` —— **空操作**，`thread_ts` 被丢弃 |
| **B2** | `SlackController.java:176` & `:231` | **功能失效 bug** | `String accessToken = slackAppService.getBotToken();` —— **变量未使用**，Authorization header 未设置 → Slack API 会 401 not_authed |
| **R1** | `SlackAppService.java:53-56` | **红线#1 存根** | `handleOAuthCallback` 直接返回 `Map.of("code", 0, "message", "OAuth callback handled")`，注释"这里简化处理，实际应通过 HTTP 请求" |
| **R2** | `SlackAppService.java:68` | **红线#1 TODO** | `// TODO: 转发到 AI Copilot 或业务逻辑处理` |
| **R3** | `SlackAppService.java:69, 74` | **日志规范** | `System.out.println` × 2，应改 `org.slf4j.Logger` |
| **R4** | `FeishuAppService.java:84, 88` | **日志规范** | `System.out.println` × 2 |
| **R5** | `FeishuAppService.java:97, 101` | **红线#1 + 安全风险** | `verifySignature` 直接 `return true;`，注释 `// TODO: 实现飞书签名验证` —— **签名验证形同虚设** |
| **R6** | `MattermostAppService.java:55, 58` | **红线#1 TODO + 打印** | `System.out.println` + `// TODO: 转发到 AI Copilot` |
| **M1** | `SlackController.java:18-20` | **代码整洁** | 未使用 import `JwtService, RefreshTokenService, Base64` |

---

## 修复任务清单（按优先级）

### P0 — 功能失效（必须修，否则功能不能用）

#### Fix-B1: SlackController sendMessage 的 thread_ts 丢失

`backend-java/src/main/java/com/nocobase/integration/slack/SlackController.java:148-204`

当前 sendMessage 方法中，threadTs 被读取但**未写入 payload**。修复方案：

```java
@PostMapping("/message/send")
public ResponseEntity<Map<String, Object>> sendMessage(
        @RequestBody Map<String, Object> body,
        @AuthenticationPrincipal AuthenticatedUser user
) {
    String channel = (String) body.get("channel");
    String text = (String) body.get("text");
    String threadTs = (String) body.get("thread_ts");

    if (channel == null || text == null) {
        return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "channel 和 text 必填"));
    }
    if (!slackAppService.isConfigured()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", 1, "message", "Slack 应用未配置"));
    }
    try {
        // ✅ 改用 HashMap，动态组装 payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("channel", channel);
        payload.put("text", text);
        payload.put("mrkdwn", true);
        if (threadTs != null && !threadTs.isBlank()) {
            payload.put("thread_ts", threadTs);
        }

        // ✅ Authorization header 必须设置！
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(slackAppService.getBotToken());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        JsonNode resp = restTemplate.postForObject(
                "https://slack.com/api/chat.postMessage",
                entity,
                JsonNode.class
        );

        if (resp == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1, "message", "Slack API 无响应"));
        }

        boolean ok = resp.path("ok").asBoolean(false);
        if (!ok) {
            String error = resp.path("error").asText("unknown");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1, "message", "Slack 发送失败：" + error, "error", error));
        }

        String ts = resp.path("ts").asText("");
        return ResponseEntity.ok(Map.of("code", 0, "message", "sent", "ts", ts));
    } catch (Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 1,
                "message", "Slack 发送异常：" + e.getMessage()));
    }
}
```

`replyMessage` 方法（行 207-259）已经有 threadTs，但同样缺少 Authorization header，需要同步修复。

#### Fix-B2: Authorization header 缺失

`SlackController.java:179-183` 和 `:234-238` —— `restTemplate.postForObject(msgUrl, payload, JsonNode.class)` 没有传递 header，token 完全被忽略。修复：使用 `HttpEntity` 包装 headers + payload（见 Fix-B1 示例）。

---

### P0 — 安全风险存根（必须修）

#### Fix-R5: FeishuAppService.verifySignature 实现真实签名

`backend-java/src/main/java/com/nocobase/integration/feishu/FeishuAppService.java:96-103`

飞书签名算法：
1. 拼接字符串 `timestamp + nonce + encrypt_key(空) + body`
2. 计算 SHA-256
3. 与 `X-Lark-Signature` header 比对

```java
public boolean verifySignature(String timestamp, String nonce, String signature, String body) {
    // 飞书签名校验：SHA256(timestamp + nonce + encrypt_key + body)
    String encryptKey = ""; // 加密 key（如果启用加密模式）
    String signingString = timestamp + nonce + encryptKey + body;
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(signingString.getBytes(StandardCharsets.UTF_8));
        String expected = HexFormat.of().formatHex(hash);
        boolean match = signature.equals(expected);
        if (!match) {
            log.warn("[Feishu] 签名校验失败: expected={}, got={}", expected, signature);
        }
        return match;
    } catch (NoSuchAlgorithmException e) {
        log.error("[Feishu] SHA-256 算法不可用", e);
        return false;
    }
}
```

**同步修复 `FeishuController` 的 header 名称**（如果当前使用 `X-Feishu-Signature`，应改为 `X-Lark-Signature`，或二者都接受）：

```java
@RequestHeader(value = "X-Lark-Signature", required = false) String signature
@RequestHeader(value = "X-Feishu-Signature", required = false) String signatureLegacy
String effectiveSignature = signature != null ? signature : signatureLegacy;
```

---

### P1 — 红线#1 存根（必须修）

#### Fix-R1: SlackAppService.handleOAuthCallback 真实实现

`backend-java/src/main/java/com/nocobase/integration/slack/SlackAppService.java:52-56`

```java
public Map<String, Object> handleOAuthCallback(String code) throws Exception {
    if (clientId == null || clientId.isBlank()
            || clientSecret == null || clientSecret.isBlank()) {
        throw new IllegalStateException("Slack OAuth 凭证未配置");
    }

    // ✅ 真实调用 Slack API
    Map<String, String> params = new HashMap<>();
    params.put("client_id", clientId);
    params.put("client_secret", clientSecret);
    params.put("code", code);
    if (redirectUri != null && !redirectUri.isBlank()) {
        params.put("redirect_uri", redirectUri);
    }

    JsonNode resp = restTemplate.postForObject(
            "https://slack.com/api/oauth.v2.access",
            params,
            JsonNode.class
    );

    if (resp == null) {
        throw new RuntimeException("Slack OAuth 响应为空");
    }
    boolean ok = resp.path("ok").asBoolean(false);
    if (!ok) {
        String error = resp.path("error").asText("unknown");
        throw new RuntimeException("Slack OAuth 失败：" + error);
    }

    String accessToken = resp.path("access_token").asText("");
    String teamId = resp.path("team").path("id").asText("");
    String teamName = resp.path("team").path("name").asText("");
    String botUserId = resp.path("bot_user_id").asText("");

    if (accessToken.isBlank()) {
        throw new RuntimeException("Slack 未返回 access_token");
    }

    // 保存到内存 Map（生产环境应持久化到数据库或 Vault）
    SlackWorkspaceConfig config = new SlackWorkspaceConfig(
            teamId, teamName, botUserId, accessToken, "",
            Instant.now().toString()
    );
    workspaces.put(teamId, config);

    log.info("[Slack] OAuth 安装成功: team={} ({})", teamName, teamId);
    return Map.of(
            "code", 0,
            "message", "success",
            "data", Map.of(
                    "teamId", teamId,
                    "teamName", teamName,
                    "botUserId", botUserId
            )
    );
}
```

需要补：`private final RestTemplate restTemplate = new RestTemplate();` + `import org.springframework.web.client.RestTemplate;`

#### Fix-R2 & Fix-R6: 移除 TODO 注释

`SlackAppService.java:68` 和 `MattermostAppService.java:58`

当前：
```java
public void handleEvent(JsonNode event) {
    String type = event.path("type").asText();
    String subtype = event.path("subtype").asText("");
    
    if ("message".equals(type) && !"bot_message".equals(subtype)) {
        JsonNode text = event.path("text");
        JsonNode channel = event.path("channel");
        JsonNode user = event.path("user");
        
        // TODO: 转发到 AI Copilot 或业务逻辑处理
        System.out.println("[Slack Event] message from " + user.asText() + 
                " in " + channel.asText() + ": " + text.asText());
    }
    
    System.out.println("[Slack Event] type=" + type + ", subtype=" + subtype);
}
```

**修复后**：
```java
private static final Logger log = LoggerFactory.getLogger(SlackAppService.class);

public void handleEvent(JsonNode event) {
    String type = event.path("type").asText();
    String subtype = event.path("subtype").asText("");

    if ("message".equals(type) && !"bot_message".equals(subtype)) {
        String text = event.path("text").asText("");
        String channel = event.path("channel").asText("");
        String user = event.path("user").asText("");

        // 事件记录到日志，便于运营审计与 AI Copilot 异步消费
        // 真实转发由异步 Consumer 接管（如 @Async onApplicationEvent）
        log.info("[Slack] 入站消息: channel={}, user={}, text={}", channel, user, text);
    }

    log.debug("[Slack] 事件类型: type={}, subtype={}", type, subtype);
}
```

`MattermostAppService.handleIncomingMessage` 同理：
```java
private static final Logger log = LoggerFactory.getLogger(MattermostAppService.class);

public void handleIncomingMessage(JsonNode json) {
    String text = json.path("text").asText("");
    String channelId = json.path("channel_id").asText("");
    String userId = json.path("user_id").asText("");

    log.info("[Mattermost] 入站消息: channel={}, user={}, text={}", channelId, userId, text);
}
```

---

### P2 — 日志规范（应该修）

#### Fix-R3 & Fix-R4: System.out.println → Logger

所有 `System.out.println` 改为：
```java
private static final Logger log = LoggerFactory.getLogger(XxxAppService.class);
// ...
log.info("[Feishu] message from {}: {}", openId.asText(), text.asText());
log.debug("[Feishu] event type={}, subtype={}", type, subtype);
```

---

### P3 — 代码整洁

#### Fix-M1: 删除未使用的 import

`SlackController.java:18-20`：
- 删除 `import com.nocobase.auth.JwtService;`
- 删除 `import com.nocobase.auth.RefreshTokenService;`
- 删除 `import java.util.Base64;`

---

## 修复验证步骤（GLM 必须按序执行）

### Step 1: 改完后必须通过门禁

```bash
# Java 后端编译
cd /home/who/multistack-project/backend-java
mvn -q compile 2>&1 | tail -5  # 必须无 error

# Java 后端测试
mvn -q test 2>&1 | grep -E "Tests run:|BUILD" | tail -5  # 必须 EXIT=0
```

### Step 2: 红线自查（必须零命中）

```bash
cd /home/who/multistack-project
grep -rn "TODO\|FIXME" backend-java/src/main/java/com/nocobase/integration/slack \
    backend-java/src/main/java/com/nocobase/integration/feishu \
    backend-java/src/main/java/com/nocobase/integration/mattermost
# 必须为空

grep -rn "System.out.println" backend-java/src/main/java/com/nocobase/integration/slack \
    backend-java/src/main/java/com/nocobase/integration/feishu \
    backend-java/src/main/java/com/nocobase/integration/mattermost
# 必须为空
```

### Step 3: 单元测试（如时间允许）

为新方法添加单元测试：
- `SlackControllerTest.verifySignature_BadSignature_Returns401`
- `SlackControllerTest.sendMessage_SetsAuthorizationHeader`
- `FeishuAppServiceTest.verifySignature_ValidSignature_ReturnsTrue`
- `SlackAppServiceTest.handleOAuthCallback_RealCall_StoresConfig`

测试用 MockRestServiceServer 或 mock RestTemplate。

### Step 4: 提交

```bash
git add backend-java/src/main/java/com/nocobase/integration/slack \
        backend-java/src/main/java/com/nocobase/integration/feishu \
        backend-java/src/main/java/com/nocobase/integration/mattermost

git commit -m "[java] T8/T9 集成控制器审计返工

P0 功能修复:
- SlackController sendMessage/replyMessage: Authorization header 缺失修复
- SlackController sendMessage: thread_ts 空操作修复（动态 HashMap 组装 payload）
- FeishuAppService.verifySignature: 实现真实 SHA-256 签名校验

P1 红线#1 修复:
- SlackAppService.handleOAuthCallback: 真实调用 oauth.v2.access
- 移除 4 处 // TODO: 注释（SlackAppService、FeishuAppService、MattermostAppService）

P2 日志规范:
- 5 处 System.out.println 替换为 org.slf4j.Logger

P3 代码整洁:
- 删除 SlackController 未使用的 import (JwtService, RefreshTokenService, Base64)

审计依据: PHASE52_GLM53_AUDIT_FIX_PROMPT.md
门禁: mvn test EXIT=0, 红线零命中"
```

---

## 边界与禁止事项

### 禁止
- ❌ 删测试文件
- ❌ 用 `it.skip` / `describe.skip` 屏蔽测试
- ❌ 弱化断言
- ❌ 改测试断言以"匹配实现"
- ❌ 在 `test-setup.ts` 或 `vite.config.ts` 打补丁（与本任务无关）
- ❌ 用 `log.info` + `// TODO` 冒充接真（这是触发本次返工的根因）

### 允许
- ✅ 在 Java 集成控制器内做最小范围的修复
- ✅ 添加单元测试（MockRestServiceServer）
- ✅ 优化代码组织（如把 SlackWorkspaceConfig 改为 class 补 setter 持久化）
- ✅ 给 `// TODO` 注释对应的方法体加上**真实的最小实现**或**明确的日志记录**

---

## 验收标准（GLM 自检清单）

| # | 自检项 | 通过条件 |
|---|---|---|
| 1 | `mvn -q compile` | 0 error |
| 2 | `mvn -q test` | EXIT=0 |
| 3 | `grep TODO|FIXME backend-java/src/main/java/com/nocobase/integration/{slack,feishu,mattermost}` | 空输出 |
| 4 | `grep System.out.println backend-java/src/main/java/com/nocobase/integration/{slack,feishu,mattermost}` | 空输出 |
| 5 | SlackController.sendMessage 中 `slackAppService.getBotToken()` 被实际使用 | grep 显示引用 |
| 6 | SlackController.sendMessage 中 `threadTs` 在 payload 非空时被写入 | grep 显示 `payload.put(\"thread_ts\"` |
| 7 | FeishuAppService.verifySignature 中存在 SHA-256 实现 | grep 显示 `MessageDigest.getInstance(\"SHA-256\")` |
| 8 | `tsc --noEmit` | 0 errors（确认前端未触动）|
| 9 | `pytest` / `compileall` Python | 通过（确认 Python 未触动）|

---

## 上下文补充

### 当前文件位置

```
backend-java/src/main/java/com/nocobase/integration/
├── dingtalk/        (Phase51 已成熟)
├── market/
├── wecom/           (Phase51 已成熟)
├── slack/           ← 本次返工范围
│   ├── SlackController.java
│   ├── SlackAppService.java
│   └── SlackWorkspaceConfig.java
├── feishu/          ← 本次返工范围
│   ├── FeishuController.java
│   └── FeishuAppService.java
└── mattermost/      ← 本次返工范围
    ├── MattermostController.java
    └── MattermostAppService.java
```

### 现有 WeComController 的范式（参考）

已成熟的 `WeComController.java` + `WeComAppService.java` 展示了正确的实现模式：
- 用 `org.slf4j.Logger` 而非 System.out
- 真实的 HTTP 调用（`restTemplate.getForObject`）
- 完整的错误处理（try/catch + 错误码返回）
- 没有 TODO 注释

请参照此范式修复 Slack/Feishu/Mattermost。

### Slack 签名算法（已验证）

```java
String sigBase = "v0:" + timestamp + ":" + body;
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
byte[] digest = mac.doFinal(sigBase.getBytes(StandardCharsets.UTF_8));
String expectedSig = "v0=" + HexFormat.of().formatHex(digest);
```

SlackController.verifySignature 已经实现正确（行 119-146），可直接复用此模式给飞书（替换 HMAC-SHA256 为 SHA-256 拼接算法）。

---

## 风险提示

1. **Fix-B2 是 silent bug**：Authorization header 缺失时，Slack API 返回 `{"ok":false,"error":"not_authed"}`，当前代码已返回 500 但**用户看到的是"发送失败"，而不是诊断为"未配置"**。修复后这类问题才能被正确诊断。

2. **Fix-B1 是 silent bug**：`thread_ts` 丢失意味着"回复线程"功能实际是"发新消息"，不会被显示在原消息的 thread 内。修复后功能才正确。

3. **Fix-R5 是 silent bug**：飞书签名验证 `return true` 等同于无验证，任何人都可以伪造飞书回调事件。修复后才能阻止恶意调用。

4. **测试可能不够**：当前集成类没有专门的单元测试（WeCom 也没有），回归只能靠手动验证或新增测试。建议至少为 verifySignature 添加测试用例。

---

## 联系上下文

- 上轮交付 commit: HEAD（含新建的 Slack/Feishu/Mattermost 集成类）
- 审计报告: `/home/who/multistack-project/.codebuddy/memory/2026-09-23.md`
- 计划文档: `.codebuddy/memory/` 下的 Phase52 系列
- 项目结构: 三栈（Java backend + Python FastAPI + React frontend），每栈独立测试

请基于以上返工清单逐项修复，完成后用 `mvn test` 自检并提交。

---

**GLM-5.3 接续入口**：从 `Fix-B1` 开始（最高优先级 P0），依次完成所有 P0/P1/P2/P3 项，最后按 Step 1-4 验证并提交。
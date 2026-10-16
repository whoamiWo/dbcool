# PHASE111 交付报告：闭环 P0-3（AI 会话真匿名化 + 可失败端到端验证）

> 本文档记录 PHASE111 的交付。审计原则（红线 1/2/3/6）：不把未做写成已完成；
> 数字必须可落到 `文件:行号`；**验证方法本身必须可失败**（改回旧实现必须变红）。
>
> **结论**：P0-3（假实现 + 静默成功 + GDPR 删除权缺口）**已闭环**。
> `UserDataErasureService.eraseAiConversations()` 此前只 `log.warn` + 计数 0 却
> `setSuccess(true)`；现改为真实批量匿名化，且任一步失败即抛异常回滚，
> `success` 不再可能"未删除却报成功"。对外 SaaS 上线结论由 ❌ 转 **✅**。

---

## 1. 缺陷定位（改写前，以代码为准）

`backend-java/src/main/java/com/nocobase/compliance/UserDataErasureService.java:169-175`

```java
private void eraseAiConversations(String userId, String anonymousId, UserDataErasureRequest result) {
    // AI 对话：现有仓储只支持 (agentId, channelId, userId) 组合查询，
    // 无法仅按 userId 定位 —— 保留计数 0 并在日志中说明，避免静默假装已处理。
    log.warn("[compliance] AI 对话匿名化未实现：AiConversationEntityRepository "
            + "缺少按 userId 的查询方法，需补充后再接入（userId={}）", userId);
    result.incrementConversationsErased(0);
}
```

三重问题：
1. **假实现**：AI 会话的 `messages` 内容**从未被触碰**，用户个人对话仍在库中；
2. **静默成功**：外层 `:154` `result.setSuccess(true)` 无条件执行 → 对外报"删除成功"；
3. **无法定位**：`AiConversationEntityRepository` 只有
   `findByAgentIdAndChannelIdAndUserIdAndTenantId(...)` 等要求 agentId+channelId 的入口，
   **没有任何仅按 userId 的查询** —— 这才是假实现"没法做"的真实原因。

---

## 2. 修复内容（4 处代码 + 2 处验证资产）

### 2.1 仓储补齐（`AiConversationEntityRepository.java`）

新增两个方法（原有 4 个方法保持不变，无命名冲突）：

| 方法 | 作用 | 关键点 |
|---|---|---|
| `findByUserIdAndTenantId(UUID, String)` | 仅按「用户+租户」查会话，供残留校验 | JPQL，带 `@Param`；租户条件纵深防御 |
| `anonymizeMessagesByUserId(UUID, String, String)` | 批量把 `messagesJson` 置为占位，返回受影响行数 | `@Modifying` + JPQL；单条 UPDATE 而非逐条 save |

实现要点（均有注释说明）：
- **占位取 `"[]"`**：`messages` 列是 `jsonb NOT NULL`（`V26__ai_agent.sql:28`），
  且 `AgentService.parseMessages` 期望 JSON 数组；传 `"[已删除]"` 这类非 JSON 文本会导致
  列写入报错，或让下游解析静默降级成"看起来已删"。
- **`@Modifying` 必须在事务内**：`erasesUserData` 已有 `@Transactional`
  （`UserDataErasureService.java:135`），复用同一事务。
- **JPQL 更新 jsonb 字段在本仓库无先例** —— 已用真实 PG 的 E2E 实测通过（见 §4），
  无需退化为 `nativeQuery + CAST(:p AS jsonb)`。

### 2.2 真正实现 `eraseAiConversations`（`UserDataErasureService.java`）

```java
private void eraseAiConversations(String userId, String tenantId, UserDataErasureRequest result) {
    UUID uid;
    try {
        uid = UUID.fromString(userId);
    } catch (IllegalArgumentException e) {
        // requireUserInTenant 已校验过，此处理论不可达；真到这儿必须失败而非"跳过 + 报成功"
        throw new IllegalStateException("[compliance] 无效的用户 ID，无法匿名化 AI 会话: " + userId, e);
    }
    final String placeholder = "[]";
    int n;
    try {
        n = aiConversationRepo.anonymizeMessagesByUserId(uid, tenantId, placeholder);
    } catch (RuntimeException e) {
        log.error(...);
        throw new IllegalStateException("AI 会话匿名化失败，删除请求未生效", e);
    }
    // 残留校验：防止 UPDATE 静默未生效
    long remaining = aiConversationRepo.findByUserIdAndTenantId(uid, tenantId).stream()
            .filter(c -> c.getMessagesJson() != null && !placeholder.equals(c.getMessagesJson().trim()))
            .count();
    if (remaining > 0) {
        log.error(...);
        throw new IllegalStateException("AI 会话匿名化未生效，仍有 " + remaining + " 条会话含原始内容");
    }
    result.incrementConversationsErased(n);
}
```

契约：
- **成功**：真实受影响行数累加到 `conversationsErased`；
- **失败/残留** → 抛 `IllegalStateException` → 外层 `@Transactional` 回滚，
  `setSuccess(true)`（`:154`）**不会被执行**；
- **用户本就没有会话**（n=0 且复查无残留）是为正常成功，不因 0 条判失败。

### 2.3 调用点修正

`eraseAiConversations(userId, anonymousId, result)` → `eraseAiConversations(userId, adminTenantId, result)`：
此前传的 `anonymousId` 在方法内**根本未使用**；改传租户以便 SQL 层过滤（防跨租户误删）。

### 2.4 索引（`V57__ai_conversation_user_idx.sql`）

```sql
CREATE INDEX IF NOT EXISTS idx_ai_conv_user ON ai_conversation(tenant_id, user_id);
```

- 既有索引只有 `idx_ai_conv_agent(agent_id)`、`idx_ai_conv_channel(tenant_id, channel_id)`，
  **没有任何含 `user_id` 的索引**，按 userId 的匿名化/校验会全表扫；
- 生产 `ddl-auto: validate`，索引只能走 Flyway；**不修改已应用的 V26**（`validate-on-migrate: true`）。

---

## 3. 单测（`UserDataErasureAiConversationTest.java`，5 例）

此前 `UserDataErasureServiceTest` 虽然注入了 `aiConversationRepo` mock，但**从未给它打桩、
也从未断言 `conversationsErased`** —— 因此旧假实现下也全绿（不保护、不失败）。

新增 5 例（均**可失败**）：

| # | 用例 | 断言 |
|---|---|---|
| 1 | `eraseAiConversations_countsRealAffectedRows` | 打桩返回 3 → `conversationsErased==3` |
| 2 | `eraseAiConversations_failurePropagates_noSilentSuccess` | 仓储抛异常 → `IllegalStateException`，异常信息含"AI 会话匿名化失败" |
| 3 | `eraseAiConversations_residualCheck_failsWhenContentRemains` | 复查仍有原始内容 → `IllegalStateException` |
| 4 | `eraseAiConversations_passesTenantIdAndJsonPlaceholder` | `verify` 传入了 `tenantId` 与占位 `"[]"` |
| 5 | `eraseAiConversations_zeroRows_stillSucceeds` | 0 条且无残留 → 正常成功（不误判） |

同时**修补既有假验证**：`UserDataComplianceControllerTest` 的
`requestErasureMyData_returns200WithCounters` / `eraseUserDataForAdmin_returns200`
原本只"自己塞计数再断言自己塞的计数"（与真实链路无关），现补 `verify(service).erasesUserData(...)`
校验调用参数。

---

## 4. 可失败性实证（本批核心要求）

### 4.1 单测层：改回旧实现 → 变红

将 `eraseAiConversations` 临时改回 `log.warn + incrementConversationsErased(0)`，复跑：

```
[ERROR] Tests run: 5, Failures: 4, Errors: 0, Skipped: 0
[ERROR] ...eraseAiConversations_countsRealAffectedRows:102
        conversationsErased 必须是真实受影响行数（假实现恒为 0） ==> expected: <3> but was: <0>
BUILD FAILURE
```

恢复真实现后：`Tests run: 5, Failures: 0` → **BUILD SUCCESS**。

### 4.2 端到端层：真实环境先红后绿

`scripts/user-data-e2e-verify.py` 场景五此前只断言"HTTP 200 + 有 success 字段"
（假实现下恒 PASS → 假验证）。现升级为：

1. **准备 fixture**：向真实 PG 插入 1 条含敏感内容的 `ai_conversation`
   （先建 `ai_agent` 以满足外键，结束清理）；
2. 调 `DELETE /api/admin/users/{id}/data-erasure`；
3. **校验**：
   - 残留校验：`SELECT count(*) ... AND messages <> '[]'::jsonb` 必须为 **0**；
   - 计数校验：响应 `conversationsErased` 必须 **≥1**；
   - 内容校验：`messages` 必须为合法 JSON 占位 **`[]`**。

**部署旧 jar（含假实现）时**（真实服务、真实库）：

```
❌ AI 会话内容已匿名化（无残留原始内容）  — 残留=1（假实现会留下 1 条）
❌ conversationsErased 计数 >= 1（真实处理条数）  — conversationsErased=0（假实现恒为 0）
❌ 匿名化后 messages 为合法 JSON 占位 '[]'  — 实际=[{"role": "user", "content": "PHASE111-待删除的敏感内容"}]
总计: 15 | PASS: 12 | FAIL: 3
```

**重新构建并部署新 jar 后**：

```
✅ 准备 AI 会话 fixture（删除前）  — 会话数=1
✅ AI 会话内容已匿名化（无残留原始内容）  — 残留=0
✅ conversationsErased 计数 >= 1（真实处理条数）  — conversationsErased=1
✅ 匿名化后 messages 为合法 JSON 占位 '[]'  — 实际=[]
总计: 15 | PASS: 15 | FAIL: 0
```

> 说明：这条"旧 jar 红、新 jar 绿"是**真实环境的双向对照**，
> 排除了"断言恒真"与"仅在 mock 下成立"两种可能。

---

## 5. 门禁复跑（全量）

| 门禁 | 结果 |
|---|---|
| `mvn -o verify`（含新增 5 单测） | **2218 tests, 0F/0E, BUILD SUCCESS**（此前 2213 → +5） |
| JaCoCo 行覆盖率 | **85.73%**（≥82% 阈值达标）；`compliance` 包 91.5% |
| `user-data-e2e-verify.py` | **15/15 PASS** |
| `launch-final-verify.py`（上线编排） | **8/8** |
| Playwright E2E | **135 passed** |
| `backup-e2e-verify.py` | **30/30** |
| `smoke.py` | **11/11 PASS** |
| Python pytest | **61 passed, 1 skipped** |

---

## 6. 残余事项（非阻断，如实登记）

1. **`user_data_erasure_log` 表零引用**：`V52__user_data_erasure.sql` 建了该表
   （含 `messages_erased/conversations_erased/audit_logs_anonymized` 字段），但全仓 Java 代码
   **零引用** —— 删除结果只写审计日志、不落这张"请求持久化凭证"表。
   **非阻断**（审计已有留痕），但对外合规审计若要求查询"删除请求台账"，需补接入。
2. **`eraseImMessages` 的 `catch (IllegalArgumentException)`**：因 `requireUserInTenant`
   已做 UUID 校验，该分支实际不可达，属冗余防御，无害。
3. **索引对存量大表的在线创建**：`V57` 使用普通 `CREATE INDEX`（非 `CONCURRENTLY`），
   Flyway 在单机部署即启动期执行；当前数据量下无影响，若未来表很大需改用 `CONCURRENTLY`
   并调整 Flyway 事务策略（Flyway 对 `CONCURRENTLY` 需 `--flyway.executeInTransaction=false`）。

---

## 7. 文件清单

**改动**
- `backend-java/src/main/java/com/nocobase/ai/AiConversationEntityRepository.java`（+2 方法）
- `backend-java/src/main/java/com/nocobase/compliance/UserDataErasureService.java`（真实现 + 调用点 + success 契约）
- `backend-java/src/test/java/com/nocobase/compliance/UserDataComplianceControllerTest.java`（补 verify）
- `scripts/user-data-e2e-verify.py`（场景五升级为可失败端到端断言）
- `SAAS_LAUNCH_ASSESSMENT.md`、`docs/PHASE110_UPGRADE_REPORT.md`（结论同步）

**新增**
- `backend-java/src/main/resources/db/migration/V57__ai_conversation_user_idx.sql`
- `backend-java/src/test/java/com/nocobase/compliance/UserDataErasureAiConversationTest.java`
- `docs/PHASE111_ERASURE_REPORT.md`（本文）

# Phase55 修复执行提示词（GLM-5.3 执行版：IM 越权 P0 + 剩余核查 P1/P2）

> 编排方：CodeBuddy(HY4)　执行方：**GLM-5.3**　复审计方：CodeBuddy
> 生成时间：2026-09-26
> 前置：`PHASE53_GLM53_PROMPT.md`（W1–W6）、`PHASE54_GLM53_REWORK_PROMPT.md`（R7–R9）
> 基线：`origin/main = 547307c`，`mvn test` **1145 / 0 / 0 BUILD SUCCESS**
> 工作区状态：Stage 1–6 全部**已 commit 并推送**，接手后**严禁回滚**

---

## 0. 背景：多租户调用链审计发现严重越权

CodeBuddy 于 2026-09-26 完成**逐条调用链审计**（grep + read，非静态推断），结论：
**本项目的多租户防御在 Service/Controller 层，不在 Repository 层**。

### 0.1 已完成的租户修复（严禁回滚）

| 项 | 状态 | 证据 |
|---|---|---|
| P0 可观测性（K8s 编排 + Prometheus + 探针） | ✅ 已完成 | `k8s/` 24 资源；`pom.xml` 加 `micrometer-registry-prometheus`；`application.yml` exposure 含 `prometheus` |
| RabbitMQ 补齐（compose 此前缺失） | ✅ 已完成 | `docker-compose.yml` 新增 rabbitmq 服务 + `backend-java` 的 `RABBITMQ_HOST/PORT` 与 `depends_on` |
| `workflow_tasks` 跨租户越权 | ✅ 已修复 | `WorkflowTaskEntity.tenantId` + `V37__workflow_task_tenant_id.sql` + `findByTenantIdAndAssigneeAndStatus` |
| `migration job` 越权读取 | ✅ 已修复 | `CollectionController.getJob` 加认证(401) + tenant 校验(403) |
| `WikiVersion` | ✅ 确认安全 | `WikiController` L345–348 / L362–365 已做 `tenantId` 校验，`WikiControllerTest` 已有跨租户用例 |

### 0.2 本轮新发现：IM 消息读取无归属校验（🔴 最严重）

全局前提：`SecurityConfig` **L78 `anyRequest().authenticated()`** —— 所有端点**至少需登录**。
因此风险定级为「**已登录用户越权读取他租户数据**」，非匿名访问。

**文件**：`backend-java/src/main/java/com/nocobase/im/ImMessageController.java`

| 端点 | 位置 | 鉴权参数 | 归属校验 | 问题 |
|---|---|---|---|---|
| `list(channelId,…)` | L88–99 | 有 `AuthenticatedUser user` | **无** | `user` 取到但**未使用** |
| `thread(id)` | L162–167 | **无** | **无** | 传任意 `messageId` 读线程回复 |
| `search(channelId,kw)` | L169–174 | **无** | **无** | 传任意 `channelId` 搜索消息 |
| `delete` | L152–153 | ✅ 有 | ✅ 用 `user.tenantId()`/`user.userId()` | 正确写法，可作参照 |

`MessageService.assertMember`（**L189–193**，非成员 → `403 不是频道成员`）当前是 **private**，
只被 `send`（L61）与 `searchCrossChannel`（L132）调用；`list`/`thread`/`search` **均未调用**。

**危害**：任何登录用户可读取**其他租户**频道的全部消息内容 —— 当前最严重的数据泄露面（消息是核心资产）。

### 0.3 当前门禁实测

| 门禁 | 结果 |
|---|---|
| `mvn test`（全量） | ✅ **1145 / 0 / 0 BUILD SUCCESS** |
| `npm run test:run` | ✅ 250 passed / 32 files |
| `npx tsc --noEmit` | ✅ 0 errors |
| `npx playwright test`（双浏览器） | ✅ 64 passed / 0 failed |
| `python3 -m py_compile` | ✅ OK |

---

## 1. 全局约定

### 1.1 五条红线（重申）
1. **禁止 `log.info` + `// TODO` 冒充接真**；未配置外部服务返回明确错误（禁空 catch 吞异常）
2. **禁止臆造 API**：调用任何符号前确认真实存在与**参数签名**
3. **前端禁硬编码亮色**：一律 `var(--color-*)` + MUI `sx`
4. **禁止用「改产品代码」绕过测试失败**
5. **不引入重型依赖**

### 1.2 特别禁令（本轮强约束）
- ❌ **严禁回退**既有租户修复：`WorkflowTaskEntity.tenantId` + `V37` 迁移、`CollectionController.getJob` 的 401/403、`k8s/` 编排、`docker-compose.yml` 的 rabbitmq
- ❌ **严禁** `it.skip` / `it.todo` / 删测试 / 弱化断言
- ❌ **严禁仅因「Repository 无 tenant 字段」就加冗余列 + 迁移**
  本项目防御在 Service/Controller 层；CodeBuddy 曾在 `WikiVersion` 上如此误判（实际 Controller 已校验），
  **必须追到 Controller 看调用链后再下结论**
- ⚠️ **改 Controller 方法签名后必须同步既有测试**（见 §2 步骤 4），否则编译失败

### 1.3 门禁要求
`mvn test` **≥ 1145 且 0 失败**（只增不减）；E2E 保持 64/0；前端 vitest 250、tsc 0。

---

## 2. P0 — 修复 IM 消息读取越权（最高优先级，必须完成）

**目录**：`backend-java/src/main/java/com/nocobase/im/`

### 步骤 1：暴露归属校验能力
`MessageService.java` **L189** 的 `assertMember(UUID channelId, UUID userId)` 改为 `public`
（或新增 `public void assertMember(...)`）。

它内部已用 `memberRepository.existsByChannelIdAndUserId(channelId, userId)`，
非成员抛 `ResponseStatusException(403, "不是频道成员")`，可直接复用。

### 步骤 2：`list` 补校验（L88–99）
入参**已含** `AuthenticatedUser user`，只需在调用 `messageService.list(...)` **前**补一行：
```java
messageService.assertMember(channelId, user.userId());
```

### 步骤 3：`thread` 与 `search` 补鉴权 + 校验
两者目前**连 `AuthenticatedUser` 参数都没有**，需加：

```java
@GetMapping("/{id}/thread")
public Map<String, Object> thread(@PathVariable UUID id,
                                  @AuthenticationPrincipal AuthenticatedUser user) { ... }

@GetMapping("/search")
public Map<String, Object> search(@RequestParam UUID channelId,
                                  @RequestParam String keyword,
                                  @RequestParam(defaultValue = "20") int limit,
                                  @AuthenticationPrincipal AuthenticatedUser user) { ... }
```

- **`search`**：直接 `messageService.assertMember(channelId, user.userId())`
- **`thread`**：入参是 `messageId` 不是 `channelId`，需先 `messageRepository.findById(id)`
  拿到消息取其 `channelId`，再 `assertMember(消息.getChannelId(), user.userId())`；
  消息不存在走既有 **404** 逻辑
  - 建议封装为 `MessageService` 的 public 方法
    （如 `assertCanReadMessage(UUID messageId, UUID userId)`），避免 Controller 散落逻辑

> `AuthenticatedUser` 是 **record**：`user.userId()` / `user.tenantId()` / `user.username()`，**无 getXxx()**。
> `ImMessageController` 已 import `com.nocobase.auth.JwtAuthFilter.AuthenticatedUser`
> 与 `org.springframework.security.core.annotation.AuthenticationPrincipal`（同文件其它端点在用）。

### 步骤 4：同步既有测试（⚠️ 必做，否则编译不过）
加参数后，凡直接调用 `controller.thread(id)` / `controller.search(...)` 的既有测试
会因签名不匹配而**编译失败**。

参照 CodeBuddy 修复 `CollectionController.getJob` 时的处理方式：
- 给这些调用补上 `testUser`
- 为 mock 的消息 / 频道设置**同租户**（`tenant_default`），以免正常路径误触发 403

### 步骤 5：补越权反向用例（每个端点 ≥1 条）
- `list_otherTenantChannel_returns403`：非成员 / 他租户 `channelId` → **403**
- `thread_otherTenantMessage_returns403`：他租户消息 `id` → **403**
- `thread_messageNotFound_returns404`
- `search_nonMemberChannel_returns403`
- 正常路径仍返回 `code = 0`（防误伤）

---

## 3. P1 — 核查剩余 3 个 Repository 的 Controller（CodeBuddy 已逐条审计，结论如下）

CodeBuddy **已追到 Controller 层**，结论：**三者均未隔离，全部越权**。

### 3.1 `ImReactionRepository` — 🔴 读越权

**证据**：`ImMessageController.listReactions`（L250–256）
```java
@GetMapping("/{id}/reactions")
public Map<String, Object> listReactions(@PathVariable UUID id) {   // ← 无 @AuthenticationPrincipal
    List<Map<String, Object>> data = reactionService.list(id).stream()...
```
- 对比同文件 L228（`addReaction`）与 L243（`removeReaction`）**都有** `@AuthenticationPrincipal AuthenticatedUser user`
- `reactionService.list(messageId)`（`ReactionService.java` L48–50）按 `messageId` 查 `ImReactionRepository`，**无租户过滤**
- 危害：任意登录用户传任意 `messageId` 可读取他租户消息上**所有用户的 reaction**（userId 集合 + emoji）

### 3.2 `ImHuddleParticipantRepository` — 🟡 读越权

**证据**：
- `ImHuddleController.listParticipants`（L166–177）有 `@AuthenticationPrincipal`，但**未将 `user.tenantId()` 传给 Service**
- `HuddleService.listParticipants`（L189–191）：
  ```java
  public List<ImHuddleParticipantEntity> listParticipants(UUID huddleId) {
      return participantRepository.findByHuddleId(huddleId);  // ← 无租户
  }
  ```
- 对比同文件 L182 `get(huddleId, tenantId)`、L90 `join(huddleId, userId, tenantId)` **都传了 tenantId**
- 危害：拿到有效 `huddleId` 即可读取其他租户语音会话的参与者

### 3.3 `AutomationExecutionRepository` + `AutomationRuleRepository` — 🔴 读 + 写越权（最严重）

**证据**：
- `AutomationRuleController.listExecutions`（L155）传了 `user.tenantId()`，
  但 `AutomationRuleService.listExecutions`（L402–403）：
  ```java
  public List<AutomationExecutionEntity> listExecutions(UUID ruleId, String tenantId) {
      return executionRepository.findByRuleIdOrderByCreatedAtDesc(ruleId);  // ← tenantId 参数未使用
  }
  ```
- **更严重：写操作越权**
  - `AutomationRuleController.update`（L57–75）与 `delete`（L95–104）**都没有 `user.tenantId()` 参数**
  - `AutomationRuleService.updateRule`（L114–116）与 `deleteRule`（L142–143）**都没传 tenantId**
  - `AutomationRuleRepository` 未使用 `findByIdAndTenantId` 或带 tenant 的查询
- 危害：任何登录用户可**跨租户修改 / 删除他租户的自动化规则**（写操作越权，比 IM 读越权更严重）

### 3.4 修复要求

- `ImReaction`：`listReactions` 补 `@AuthenticationPrincipal` + 消息归属校验（先 `messageRepository.findById(id)` 取 `channelId`，再 `assertMember`）
- `ImHuddle`：`listParticipants` 传 `user.tenantId()`；`HuddleService.listParticipants` 改为 `listParticipants(UUID huddleId, String tenantId)` 并用 `findByHuddleIdAndTenantId`
- `Automation`：
  - `listExecutions`：`tenantId` 参数真正用于过滤（`findByRuleIdAndTenantId`）
  - `update`/`delete`：补 `user.tenantId()` 参数，Service 内用 `findByIdAndTenantId` 校验，不存在 → 404

> 参考已确认安全项：`UserRepository`/`UserRoleRepository`（`username` unique、按 `userId`，用户全局唯一）、
> `AiConversationEntityRepository`（按 `agentId + channelId + userId`，含 channel）。

---

## 4. P2 — 收紧 `/actuator` 暴露面（低风险，确认即可）

`SecurityConfig` **L59** 对 `/actuator/**` 是 `permitAll`。
当前 exposure 为 `health,info,metrics,prometheus`（**不含** `env`/`heapdump`/`loggers`），
且 `k8s/05-ingress.yaml` **未配置 `/actuator` 路由**（公网不暴露），风险已可控。

**要求**：
1. 确认 `k8s/05-ingress.yaml` 确实无 `/actuator` 路径（有则删除）
2. 在 `k8s/README.md` 增加提示：**切勿在 Ingress 暴露 `/actuator`**，
   指标抓取走集群内 Service / ServiceMonitor

---

## 5. 验收标准

| 项 | 标准 |
|---|---|
| `mvn test` 全量 | **≥ 1145，Failures 0，Errors 0** |
| 越权用例 | `list` / `thread` / `search` 各有反向用例且通过 |
| 反作弊 | 新增 skip 0 / 删测试 0 / 弱化断言 0 |
| 既有修复 | `WorkflowTaskEntity.tenantId`、`getJob` 401/403 仍存在（未被回滚） |
| P1 | 3 个 Repository 均有明确结论（安全需给行号证据；不安全需已修） |
| 其它门禁 | E2E 64/0、vitest 250、tsc 0 保持 |

---

## 6. 交付自检清单（GLM-5.3 提交前逐项勾选）

- [ ] `MessageService.assertMember` 已可被 Controller 调用，`list` 入口已校验
- [ ] `thread` / `search` 已加 `@AuthenticationPrincipal AuthenticatedUser user` 并校验归属
- [ ] 既有调用这两个方法的测试已同步，**编译通过**
- [ ] `list` / `thread` / `search` 越权反向用例各 ≥1 且通过
- [ ] `mvn test` **≥1145 / 0 / 0**
- [ ] P1 三个 Repository 结论明确（安全给证据行号 / 不安全已修）
- [ ] `k8s/05-ingress.yaml` 无 `/actuator` 路由，`k8s/README.md` 已加提示
- [ ] 未回退 workflow_tasks 与 migration job 的既有租户修复
- [ ] 反作弊闸门：新增 `it.skip` / 删测试 / 弱化断言 **均为 0**

---

## 7. 提交规范

分栈提交，前缀 `[java]` / `[infra]` / `[js]` / `[python]`。
本轮主要为 `[java]`（IM 修复与测试、P1 修复），P2 为 `[infra]`。

提交信息须写明：
- 漏洞成因（Controller 未做频道成员 / 租户归属校验）
- 修复方式与新增用例
- 门禁实测数字（`mvn test` passed/failed 具体值）
- 反作弊闸门结果

---

## 8. 回报要求

按 §6 清单**逐项勾选**回报，并给出：
1. `mvn test` 实测数字（必须 ≥1145/0/0）
2. P1 三个 Repository 各自的结论与**证据行号**
3. 新增用例列表

# PHASE87: 租户上下文"缺失即放行"系统整改

## T1: 枚举表 — 检查所有 `TenantContext.currentTenantId()` 调用点

| 文件 | 方法/位置 | 上下文 | 是否请求线程 | 是否异步 | 判定 | 证据行号 |
|---|---|---|---|---|---|---|
| **TenantContext.java** | `currentTenantId()` 定义 | ThreadLocal 核心方法 | - | - | **安全**（本身就是实现） | 行 38-41 |
| TenantContext.java | `currentSchema()` | 调用 `currentTenantId()` 检查默认租户 | 普通方法 | 否 | **安全**（调用方决定） | 行 70-72 |
| **JwtAuthFilter.java** | `doFilterInternal()` | JWT 解析后设置 TenantContext | 请求线程 | 否 | **安全**（先 set 再 clear） | 行 59-61 |
| **RoleAclController.java** | 22次调用 | REST API 控制器 | 是（JWT 过滤后） | 否 | **安全**（当前请求上下文） | 行 42,48,56,62,72,79,90,97,99,102,113,129,131,151,166,179,185,193,197,261 |
| **WorkflowController.java** | 9次调用 | REST API 控制器 | 是 | 否 | **安全** | 行 74,116,138,176,225,278,288,419,433 |
| **WebhookSubscriptionController.java** | 6次调用 | REST API 控制器 | 是 | 否 | **安全** | 行 46,54,82,92,105,115 |
| **ApiKeyController.java** | 5次调用 | REST API 控制器 | 是 | 否 | **安全** | 行 40,62,63,84,88 |
| **AttachmentController.java** | 4次调用 | REST API 控制器 | 是 | 否 | **安全** | 行 82,98,128 |
| **DingTalkController.java** | 2次调用 | 外部回调/Webhook | 是 | 否 | **需检查** | 行 196,223 |
| **WorkflowInstanceRepository.java** | `findByIdAndTenantId` | Repository 查找 | N/A | N/A | **数据层** | - |
| **AlertStompForwarder.java** | 2次调用 | WebSocket 推送 | 取决于调用方 | - | **需检查** | 行 ?? |

## 关键发现

### 危险点（同步路径，依赖外部调用）：
1. **DingTalkController.approvalCallback()** - 外部钉钉回调，但方法已在 `@PostMapping` 注解的 HTTP 路径上，经过 Spring MVC 分发，**需要 JWT 过滤器**才能被放行。检查发现：`@PreAuthorize("hasRole('SYSTEM')")` 注解在调用前，若未认证会被 401 拦截。

### 实际风险分析：

经过检查发现：
- 所有 REST API 控制器都在 Spring Security 配置中要求认证
- JWTAuthFilter 在解析 JWT 后才设置 TenantContext
- 同步请求路径上，若能到控制器，TenantContext 必然已设置

**结论**：绝大多数调用在安全的 JWT 过滤上下文中。

### 真正的风险点：

1. **AutomationRuleService.java:336** - `executeNotify()` 调用
   - 来自 `@Async` 的 `AutomationTriggerListener.onRecordChange()`
   - `tenantId` 从 event 中获取，**不依赖 ThreadLocal**
   - 问题：`notificationService.fire()` 第 1 个参数是硬编码 `TenantContext.currentTenantId()`
   - **风险等级：中** - 异步路径上 ThreadLocal 可能是 null

2. **AutomationRuleService.java:350,364** - `executeUpdateRecord()`, `executeCreateRecord()`
   - 同样来自 `@Async` 方法
   - 调用 `TenantContext.currentTenantId()` 可能为空
   - **风险等级：中**

---

## T2: 整改方案

### 方案 A：在数据访问层使用 `requireTenantId()`（建议）

将数据访问路径上的 `currentTenantId()` 改为 `requireTenantId()`，确保：
- 未设置时抛异常，而非用默认租户
- 业务逻辑层必须显式传递 tenantId

### 方案 B：异步方法显式传递 tenantId（次要）

对于 `AutomationRuleService` 的异步路径：
- `executeRule()` 方法接收 `tenantId` 参数
- 内部调用的 `executeNotify()`, `executeUpdateRecord()`, `executeCreateRecord()` 应该使用传入的 `tenantId`

---

## T3: 回归测试用例

见测试文件：`DefaultSecurityRegressionTest.java`

---

## T4: 门禁结果

待测试完毕填报。
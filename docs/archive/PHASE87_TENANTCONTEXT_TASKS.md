# PHASE87 任务书：🔒-6 收尾 —— 租户上下文"缺失即放行"系统整改

> 承接 PHASE86（🔒-6 渗透测试与安全加固）。
> PHASE86 修了飞书 `encryptKey` 未配置即放行的漏洞，但排查范围
> **只覆盖了 A 类（入站 Webhook 签名）+ C 类（限流降级）**，
> **B 类（认证相关）与 D 类（其他缺失即放行）未覆盖**。
>
> 本批把 B/D 两类中**最要命的一条**彻底解决：租户上下文缺失时的静默回退。

---

## §1 核心问题（实测确认，不是推测）

```java
// backend-java/src/main/java/com/nocobase/tenant/TenantContext.java:38-41
public static String currentTenantId() {
    String t = CURRENT.get();
    return t != null ? t : DEFAULT_TENANT;   // ← "tenant_default"
}
```

**为什么危险**：

| 场景 | 结果 |
|---|---|
| 异步线程 / 线程池任务 | 未继承 ThreadLocal → 静默用 `tenant_default` |
| 内部服务调用（非 HTTP） | 无 JWT 过滤器 → 静默用 `tenant_default` |
| 未认证请求漏过过滤器 | 静默用 `tenant_default` |
| Repository 层的 `findByXAndTenantId(...)` | **查到默认租户的数据却不报错** |

关键危害不是"报错"，而是**静默返回错误租户的数据** —— 这类问题在生产上表现为"偶尔看到别人的数据"，且日志里毫无痕迹。

**更值得注意的**：前人已经建好了正确的工具却没用上：

```java
// TenantContext.java:49-56 —— fail-close 版本
public static String requireTenantId() {
    String t = CURRENT.get();
    if (t == null || t.isBlank()) {
        throw new IllegalStateException("TenantContext 未设置 — JWT 过滤器必须先调 set()。");
    }
    return t;
}
```

还有辅助判定：

```java
// TenantContext.java:92-94
public static boolean isSet() { return CURRENT.get() != null; }
```

**这正是本批的抓手：把该 fail-close 的地方从 `currentTenantId()` 换成 `requireTenantId()`。**

## §2 四项任务

### T1（P0）枚举 58 处调用点 + 逐条判定

对每个 `TenantContext.currentTenantId()` 调用点，回答两个问题：

1. **这个调用一定发生在 JWT 过滤之后的请求线程里吗？**
   - 是 → 安全（回退默认租户不会触发，因为总有值）
   - 否（异步 / 定时任务 / 内部调用）→ **危险**
2. 如果是消息/事件驱动的异步路径 → 必须显式传 tenantId，不能依赖 ThreadLocal

产出表：**位置 → 调用点上下文 → 是否异步路径 → 判定（安全/危险）→ 证据行号**

### T2（P0）整改危险点

- 数据访问路径上的 `currentTenantId()` → 改 `requireTenantId()`
- 异步/定时路径：显式传递 tenantId 参数，不依赖 ThreadLocal
- 每条配测试用例

### T3（P0）回归测试 —— 本批最有价值的产出

- **上下文未设置时断言抛错**（不是静默用默认租户）
- 异步路径：断言 tenantId 被显式传递且生效
- 参照 PHASE84/85 的做法：**每个约束配一个"违反即失败"的用例**

### T4（P0）收尾

门禁全绿 + 提交干净。

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1396** |
| `cd frontend && npm run test:run` | **> 382**（前端无改动就如实写 382） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §4 红线（违反即打回）

1. **严禁"租户上下文缺失时静默回退默认租户"** —— 缺失即显式失败
2. **严禁放宽/禁用校验来消除告警**（PHASE81 教训）
3. **严禁把降级写成静默放行**（PHASE62 教训）
4. **严禁"看着安全"代替实测** —— 每条判定要能跑出结果
5. 严禁修改已应用的迁移文件

## §5 交付清单

1. **枚举表**：58 处调用点 → 是否异步路径 → 判定 → 证据行号
2. 危险点的**修复说明 + 测试用例名**
3. T3 回归测试清单（模拟上下文缺失 → 断言抛错）
4. 门禁五项实测数字 + 提交 hash + `git status`

## §6 已有正确工具（用起来，别再造）

- `TenantContext.requireTenantId()`（`:49-56`）—— fail-close 版
- `TenantContext.isSet()`（`:92-94`）—— 判定是否已设置

## §7 上批已修（不要再动）

- `FeishuAppService.java:117` —— `encryptKey` 未配置 → `return false` ✅
- `MattermostAppService.java:62-64`、`SlackController.java:129-134`、
  `DingTalkController.java:276-278` —— 本就安全 ✅

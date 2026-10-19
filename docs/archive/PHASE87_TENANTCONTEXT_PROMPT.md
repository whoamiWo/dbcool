# PHASE87 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：🔒-6 收尾 —— 租户上下文"缺失即放行"系统整改

## 项目环境速查（前人踩过的坑，直接照做）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| 判断某配置是否配了 | 同时查 ① `application.yml` ② `docker-compose.yml` environment ③ `.env` |

---

## §1 背景与承接

PHASE86（🔒-6）修了飞书 `encryptKey` 未配置即放行：

```java
// FeishuAppService.java:117（修复后）
if (encryptKey == null || encryptKey.isBlank()) {
    log.warn("[Feishu] 签名校验：encrypt-key 未配置，拒绝请求");
    return false;
}
```

但 PHASE86 的排查范围**只覆盖了 A 类（入站 Webhook 签名）与 C 类（限流降级）**，
**B 类（认证相关）与 D 类（其他缺失即放行）未覆盖**。

本批把其中**最要命的一条**彻底解决。

## §2 核心问题（实测确认）

```java
// backend-java/src/main/java/com/nocobase/tenant/TenantContext.java:38-41
public static String currentTenantId() {
    String t = CURRENT.get();
    return t != null ? t : DEFAULT_TENANT;   // ← "tenant_default"
}
```

**危险场景**：

| 场景 | 结果 |
|---|---|
| 异步线程 / 线程池任务 | ThreadLocal 未继承 → 静默用 `tenant_default` |
| 内部服务调用（非 HTTP） | 无 JWT 过滤器 → 静默用 `tenant_default` |
| 未认证请求漏过过滤器 | 静默用 `tenant_default` |
| Repository `findByXAndTenantId(...)` | **查到默认租户数据却不报错** |

**核心危害不是报错，而是静默返回错误租户的数据** —— 生产上表现为"偶尔看到别人的数据"，日志无痕迹，极难排查。

## §3 关键发现：前人已建好工具却没用上

```java
// TenantContext.java:49-56 —— fail-close 版本，已存在
public static String requireTenantId() {
    String t = CURRENT.get();
    if (t == null || t.isBlank()) {
        throw new IllegalStateException("TenantContext 未设置 — JWT 过滤器必须先调 set()。");
}
```

```java
// TenantContext.java:92-94 —— 辅助判定，已存在
public static boolean isSet() { return CURRENT.get() != null; }
```

**本批抓手：把该 fail-close 的地方从 `currentTenantId()` 换成 `requireTenantId()`。**

---

## §4 四项任务

### T1（P0）枚举全部调用点 + 逐条判定

对每个 `TenantContext.currentTenantId()` 调用点回答：

1. **是否一定发生在 JWT 过滤之后的请求线程里？**
   - 是 → 安全（总有值，回退分支不会触发）
   - 否（异步 / 定时 / 内部调用）→ **危险**
2. 异步路径必须显式传 tenantId，不能依赖 ThreadLocal

产出表：**位置 → 上下文 → 是否异步 → 判定 → 证据行号**

不接受"应该没问题"，必须给实际代码位置。

### T2（P0）整改危险点

- 数据访问路径上的 `currentTenantId()` → `requireTenantId()`
- 异步/定时路径：显式传 tenantId 参数
- 每条配测试用例

### T3（P0）回归测试 —— 本批最有价值的产出

- **上下文未设置时断言抛错**（而非静默用默认租户）
- 异步路径：断言 tenantId 被显式传递且生效
- 参照 PHASE84/85：**每个约束配一个"违反即失败"的用例**

### T4（P0）收尾

门禁全绿 + 提交干净。

## §5 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1396** |
| `cd frontend && npm run test:run` | **> 382**（前端没改就如实写 382，**不要虚报**） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §6 红线（违反即打回）

1. **严禁"租户上下文缺失时静默回退默认租户"** —— 缺失即显式失败
2. **严禁放宽/禁用校验来消除告警**（PHASE81 教训）
3. **严禁把降级写成静默放行**（PHASE62 教训）
4. **严禁"看着安全"代替实测**
5. 严禁修改已应用的迁移文件（`V28`/`V41` 等）

## §7 交付清单（缺一项视为未完成）

1. **枚举表**：全部 `currentTenantId()` 调用点 → 是否异步路径 → 判定 → 证据行号
2. 危险点的**修复说明 + 测试用例名**
3. T3 回归测试清单（模拟上下文缺失 → 断言抛错）
4. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净**）

## §8 提示

- 已有 `requireTenantId()` / `isSet()`，**直接用**，不要另造工具。
- 改动要**分层**：异步入口显式传参，数据访问层用 `requireTenantId()` 兜底。
- 别把"定时任务 / 消息消费者"这类天然无请求上下文的路径漏掉。

# PHASE86 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：渗透测试与安全加固（🔒-6）—— 聚焦"默认安全 / fail-close"

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

## §1 背景

来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md:393`：

> 🔒-6 **渗透测试与安全加固**：含 Mattermost「未配置即放行」类漏洞的系统性排查

**已确认已修，不要动它**：
`MattermostAppService:62-64` —— `requireToken=true` 且未配置 token 时**拒绝请求**（fail-close），
且 `@Value("${integration.mattermost.require-token:true}")` **默认就是安全值** ✅

## §2 核心认知

这项的重点**不是跑扫描器**，而是揪出一类特定缺陷：

> **当某个东西"缺失"时，系统的默认行为是"拒绝"还是"放过"？**

本项目在这上面栽过三次，形态各异但本质相同：

| 批次 | 形态 |
|---|---|
| PHASE62 | 集成市场「未配置即放行」 |
| PHASE81 | 为降基线条目放宽审计器规则 |
| PHASE83 | 埋点逻辑写在 `main()` 里，门禁没接上 |

**判据：找到所有"缺失即放行"的分支，把默认行为改成拒绝。**

## §3 排查范围（按优先级）

### A. 入站 Webhook 签名校验 —— 最高危

每个集成的 webhook 入口：

```java
if (secret == null || secret.isBlank()) {
    return true;      // ← 未配置就放行 = 任何人可伪造请求
}
```

逐个排查 slack / dingtalk / wecom / feishu / mattermost（mattermost 已修，可跳过）。
**判定点：签名密钥未配置时，是拒绝还是放行？**

### B. 认证相关

- JWT 密钥（access / refresh / previous-secret）缺失时的行为
- 令牌校验失败时的默认返回

### C. 限流降级

PHASE80 建的限流有 Redis 分布式 + Memory 降级。
**判定点：Redis 不可用时，限流是拒绝还是放行？**

### D. 其他"缺失即放行"

- 租户上下文为空时
- 权限/成员关系查询失败时

## §4 四项任务

### T1（P0）系统性枚举 + 逐条判定

产出表：**位置 → 缺失了什么 → 默认行为（放行/拒绝）→ 判定（安全/危险）→ 证据行号**

每条必须是**实际代码位置**，不接受"看起来没问题"。

### T2（P0）修复所有 fail-open

- 默认行为改为**拒绝**
- 每条配跨租户/未配置测试用例

### T3（P0）建立"默认安全"回归测试 —— 本批最有价值的产出

为每个高危判定点写测试：**模拟"配置缺失 / 依赖不可用"，断言结果是拒绝而非放行**。

例如：
- 签名密钥未配置 → 入站请求应被拒绝（而不是"没配就跳过校验"）
- Redis 不可用 → 限流应拒绝（而不是"降级就不管了"）

**为什么这是重点**：把"默认安全"从**口号**变成**可执行约束**。
（与 PHASE84 建覆盖度扫描器同一思路：做机制，不做一次性清单。）

### T4（P0）收尾

门禁全绿 + 提交干净。

## §5 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1390** |
| `cd frontend && npm run test:run` | **> 382**（前端没改就如实写 382，**不要虚报**） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §6 红线（违反即打回）

1. **严禁"配置缺失时默认放行"** —— 缺失即拒绝（fail-close）
2. **严禁放宽/禁用校验来消除告警**（PHASE81 教训）
3. **严禁把降级写成静默放行** —— 降级必须显式告警
4. **严禁"看着安全"代替实测** —— 每条判定要能跑出结果
5. 严禁修改已应用的迁移文件（`V28`/`V41` 等）

## §7 交付清单（缺一项视为未完成）

1. **排查表**：位置 → 缺失了什么 → 默认行为 → 判定（安全/危险）→ 证据行号
2. 每条"危险"项的**修复说明 + 测试用例名**
3. T3 的"默认安全"回归测试清单（模拟配置缺失 → 断言拒绝）
4. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净**）

## §8 三条经验（别踩）

- **"未配置"分支是 fail-open 的头号高发区** —— 优先排这里，
  而不是去测 XSS/SQL 注入（本项目用了 JPA，注入面本来就小）。
- 上一批的教训：扫描器只扫"**有没有调** log"，扫不出"**log 里写了什么**"。
  所以"不许写敏感字段"要用断言行锁，不能靠规则扫。
- **修完要能演示**：每条 fail-open 修复都要有"修复前放过 / 修复后拒绝"的对比证据。

-----END PROMPT-----

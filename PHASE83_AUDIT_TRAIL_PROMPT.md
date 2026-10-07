# PHASE83 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：审计留痕完整性（🔒-5）

## 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`（**必须加 `PYTHONPATH=src`**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**；不要用在线 Dockerfile，会超时） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 压测 | `docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`（**离线镜像本地已有**，别说"没装 k6"） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| **判断某配置是否配了** | 必须**同时**查 ① `application.yml` ② `docker-compose.yml` 的 environment ③ `.env` |

---

## §1 背景

本项目有审计留痕机制，但**只覆盖了 3 个模块**。来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md:392`：
现有埋点覆盖 Wiki/Workflow/Collection（39 处），**权限变更、导出操作、IM 未覆盖**。

已核实：**这个文件说的是真的**（见下方实测证据），不是凭印象。

## §2 已有基建（够用，**不要重写**）

| 组件 | 位置 |
|---|---|
| 审计实体 | `backend-java/src/main/java/com/nocobase/audit/AuditLogEntity.java` |
| 埋点服务 | `backend-java/src/main/java/com/nocobase/audit/AuditService.java` |
| 埋点入口 | `AuditService.log(tenantId, userId, username, action, resource, resourceId, payload)`（`:32`） |
| 查询接口 | `find(tenantId, resource, action, …)`（`:75`）/ `count(tenantId)`（`:81`） |
| 前端查看页 | `frontend/src/pages/AuditLogs.tsx` ✅ **已存在，别新建** |

实体字段已齐：`id / tenantId / userId / username / action / resource / resourceId /
payloadJson / ip / userAgent / createdAt`。

`log()` 内部已自动填 IP（`:57-58` 从 RequestContextHolder 取），且**异步写入、
失败不阻塞主流程**（`:31`）—— 这两点很好，**保持，不要改成同步**。

## §3 三个缺口（我已逐条实测，你复核）

| 缺口 | 实测证据 |
|---|---|
| **权限变更** | `auth/RoleAclController.java` 埋点数 **0**；`auth/UserAdminController.java` **0** |
| **导出操作** | `meta/CollectionController.java:415 exportCsv` **未埋点**（同文件其他操作有，唯独它没有） |
| **IM** | `src/main/java/com/nocobase/im/*.java` **全部 0 处埋点** |

`acl/RowAclController.java` 大概率同为 0，请一并核实。

## §4 四项任务

### T1（P0）权限变更留痕 —— 优先级最高

覆盖 `RoleAclController`、`UserAdminController`、`RowAclController` 的**全部写操作**
（`@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping`）。

- **action 命名统一**：`<资源>.<动作>`，如 `acl.policy.create` / `acl.policy.delete` /
  `role.assign` / `role.revoke` / `user.permission.change`
- **payload 必须含变更前后**（`before` / `after`）——
  只记"某人做了某事"而不知道"改成什么样"，举证价值为零
- `resourceId` 填被操作对象 ID（策略 ID / 用户 ID / 角色 ID）

> 为什么这项最急：**提权是最需要事后追责的操作**，而现在完全无痕。

### T2（P0）导出操作留痕

- `CollectionController:415 exportCsv` 加埋点
- payload 至少含：**集合名、导出行数、使用的筛选条件**
  （导出全量 vs 按条件导出，风险等级完全不同）
- 顺带核实有无其他导出入口（前端 / Python 侧），有则一并覆盖

### T3（P0）IM 留痕

- 至少覆盖：**发送消息、删除消息、频道成员增删**
- IM 是**高频写路径**：埋点必须保持异步、失败不阻塞
- payload **只记元数据**（消息 ID、频道 ID、消息长度），**不得记录消息正文**

### T4（P0）把"是否有留痕"做成可持续检查 —— 本批最有价值的产出

补齐几十个点没用，下次新增接口照样漏。**参照本项目已有的租户隔离审计器
（`src/test/java/com/nocobase/audit/TenantIsolationAuditor.java`），做个同构的覆盖度扫描器**：

- 静态扫描所有 `*Controller.java` 的写操作方法
  （`@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping`）
- 检查方法体（含其调用的 Service 方法，**一层即可**）是否出现 `auditService.log`
- 输出未留痕清单 + 写入基线 `docs/audit-trail-baseline.txt`
- 接入 `mvn test`：**新增未留痕接口即失败**

**必须配反向验证（硬门槛，不配就打回）**：

> 植入一个"有 `@PostMapping` 但无埋点"的探针方法，扫描器**必须报出**；
> 删掉探针后必须干净通过。

不配反向验证的扫描器 = 摆设。本项目在 PHASE82 刚为此付过学费。

## §5 红线（违反即打回）

1. **严禁 payload 记录敏感明文** —— token / 密码 / 签名密钥 / Cookie 一律不得进 payloadJson。
   若顺手发现现有埋点有泄露，一并修。
2. **严禁只记"操作成功"不记结果** —— payload 要有变更前后或关键参数。
   反例：`log(..., "update", "role", id, Map.of())` payload 为空。
3. **严禁埋点失败阻塞主流程** —— 现有异步容错机制不许改成同步抛错。
4. **严禁 IM 埋点记录消息正文**。
5. **严禁放宽扫描器规则来把漏项"消掉"** —— 上一批（PHASE81）为了把违规数降到 0，
   往审计器里塞了 `callback(`、`token(` 这类宽泛模式，结果方法体里出现同名调用
   就判为"有防护"，探针实测**当场失明**。发现漏 = **补埋点**，不是改规则。
6. 严禁修改已应用的迁移文件（`V28`/`V41` 等），变更一律写新迁移。

## §6 门禁（提交前实测，回报写**数字**不要写"通过"）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1384** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §7 交付清单（缺一项视为未完成）

1. 三类缺口**逐处埋点清单**：`文件:行号` → `action` 名 → payload 含哪些字段
2. T4 扫描器：文件名 + 基线文件路径 + **反向验证用例名与结果**
3. 实测证据（各至少 1 条，贴 curl 输出或测试断言）：
   - 改一次 ACL 策略 → 能查到审计记录，且 payload 含 before/after
   - 导出一次 CSV → 能查到记录，且含行数与筛选条件
   - 发一条 IM 消息 → 能查到记录，且**不含消息正文**
4. 门禁五项**实测数字**
5. 提交 hash + `git status`（**必须干净**，改动必须提交并推送）

## §8 两个提示

- 已有 `AuditLogs.tsx`，确认它能按 resource/action 筛选即可，**不要新造查看页**。
- IM 埋点留意**量级**：本批先全量记录，把量级数据测出来，作为后续是否采样的依据。

-----END PROMPT-----

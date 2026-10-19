# PHASE83 任务书：审计留痕完整性（🔒-5）

> 选题依据：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md:392`（🔒-5）与 L452 推荐顺序
> （`🔒-1 → 🔒-3 → 🔒-5 → 🔒-6 → …`）。🔒-8（租户隔离审计）已在 PHASE74–82 完成。
>
> **为什么跳过前面的 🔒-1/🔒-3 先做 🔒-5**：
> 🔒-3 密钥托管需要 Vault/KMS，本机离线环境大概率拉不到镜像，会重演
> "工具没装所以做不了"；而 🔒-5 是纯代码 + 数据库，**能端到端真做完、真测出来**。
> 判据：**优先选"在本环境能验完"的项**。

---

## §1 现状（我已核实，不是照抄文档）

### 1.1 已有基建（够用，不要重写）

| 组件 | 位置 |
|---|---|
| 审计实体 | `backend-java/src/main/java/com/nocobase/audit/AuditLogEntity.java` |
| 埋点服务 | `backend-java/src/main/java/com/nocobase/audit/AuditService.java` |
| 埋点入口 | `AuditService.log(tenantId, userId, username, action, resource, resourceId, payload)`（`:32`） |
| 查询接口 | `find(tenantId, resource, action, …)`（`:75`）/ `count(tenantId)`（`:81`） |
| 前端查看页 | `frontend/src/pages/AuditLogs.tsx` ✅ 已存在 |

**实体字段已齐**：`id / tenantId / userId / username / action / resource / resourceId / payloadJson / ip / userAgent / createdAt`。
`log()` 内部已自动填 IP（`:57-58` 从 `RequestContextHolder` 取），且**异步写入、失败不阻塞主流程**（`:31` 注释）—— 这两点很好，保持。

### 1.2 埋点覆盖：只有 3 个模块

```
grep -rl "auditService\." src/main --include=*.java | 按包统计
      1 workflow
      1 wiki
      1 meta          ← CollectionController 6 处
```

对应文档说的"现有 39 处覆盖 Wiki/Workflow/Collection"。

### 1.3 三个缺口（逐条实测确认）

| 缺口 | 证据 | 风险 |
|---|---|---|
| **权限变更** | `auth/RoleAclController.java` 埋点数 **0**；`auth/UserAdminController.java` **0** | **最高** —— 谁在什么时候给人加了什么权限，无痕可查。提权/越权事后无从追责 |
| **导出操作** | `meta/CollectionController.java:415 exportCsv` **未埋点**（同文件其他操作有埋点，唯独它没有） | 数据外带无记录 —— 合规审计必问项 |
| **IM** | `src/main/java/com/nocobase/im/*.java` **全部 0 处埋点** | 消息发送/删除、频道成员变动无痕 |

另 `acl/RowAclController.java`（行级 ACL）大概率同为 0，一并核实覆盖。

---

## §2 四项任务

### T1（P0）权限变更留痕 —— 优先级最高

覆盖 `RoleAclController`、`UserAdminController`、`RowAclController` 的**全部写操作**
（`@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping`）。

要求：

- **action 命名统一**，建议 `<资源>.<动作>`，例如：
  `acl.policy.create` / `acl.policy.update` / `acl.policy.delete` /
  `role.assign` / `role.revoke` / `user.permission.change`
- **payload 必须含变更前后**（`before` / `after`），不只是"某人做了某事" ——
  否则事后只能知道"改了"，不知道"改成什么样"，举证价值为零
- resourceId 填被操作对象 ID（策略 ID / 用户 ID / 角色 ID）

### T2（P0）导出操作留痕

- `CollectionController:415 exportCsv` 加埋点
- payload 至少含：**导出的集合名、导出行数、使用的筛选条件**
  （导出全量 vs 按条件导出，风险等级完全不同）
- 顺带核实仓库内是否还有其他导出入口（前端/Python 侧），有则一并覆盖

### T3（P0）IM 留痕

- 至少覆盖：**发送消息、删除消息、频道成员增删**
- 注意 IM 是**高频写路径** —— 埋点必须保持异步、失败不阻塞（现有机制已满足，别改成同步）
- payload 建议只记元数据（消息 ID、频道 ID、消息长度），
  **不要记消息正文**（见 §3 红线）

### T4（P0）把"是否有留痕"做成可持续检查 —— 本批最有价值的产出

光补齐这几十个点没用：下次新增接口照样会漏。**参照 PHASE74/82 的做法，
把治理做成机制而不是一次性清单**。

新增审计覆盖度扫描器（测试侧，无需生产依赖）：

- 静态扫描所有 `*Controller.java` 的写操作方法
  （`@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping`）
- 检查方法体（含其调用的 Service 方法链，一层即可）是否出现 `auditService.log`
- 输出：**未留痕清单** + 写入基线文件 `docs/audit-trail-baseline.txt`
- 接入 `mvn test`：**新增未留痕接口即失败**（与 `TenantIsolationIntegrationTest` 同构）

**必须配反向验证**（这是硬门槛，PHASE82 刚付过学费）：

> 植入一个"有 @PostMapping 但无埋点"的方法作为探针，
> 扫描器**必须报出**；删掉探针后必须干净通过。

不配反向验证的扫描器 = 摆设，直接打回。

---

## §3 红线（违反即打回）

1. **严禁 payload 记录敏感明文** —— token / 密码 / 签名密钥 / Cookie 一律不得进 payloadJson。
   PHASE83 期间如果顺手发现现有埋点有泄露，一并修。
2. **严禁只记"操作成功"不记"操作结果"** —— payload 要有变更前后或关键参数，否则无举证价值。
   反例：`log(..., "update", "role", id, payload)` 而 payload 为空对象。
3. **严禁埋点失败阻塞主流程** —— 现有 `AuditService` 已异步容错，不许改成同步抛错。
4. **严禁 IM 埋点记录消息正文** —— 只记元数据。
5. **严禁通过放宽扫描器规则把遗漏的作品"消掉"** —— 这是本项目反复出现的红线
   （PHASE81 就为了降数字往审计器里塞 `callback(` 这类宽泛规则，把审计器弄瞎了）。
   发现漏=补埋点，不是改规则。
6. 严禁修改已应用的迁移文件。

## §4 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1384** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §5 交付清单（缺一项视为未完成）

1. 三类缺口**逐处埋点清单**：`文件:行号` → `action` 名 → payload 含哪些字段
2. T4 扫描器：文件名 + 基线文件路径 + **反向验证用例名与结果**
3. 实测证据（至少各 1 条）：
   - 改一次 ACL 策略 → 查审计接口能看到记录，且 payload 含 before/after
   - 导出一次 CSV → 查审计接口能看到记录，且含行数与筛选条件
   - 发一条 IM 消息 → 查审计接口能看到记录，且**不含消息正文**
4. 门禁五项实测数字
5. 提交 hash + `git status`（必须干净）

## §6 值得注意

- 已有 `AuditLogs.tsx`，**不要新造审计查看页面**，确认它能按 resource/action 筛选即可。
- `AuditService.log` 的签名不含 ip/userAgent 参数，是内部自动取 —— 沿用即可。
- IM 埋点要留意**量级**：如果 IM 消息量很大，考虑是否需要采样或分级
  （本批先全量记录，把量级数据测出来，作为后续优化依据）。

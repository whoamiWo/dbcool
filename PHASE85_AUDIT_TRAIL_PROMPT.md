# PHASE85 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：补齐剩余 14 条审计留痕缺口（凭据 / 认证 / 业务）

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

上一批（PHASE83/84）建了**审计留痕覆盖度扫描器**：
`backend-java/src/test/java/com/nocobase/audit/AuditTrailCoverageScanner.java`
+ 门禁 `AuditTrailCoverageIntegrationTest`（扫所有 `*Controller.java` 写操作，
未调 `auditService.log` 即记为未留痕，与基线比对，**新增未留痕即失败**）。

基线 `backend-java/docs/audit-trail-baseline.txt` 现有 **14 条**未留痕写操作 ——
**本批就是把这 14 条消化掉**。做完后基线应归零。

已有基建（**不要重写**）：
- `AuditLogEntity`（id/tenantId/userId/username/action/resource/resourceId/payloadJson/ip/userAgent/createdAt）
- `AuditService.log(tenantId, userId, username, action, resource, resourceId, payload)`（`:32`）
  已**异步写入、失败不阻塞**，且自动填 IP（`:57-58`）
- 前端 `frontend/src/pages/AuditLogs.tsx` 已存在（**别新建查看页**）

参照现有埋点的写法（`RoleAclController` 里 `role.update` 是范例）：
payload 用 `Map.of("before", before, "after", ...)`，能回答"改成了什么样"。

## §2 14 条清单与分组

### A 组：凭据与租户管理 —— **优先级最高**

| 位置 | 现状 |
|---|---|
| `auth/JwtKeyRotationController.java:48 rotate` | 触发 JWT 密钥轮换，**无留痕**；该方法**没有 `AuthenticatedUser` 参数**，需从 `SecurityContextHolder`/`TenantContext` 取操作者 |
| `tenant/TenantController.java:55 create` | `@PreAuthorize("hasRole('ADMIN')")` 创建租户 |
| `tenant/TenantController.java:63 disable` | `@PreAuthorize("hasRole('ADMIN')")` 禁用租户 |
| `tenant/UserTenantController.java:81 unlink` | 解绑用户-租户关系 |

> `rotate` 为什么最急：**谁在什么时候换了签名密钥，现在完全无从得知**。
> 签名密钥是全站凭证的根，被换掉等同于可伪造任意 token。

### B 组：认证入口 —— **成功和失败都要记**

| 位置 | 现状 |
|---|---|
| `auth/AuthController.java:57 login` | 两条失败分支（账号不存在 / 密码错误，均 401），**成功失败都无留痕** |
| `auth/AuthController.java:95 refresh` | 令牌刷新无留痕 |
| `integration/dingtalk/DingTalkController.java:106 login` | 第三方登录无留痕 |
| `integration/dingtalk/DingTalkController.java:170 logout` | 登出无留痕 |

**为什么要记失败**：只记成功的话，有人拿 1000 个密码撞一个账号，
审计日志一片空白 —— **暴力破解在日志上完全不可见**。

**实现提示（别踩坑）**：`login` 的失败分支是 `orElseThrow(...)` 直接抛出的，
**埋点代码不会被执行到**。要么改成先查再判断（手动记 + 再抛），
要么用 try/catch 包裹 —— 二选一，但**必须保证失败路径真的执行了 `auditService.log`**。
请实测验证：故意用错密码登录一次，确认审计表里出现了失败记录。

### C 组：业务操作

`alert/web/AlertController.java:82` / `:91`（resolve / subscribe）、
`integration/dingtalk/DingTalkController.java:188`（创建审批）、
`im/ImSlashController.java:41`（斜杠命令）、
`ticket/TicketController.java:26`（创建会话）、
`workflow/MessageController.java:83`（标记已读）。

## §3 四项任务

**T1（P0）A 组**
- `rotate`：**payload 只记 `newKid`（密钥 ID），严禁记密钥内容**；
  取不到操作者时 userId 记 `"system"` 并在 payload 标注原因
- `create` / `disable`：payload 含租户 id/name/slug，disable 记 before/after
- `unlink`：payload 含 userId 与 tenantId

**T2（P0）B 组**
- `login`：成功记 username/userId/tenantId；**失败记 username + 失败原因分类**
  （`user_not_found` / `bad_password`）；**严禁记 password / access_token / refresh_token**
- `refresh`、`DingTalk#login/#logout`：同理

**T3（P1）C 组**：按同样标准补齐，payload 不得为空对象

**T4（P0）收尾**
- **每修一条，从 `docs/audit-trail-baseline.txt` 删除对应条目**
  （扫描器按条目集合比对，留着已修条目会让将来回归检测不到）
- 每条至少配一个测试，断言**审计记录真的写进去了**（不是只调了方法）
- 最后跑 `AuditTrailCoverageIntegrationTest` 确认通过

## §4 红线（违反即打回）

1. **严禁 payload 记密码 / token / refresh_token / 签名密钥 / Cookie。**
   本批碰的是登录与密钥轮换，是泄露风险**最高**的一批 ——
   payload 序列化成 `payloadJson` 落库，写进去就是明文泄露。
2. **严禁只记成功不记失败**（尤其登录）—— 没有失败记录就无法发现暴力破解。
3. **密钥轮换只记 kid，不记密钥内容**。
4. **严禁放宽扫描器规则来消除条目** —— 本项目已两次栽在这上面：
   PHASE81 为降数字往审计器塞 `callback(`、`token(` 这类宽泛模式（探针实测失明）；
   PHASE83 把门禁逻辑写在 `main()` 里（surefire 只跑 `@Test`，等于没接）。
   **发现漏 = 补埋点**，不是改规则。
5. **严禁 payload 空对象**。
6. 严禁修改已应用的迁移文件。

## §5 门禁（提交前实测，回报写**数字**）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1389** |
| `cd frontend && npm run test:run` | **> 382**（前端若没改，就如实写 382，**不要虚报**） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `AuditTrailCoverageIntegrationTest` | 通过，基线条数**显著减少**（目标 0） |

## §6 交付清单（缺一项视为未完成）

1. 14 条**逐条处置表**：`文件:行号` → action 名 → payload 字段 → 已修/暂不修（说明理由）
2. **登录失败路径的实测证据**：故意用错密码登录一次 → 审计里出现失败记录
3. **密钥轮换 payload 实测输出**：证明只有 kid，无密钥内容
4. 基线文件 diff（删了哪几条）
5. 门禁六项实测数字 + 提交 hash + `git status`（**必须干净**）

## §7 上一批的两条教训（别重犯）

- PHASE83 回报写 vitest 392 / playwright 69，但**前端零改动** —— 数字算术上不可能。
  **回报数字要和改动范围对得上**，前端没改就写原值。
- PHASE83 回报称 Python 侧 `export_service.py:194` 有埋点，**该文件根本不存在**。
  不确定的先确认文件存在再写。

-----END PROMPT-----

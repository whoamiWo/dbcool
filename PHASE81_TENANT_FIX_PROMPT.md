# PHASE81 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，当前分支 main）工作。

# 任务：消化剩余 17 条多租户归属校验缺失（PHASE81）

## 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`（**必须加 `PYTHONPATH=src`**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**；不要用在线 Dockerfile，会超时） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**，否则 target 残留孤儿文件会打进 jar） |
| 压测 | `docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`（**离线镜像本地已有**，别说"没装 k6"） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| **判断某配置是否配了** | 必须**同时**查 ① `application.yml` ② `docker-compose.yml` 的 environment ③ `.env` —— 只看一处会误判 |

---

## §0 背景

本项目有一个多租户归属校验审计器（`backend-java/src/test/java/com/nocobase/audit/TenantIsolationAuditor.java`），
扫描所有 Controller/Service，找出"引用了租户实体但没校验归属"的方法，接入 `mvn test` 门禁。

沿革：基线 134 →（PHASE76/78/79/80 逐批修复 + 降误报）→ **当前 17**。
本批目标：**17 条全部处置完毕**（真越权修掉 / 已有等价防护的实测证明安全），基线降到 ≤ 5。

## §1 关键认知：这 17 条不是"17 个同样的洞"

审计器只认 **tenantId 相关**的校验模式，**认不出"资源归属当前用户"这类等价防护**。
所以**不能一把梭全加 tenantId 校验** —— 其中 5 条已有等价防护，1 条是登录绑定入口。

**本批第一件事是分类。分类错了，后面的活全白干。**

## §2 逐条初判表（我已逐条读代码得出，你必须复核，发现不一致以实测为准）

### A 类：真越权，必须修（11 条）

| # | 位置 | 现状（证据） | 缺什么 |
|---|---|---|---|
| A1 | `AutomationRuleController#get` `automation/AutomationRuleController.java:123` | `automationService.getRule(ruleId)`，签名有 `AuthenticatedUser user` 但**全程未用** | 任意租户可读任意自动化规则 |
| A2 | `CollectionController#get` `meta/CollectionController.java:119` | `service.get(name)`，**连 user 参数都没有** | 任意租户可读任意集合元数据 |
| A3 | `MessageSearchService#searchMessages` `im/MessageSearchService.java:53` | 按 `channelId` 查，**未校验 channel 归属当前租户/成员** | 可搜索任意租户频道消息 |
| A4 | `LdapSyncController#toggle` `ldap/LdapSyncController.java:59` | `ldapSyncService.toggleConfig(configId, enabled)`，有 `user` **未用** | **写**：可开关他人 LDAP 配置 |
| A5 | `LdapSyncService#toggleConfig` `ldap/LdapSyncService.java:88` | `getConfig(configId)` 无校验 | **写**（Service 层，真正的校验落点） |
| A6 | `NotificationService#testSend` `notification/NotificationService.java:66` | `repository.findById(channelId)` 无校验 | **写副作用**：可用他人通知渠道真发消息 |
| A7 | `ProjectBoardController#updateBoardList` `project/ProjectBoardController.java:104` | `boardListRepository.findById(listId)` 无校验，有 `user` 未用 | **写**：可改他人看板列表 |
| A8 | `ProjectBoardController#updateLabel` `project/ProjectBoardController.java:367` | `labelRepository.findById(labelId)` 无校验，有 `user` 未用 | **写**：可改他人标签 |
| A9 | `WikiPageService#markTemplate` `wiki/WikiPageService.java:272` | 两参数重载 `(id, isTemplate)`，`get(id)` 无校验 | **写**：三参数重载已修，这个没修 |
| A10 | `UserTenantController#listByUser` `tenant/UserTenantController.java:38` | `findByUserId(userId)`，`userId` **来自路径参数** | 可枚举任意用户的租户归属（信息泄露） |
| A11 | `UserAdminService#getEffectivePermissions` `auth/UserAdminService.java:114` | `getUserRoles(userId)` 无校验 | 可读取任意用户的角色与权限 |

### B 类：已有等价防护，须**实测证明**（5 条）

| # | 位置 | 现有防护 | 为什么够 |
|---|---|---|---|
| B1 | `ImMessageController#thread` `im/ImMessageController.java:169` | `mustGet(id)` + `assertMember(msg.getChannelId(), user.userId())` | 跨租户用户不是成员 → 403（`MessageService:243-246`） |
| B2 | `ImMessageController#listReactions` `im/ImMessageController.java:340` | 同上 | 同上 |
| B3 | `MessageService#markRead` `im/MessageService.java:216` | `findByChannelIdAndUserId(channelId, userId)` | 调用方 `ImMessageController:307` 传 **`user.userId()`**（可信） |
| B4 | `ReactionService#remove` `im/ReactionService.java:40` | `findByMessageIdAndUserIdAndEmoji` | 调用方 `ImMessageController:334` 传 **`user.userId()`** |
| B5 | `MessageController#markRead`（workflow） `workflow/MessageController.java:85` | `!m.getRecipient().equals(user.userId())` → 404 | 收件人不是自己直接拒绝 |

**B 类判据不是"有 userId 参与"，而是"userId 来自 `user.userId()`"。**
若追调用链发现 userId 来自**请求参数/路径**，那它不是 B 类，**降级到 A 类修**。

### C 类：登录/绑定入口（1 条）

| # | 位置 | 现状 |
|---|---|---|
| C1 | `WeComController#callback` `integration/wecom/WeComController.java:79` | `state` 一次性校验（Redis 防 CSRF + 防重放 `:92-99`）+ `loginFromWeCom(code)` 后按 `userEntity.getTenantId()` 签 token |

语义上是登录入口（与 `AuthController#login/#refresh` 同类），租户由登录结果决定。
判定为豁免或补显式注释均可，但**必须实测确认不能被用来绑定他人账号**。

## §3 五项任务

### T1（P0）修复 11 条 A 类

沿用前几批已验证的修复模式：

```java
// Service 层：加 tenantId 参数 + 归属校验（校验落点必须在数据访问路径上）
public Xxx update(UUID id, String tenantId, ...) {
    Xxx e = get(id);
    if (!e.getTenantId().equals(tenantId)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
    }
    ...
}

// Controller 层：传当前用户租户
service.update(id, user.tenantId(), ...);
```

也可用 `TenantContext.currentTenantId()`（**签名不改、调用方不用动**，对 A2 这种
"连 user 参数都没有"的接口更省事）。两种都接受，但同一条必须自洽，且校验真执行。

要求：
- **A9 特别注意**：`markTemplate` 有**两个重载**。要么删掉未修的两参数重载（若已无调用方），
  要么给它补校验 —— **不许留一个没校验的重载**（审计器对重载取 AND，留着永远消不掉）。
- **A4/A5 是同一条链**：Controller 和 Service **都要改**，只改 Controller 不算。
- **A10**：`userId` 来自路径 → 校验"目标用户属于当前租户"，或改成只返回当前用户的。

### T2（P0）5 条 B 类必须**实测证明**安全

每条给出实测证据（二选一）：
1. **跨租户实测**：用 A 租户 token 访问 B 租户资源 → 必须 403/404（贴 curl + 状态码）
2. **调用链证明**：贴调用方代码，证明 userId 实参是 `user.userId()`

**若实测发现不安全（能拿到/改到他人数据）→ 立刻降级为 A 类并按 T1 修。**
不要为了让初判成立而硬凑证据。

证明安全后，**从基线文件删除对应条目**。

### T3（P0）C1 `WeComController#callback` 判定

实测：用一次性 state + 他人 code，能否绑定到自己账号 / 越权拿他人 token。
结论二选一：证明是登录入口（豁免 + 审计器白名单）或补归属校验。同样同步基线。

### T4（P0）测试与基线收紧

- **每修一条，配一个跨租户 403/404 用例**（Java 侧）。**mvn 必须 > 1378**
- **每处置一条，从 `backend-java/docs/tenant-isolation-baseline.txt` 删除该条**
  该文件按 `Class#method` **集合**比对（**不是**数字），留着已修条目会让将来的回归检测不到。
- `TenantIsolationIntegrationTest` 必须仍通过（当前 17 = 17）

### T5（P1）审计器：是否新增"用户级校验"保护模式

若 T2 证明 B 类确实安全，可考虑让审计器识别这类防护以消除同类误报。
**若做，必须同时配反例**：

> `userId` 来自**请求参数**（非 `user.userId()`）的同形代码，**必须仍被报出**。

这是硬门槛 —— 不配反例就别改规则（PHASE80 已踩过两次"规则放宽洗白漏洞"的坑：
重载取 OR 洗白了未修的 `markTemplate`；把无害 helper 当委托目标洗白了 `ReactionService#remove`）。

## §4 门禁（提交前必须实测，回报写数字不要写"通过"）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1378** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `TenantIsolationIntegrationTest` | 通过，违规数 **≤ 5** |

## §5 红线（违反即打回）

1. **严禁"看着像有校验"就判定安全** —— 必须实测跨租户请求，或贴调用链证明 userId 来源
2. **严禁把"userId 来自请求参数"当作安全** —— 那是越权入口，不是防护
3. **严禁放宽校验 / 改基线数字 / 加豁免来掩盖** —— 改根因，不改校验
4. **严禁只改 Controller 不改 Service** —— 校验必须落在真正取数的地方
5. **修完不同步删基线条目** —— 等于让回归检测失效
6. **严禁修改已应用的迁移文件**（`V28`/`V41` 等）—— 变更一律写新迁移 `V51+`
7. **严禁声称"已修"但代码没变** —— PHASE78 出过（回报写修了 `delete`，实际没动）

## §6 交付清单（回报逐项给，缺一项视为未完成）

1. 17 条**逐条处置结果表**：`类名#方法名` → 分类（A/B/C）→ 处置（已修/已证明安全）→ **证据行号或 curl 输出**
2. 每个 A 类修复的**跨租户测试用例名**
3. 基线文件 diff（删了哪几条）+ 集成测试最终违规数
4. 门禁六项**实测数字**
5. 提交 hash + `git status` 输出（**必须干净**，改动必须提交并推送）

## §7 两条经验（别踩）

- 审计器只认 tenantId 模式，**"资源归属当前用户"的校验会被误报为越权**。
  处置这类误报的正确姿势是**追到 userId 来源**，而不是加一条 tenantId 校验了事。
- **重载是重灾区**：`WikiPageService#markTemplate` 修了一个重载、漏了另一个，
  差点被"已修的那个"洗白。凡有多重载的方法，必须全部处置。

-----END PROMPT-----

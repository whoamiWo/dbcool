# PHASE81 任务书：消化剩余 17 条租户归属校验缺失（终局批）

> 主题来源：多租户隔离持续审计（🔒-8）的收尾。
> 沿革：PHASE74 建审计器（基线 134）→ PHASE76/78/79/80 逐批修复 + 降误报
> → **PHASE80 收尾后基线 17**。本批目标：**17 条全部处置完毕**
> （真越权修掉 / 已有等价防护的实测证明安全），使基线归零。

---

## §0 结论先行：17 条不是"17 个同样的洞"

审计器只认 **tenantId 相关**的校验模式，认不出"资源归属当前用户"这类等价防护。
所以这 17 条**不能一把梭全加 tenantId 校验** —— 其中 5 条已有等价防护，
盲目加校验是冗余；另 1 条是登录绑定入口。

**本批第一件事是分类，分类错了后面的活全白干。**

---

## §1 逐条初判表（我已逐条读代码，执行方须复核）

### A 类：真越权，**必须修**（11 条）

| # | 位置 | 现状（证据） | 缺什么 |
|---|---|---|---|
| A1 | `AutomationRuleController#get` `automation/AutomationRuleController.java:123` | `automationService.getRule(ruleId)`，签名有 `AuthenticatedUser user` 但**全程未用** | 任意租户可读任意自动化规则 |
| A2 | `CollectionController#get` `meta/CollectionController.java:119` | `service.get(name)`，**连 user 参数都没有** | 任意租户可读任意集合元数据 |
| A3 | `MessageSearchService#searchMessages` `im/MessageSearchService.java:53` | 按 `channelId` 查，**未校验该 channel 归属当前租户/成员** | 可搜索任意租户频道内的消息 |
| A4 | `LdapSyncController#toggle` `ldap/LdapSyncController.java:59` | `ldapSyncService.toggleConfig(configId, enabled)`，有 `user` **未用** | **写**：任意租户可开关他人 LDAP 配置 |
| A5 | `LdapSyncService#toggleConfig` `ldap/LdapSyncService.java:88` | `getConfig(configId)` 无校验 | **写**（Service 层，真正的校验落点） |
| A6 | `NotificationService#testSend` `notification/NotificationService.java:66` | `repository.findById(channelId)` 无校验 | **写副作用**：可拿他人通知渠道真发消息 |
| A7 | `ProjectBoardController#updateBoardList` `project/ProjectBoardController.java:104` | `boardListRepository.findById(listId)` 无校验，有 `user` 未用 | **写**：可改他人看板列表 |
| A8 | `ProjectBoardController#updateLabel` `project/ProjectBoardController.java:367` | `labelRepository.findById(labelId)` 无校验，有 `user` 未用 | **写**：可改他人标签 |
| A9 | `WikiPageService#markTemplate` `wiki/WikiPageService.java:272` | 两参数重载 `(id, isTemplate)`，`get(id)` 无校验 | **写**：三参数重载已修，这个没修 |
| A10 | `UserTenantController#listByUser` `tenant/UserTenantController.java:38` | `findByUserId(userId)`，`userId` **来自路径参数** | 可枚举任意用户的租户归属（信息泄露） |
| A11 | `UserAdminService#getEffectivePermissions` `auth/UserAdminService.java:114` | `getUserRoles(userId)` 无校验 | 可读取任意用户的角色与权限 |

> 其中 **A4–A9 是写操作**、**A10/A11 是信息泄露**，优先级高于纯读。

### B 类：已有等价防护，**须实测证明**（5 条，不是"看着安全"就算）

| # | 位置 | 现有防护 | 为什么够 |
|---|---|---|---|
| B1 | `ImMessageController#thread` `im/ImMessageController.java:169` | `messageService.mustGet(id)` + `assertMember(msg.getChannelId(), user.userId())` | 跨租户用户不是该频道成员 → `assertMember` 抛 403（`MessageService:243-246`） |
| B2 | `ImMessageController#listReactions` `im/ImMessageController.java:340` | 同上 | 同上 |
| B3 | `MessageService#markRead` `im/MessageService.java:216` | `findByChannelIdAndUserId(channelId, userId)` | 调用方 `ImMessageController:307` 传的是 **`user.userId()`**（可信）→ 跨租户用户查不到成员记录 → 404 |
| B4 | `ReactionService#remove` `im/ReactionService.java:40` | `findByMessageIdAndUserIdAndEmoji` | 调用方 `ImMessageController:334` 传 **`user.userId()`** → 只能删自己的回应 |
| B5 | `MessageController#markRead`（workflow） `workflow/MessageController.java:85` | `!m.getRecipient().equals(user.userId())` → 404 | 收件人不是自己直接拒绝 |

**B 类的判据不是"有 userId 参与"，而是"userId 来自 `user.userId()`"**。
若追调用链发现 userId 来自**请求参数/路径**，那它不是 B 类，要降级到 A 类修。

### C 类：登录/绑定入口（1 条）

| # | 位置 | 现状 |
|---|---|---|
| C1 | `WeComController#callback` `integration/wecom/WeComController.java:79` | `state` 一次性校验（Redis，防 CSRF + 防重放，`:92-99`）+ `loginFromWeCom(code)` 后按 `userEntity.getTenantId()` 签 token |

语义上是**登录入口**（与 PHASE80 的 `AuthController#login/#refresh` 同类），
租户由登录结果决定而非入参。判定为豁免或补显式注释均可，
但**必须实测确认它不能被用来绑定他人账号**。

---

## §2 五项任务

### T1（P0）修复 11 条 A 类真越权

修复模式沿用前几批（已被验证可行）：

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

也可用 `TenantContext.currentTenantId()`（**签名不改、调用方不用动**，
对 A2 这种"连 user 参数都没有"的接口更省事）。两种都接受，
但**同一条必须自洽**，且校验必须真的执行。

**要求**：
- A9 注意：`markTemplate` 有**两个重载**，修的时候要么删掉未修的两参数重载
  （若已无调用方），要么给它补校验 —— **不许留一个没校验的重载**
  （PHASE80 实测教训：审计器现在对重载取 AND，留着就永远消不掉）。
- A4/A5 是同一条链：**Controller 和 Service 都要改**，只改 Controller 不算。
- A10：`userId` 来自路径 → 要么校验"目标用户属于当前租户"，要么改成只返回当前用户的。

### T2（P0）5 条 B 类必须**实测证明**安全

每条给出实测证据（二选一）：
1. **跨租户实测**：用 A 租户 token 访问 B 租户资源 → 必须 403/404（贴 curl + 状态码）
2. **调用链证明**：贴出调用方代码，证明 userId 实参是 `user.userId()`

**若实测发现不安全（能拿到/改到他人数据）→ 立刻降级为 A 类并按 T1 修**。
不要为了让初判成立而硬凑证据。

证明安全后，**从基线文件删除对应条目**。

### T3（P0）C1 `WeComController#callback` 判定

- 实测：用一次性 state + 他人 code 能否绑定到自己账号 / 越权拿他人 token
- 结论二选一：证明是登录入口（豁免 + 审计器白名单）或补归属校验
- 同样要同步基线

### T4（P0）测试与基线收紧

- **每修一条，配一个跨租户 403/404 用例**（Java 侧）。mvn 必须 **> 1378**
- **每处置一条，从 `backend-java/docs/tenant-isolation-baseline.txt` 删除该条**
  （该文件按 `Class#method` **集合**比对，留着已修条目会让将来的回归检测不到）
- 集成测试 `TenantIsolationIntegrationTest` 必须仍通过（当前 17 = 17）

### T5（P1）审计器：是否新增"用户级校验"保护模式

若 T2 证明 B 类确实安全，可考虑让审计器识别这类防护以消除同类误报。
**若做，必须同时配反例**：

> `userId` 来自**请求参数**（非 `user.userId()`）的同形代码，**必须仍被报出**。

这是硬门槛 —— 不配反例就别改规则（PHASE80 已踩过两次"规则放宽洗白漏洞"的坑）。

---

## §3 门禁基线（提交前必须实测，数字写进回报）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1378**（当前基线，新增用例须体现） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `TenantIsolationIntegrationTest` | 通过，且**违规数显著下降**（目标 ≤ 5） |

---

## §4 红线（违反即打回）

1. **严禁"看着像有校验"就判定安全** —— 必须实测跨租户请求，或贴调用链证明 userId 来源
2. **严禁把"userId 来自请求参数"当作安全** —— 那是越权入口，不是防护
3. **严禁放宽校验 / 改基线数字 / 加豁免来掩盖** —— 改根因，不改校验
4. **严禁只改 Controller 不改 Service** —— 校验必须落在真正取数的地方
5. **修完不同步删基线条目** —— 等于让回归检测失效
6. **严禁修改已应用的迁移文件**（`V28`/`V41` 等）—— 变更一律写新迁移 `V51+`
7. **严禁声称"已修"但代码没变** —— PHASE78 出过（回报写修了 `delete`，实际没动）

## §5 交付清单（回报必须逐项给，缺一项视为未完成）

1. 17 条**逐条处置结果表**：`类名#方法名` → 分类（A/B/C）→ 处置（已修/已证明安全）→ **证据行号或 curl 输出**
2. 每个 A 类修复的**跨租户测试用例名**
3. 基线文件 diff（删了哪几条）+ 集成测试最终违规数
4. 门禁五项实测数字（不要写"通过"，写数字）
5. 提交 hash + `git status` 输出（**必须干净**）

## §6 值得注意的两条经验

- **审计器只认 tenantId 模式**，所以"资源归属当前用户"的校验会被误报为越权。
  处置这类误报的正确姿势是**追到 userId 来源**，而不是加一条 tenantId 校验了事。
- **重载是重灾区**：`WikiPageService#markTemplate` 修了一个重载、漏了另一个，
  差点被"已修的那个"洗白。凡有多重载的方法，必须全部处置。

# PHASE79 任务书 — 让审计器基线真正归零（调准规则 + 清后门）

> 背景：租户真越权已基本消化完（第二轮抽样 **0% 真越权**），
> 但基线还剩 **119 条**，而且这 119 条**几乎全是误报**。
>
> 问题在于：**一个每天自动通过、里面全是噪音的门禁，等于什么都没守**。
> 本批把它调准 —— 让基线降到接近 0，这条防线才真正有价值。

---

## §0 现状实测

### 0.1 剩余 119 条的构成

```
WikiController          20
CollectionController     8
ImMessageController      7
ProjectController        5
AutomationRuleController 5
ProjectService           4
ProjectBoardController   4
PlaybookService          4
LdapSyncController       4
ImHuddleController       4
...（其余更少）
```

**绝大多数是 Controller** —— 而 Controller 的典型写法就是拿 `user.tenantId()` 往下传。

### 0.2 典型误报案例（我已定位）

`WikiController#archivePage`（基线里 20 条 WikiController 之一）：

```java
public Map<String, Object> archivePage(
        @PathVariable UUID id,
        @AuthenticationPrincipal AuthenticatedUser user) {
    aclEnforcer.assertCan(user.userId(), user.tenantId(), "wiki_page",
            com.nocobase.auth.AclPolicyEntity.Action.UPDATE);       // ① ACL 校验已带 tenantId
    WikiPageEntity entity = pageService.archive(id, user.userId(), user.tenantId());  // ② 委托时传了 tenantId
    auditService.log(user.tenantId(), user.userId(), ...);          // ③ 也传了
    return Map.of("code", 0, "message", "archived", "data", toPageDto(entity));
}
```

**这个方法明显是安全的**，却被判违规。

### 0.3 误报的技术根因

现有"委托隔离"模式（`TenantIsolationAuditor:70`）：

```java
Pattern.compile("\\b\\w+\\s*\\(\\s*[^)]*\\b(?:tenantId\\w*|\\w+\\.tenantId\\s*\\(\\s*\\))\\s*[,)]")
```

`[^)]*` **不能跨越 `)`**，而实参是 `user.tenantId()` —— 本身带括号。
正则在 `user.tenantId(` 处就断掉了，匹配失败 → 模式形同虚设。

---

## §1 任务

### T1（P0）清理无参 `delete` 后门（1 行）

`backend-java/src/main/java/com/nocobase/auth/UserAdminService.java:92-94`：

```java
public void delete(UUID id) {
    userRepository.deleteById(id);   // 无 tenantId 校验
}
```

实测生产代码无调用者，只有 `UserAdminServiceTest:193` 在用。
但它是危险后门 —— 将来谁顺手调用就是跨租户删用户。

**删除它**，并把测试改用带 tenantId 的重载。

### T2（P0）改进审计器规则，消除误报

目标：把基线从 119 降到**尽量接近 0**（哪怕剩几条真嫌疑也行，但要说清楚每一条是什么）。

**改进方向（基于实测案例，可自行设计更优方案）**：

1. **修好"委托隔离"的匹配** —— 允许实参里出现带括号的表达式
   （`user.tenantId()`、`getTenantId()` 等），不要被 `[^)]*` 卡住。
   建议思路：匹配"调用语句中存在 `tenantId` 相关 token"而不是要求它出现在特定位置。
2. **新增"ACL 校验即视为有防护"** —— `aclEnforcer.assertCan(..., user.tenantId(), ...)`
   这类显式权限校验应被识别为防护（它正是为了做归属判断）。
3. **新增"方法体内使用 `X.tenantId()` 作为调用实参"** —— 覆盖
   `service.xxx(id, user.tenantId())` 的各种参数位置（不只是末尾）。

**要求**：每改一条规则，在回报里说明
「改了什么 → 违规数从 N 降到 M → 举一个因此被正确豁免的方法」。

### T3（P0）反向验证：调宽之后必须仍能抓住真越权 ⭐ 本批最重要

放宽规则最大的风险是**把规则调成"什么都不报"** —— 那样基线是归零了，防线也没了。

所以**必须**保留/补充反向验证：

- 在测试里放**故意不校验租户**的 fixture 方法（已有 `TenantIsolationAuditorTest` 里的反例）
- 断言：审计器**必须把它报出来**
- **再补一个新的反例**：模拟"取到实体后直接返回、全程无 tenantId"
  （即 PHASE76 修掉的 `TicketService#addNote` 原始形态），确认规则调整后**仍能报出**

若规则调整导致反例漏报 → **判定 T2 未实现**（宁可基线剩几十条，也不能漏真越权）。

### T4（P0）门禁与提交

- 五项门禁全绿
- 基线清单同步更新（条数下降）
- 提交推送

---

## §2 范围边界

- 不改生产代码的租户逻辑（本批只动：删 1 个后门方法 + 改审计器测试侧规则）
- 不做看板 Label、灰度发布、钉钉 JS-SDK

---

## §3 门禁

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1364** |
| `cd frontend && npm run test:run` | ≥ 382 |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |
| **基线条数** | **显著低于 119**（目标接近 0，但必须满足 T3） |

---

## §4 红线

1. 🚫 **严禁为降数把规则调成"什么都不报"** —— T3 是硬门槛。
2. 🚫 **严禁靠改基线数字达标**（基线只应下降，且下降必须来自真实的规则改进或修复）。
3. 🚫 **严禁删掉反例测试**来让"反向验证通过"。
4. 🚫 严禁回报与实现不符（PHASE78 教训）。

---

## §5 本项目教训（择要）

1. **"每天都在通过的门禁"如果里面全是噪音，等于没设防** —— 门禁的价值取决于
   它**真的会拦住东西**，而不是它存在。
2. **正则写窄了会让规则形同虚设** —— `[^)]*` 匹配不了带括号的实参，
   导致"委托隔离"模式从未真正生效（本次根因）。
   → 教训：**写完模式要拿真实样例验证它确实匹配**（本项目"反向验证"思想的又一次应用）。
3. **放宽规则必须配反向验证** —— 否则"降数"和"失效"无法区分。
4. **签名有 tenantId ≠ 安全**，要看读写的每个对象（PHASE77）。
5. **回报必须逐项核**（PHASE78 教训）。
6. **判断产物是否存在先 `ls` 真实文件名**，别按猜的名字 grep（PHASE78）。

---

## §6 交付清单

1. T1：无参 `delete` 的删除 diff + 测试改用带 tenantId 重载
2. T2：每条规则改动的说明（改了什么 → 违规数 N→M → 被正确豁免的方法举例）
3. T3：**反向验证输出**（故意留的反例仍被报出；新增的"无 tenantId 直返实体"反例也被报出）
4. 基线清单 diff（条数下降）
5. 五项门禁实测输出
6. 提交推送（`git log --oneline` + `git status` 干净）

---

## §7 一句话总结

**第二轮抽样已经证明剩余 119 条几乎全是误报，而这个门禁每天自动通过 ——
它其实什么都没在守。本批把那条写窄的正则修好、把 ACL 校验识别成防护，
让基线真正降下来；同时必须证明调宽之后它仍能抓住故意留的漏洞 ——
宁可基线剩几十条，也不能让防线失效。**

# PHASE80 任务书 — 消化剩余 27 条（第一批：Playbook 系 + Ticket）

> 背景：PHASE79 把基线从 119 调到 **27**（净减 92 条误报），
> 这 27 条是**真嫌疑**。本批按"一次做透"原则只做其中一批。
>
> 我抽查后又发现 27 条里**仍有少量误报**，所以本批同时处理两类：
> 修确凿的真越权 + 把剩下的误报从规则层消掉。

---

## §0 已核实的证据（CodeBuddy 抽查实测）

### ✅ 确认真越权（本批修）

| 位置 | 代码 | 说明 |
|---|---|---|
| `PlaybookService#update`<br>`playbook/PlaybookService.java:91-92` | `PlaybookEntity p = get(id);` 后直接改字段保存 | 全程无 tenantId，与已修的 `activate` 同构 |
| `TicketService#updateStatus`<br>`ticket/TicketService.java:64-72` | `ticketRepository.findById(id).orElseThrow(...)` | 与已修的 `addNote` **完全同构**，最确凿 |

同批一起修（**同构问题，请确认后一并处理**）：
- `PlaybookService#finishRun`
- `PlaybookService#updateChecklist`
- `PlaybookController#update`（上游传参）

### ❌ 复核为误报（本批改规则消掉，不改代码）

| 位置 | 为什么是误报 |
|---|---|
| `AuthController#login`（`:59-60`） | 认证入口 —— 此时**还没有租户上下文**（用户尚未登录），按 username 查用户是设计使然。PHASE75 抽样也判过它合理 |
| `WebhookSubscriptionController#delete`（`:92-93`） | 它调 `mustGet(id)`，而 `mustGet` 内部（`:99`）用了 `TenantContext.currentTenantId()` —— 隔离在**私有辅助方法**里，审计器没跨方法识别 |

---

## §1 任务

### T1（P0）修复确凿的真越权

范围（5 项）：
1. `TicketService#updateStatus`
2. `PlaybookService#update`
3. `PlaybookService#finishRun`
4. `PlaybookService#updateChecklist`
5. `PlaybookController#update`（上游传参）

修复范式（同 PHASE76/77）：

```java
if (!entity.getTenantId().equals(tenantId)) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
}
```

要求：
- 取到实体后**立即**比对，再执行业务
- 签名缺 `tenantId` → 从**上游调用链传入**（Controller 有 `user.tenantId()`）
- **每项至少 1 条跨租户 403 断言**（用另一租户身份，不是只测正常路径）
- 若发现某项其实**已安全**（如内部已委托带 tenantId 的方法），
  **不要改它**，在回报里标注并说明证据 —— 避免为了凑数改坏正确代码

### T2（P0）把剩下两类误报从规则层消掉

**T2.1 认证入口豁免**

`AuthController#login` / `#refresh` 这类方法**签名里没有任何租户上下文来源**
（既无 `AuthenticatedUser` 参数、也无 `tenantId` 参数、方法体也无 `TenantContext` 调用）。

建议规则（可自行设计更优）：
> 若方法**拿不到**租户上下文（上述三者都没有），则**不判违规** ——
> 因为它物理上无法做归属校验，审计器不该要求做不到的事。

⚠️ 这条会显著减少误报，但也可能放过真漏洞 —— 所以**必须配合 T3 反向验证**。

**T2.2 跨方法的"委托隔离"**

`WebhookSubscriptionController#delete` 调私有方法 `mustGet(id)`，
而 `mustGet` 内部用了 `TenantContext.currentTenantId()`。

建议：审计器在判断"是否有防护"时，**把被调用的同类私有方法体也纳入检查**
（至少支持"本类内的方法调用"这一种）。

### T3（P0）反向验证（硬门槛）

每次放宽规则都必须重新证明防线仍有效：

- 现有反例（`T3_newAntiExample_noTenantIdDirectReturn` 等）**必须仍通过**
- **新增 1 个反例**：方法签名里**有** `AuthenticatedUser user` 参数、
  取实体后直接返回、**全程不用 `user.tenantId()`** —— 必须被报出
  （这条专门防 T2.1 的误伤：有上下文却不用 ≠ 没上下文）

若放宽后反例漏报 → **判 T2 未实现**。

### T4（P0）门禁与提交

- 五项门禁全绿
- 基线清单同步（条数下降）
- 提交推送

---

## §2 范围边界

- **不修**剩余 27 条里本批未列出的项（留给 PHASE81）
- 不改租户隔离方案本身
- 不做看板 Label、灰度发布、钉钉 JS-SDK

---

## §3 门禁

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1367** |
| `cd frontend && npm run test:run` | ≥ 382 |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |
| **基线条数** | **< 27** |

---

## §4 红线

1. 🚫 **严禁照单全修未经核实的清单**（本批已给出复核结论，未列出的项不动）。
2. 🚫 **严禁"加了校验但没测试"** —— 每项跨租户 403 断言。
3. 🚫 **严禁为降数把规则调成"什么都不报"** —— T3 是硬门槛。
4. 🚫 **严禁靠改基线数字达标**（下降必须来自真实修复或规则改进）。
5. 🚫 严禁回报与实现不符（PHASE78 教训）。

---

## §5 本项目教训（择要）

1. **规则调准是迭代过程** —— PHASE79 从 119→27，但仍有误报（login、
   跨方法委托）。每轮放宽都要配反向验证。
2. **"拿不到租户上下文"的方法不该判违规** —— 但"有上下文却不用"必须判。
3. **签名有 tenantId ≠ 安全**，要看读写的每个对象（PHASE77）。
4. **正则写窄会让规则失效**（PHASE79 根因：`[^)]*` 匹配不了带括号实参）。
5. **回报必须逐项核**（PHASE78）。
6. **判断产物是否存在先 `ls` 真实文件名**（PHASE78）。

---

## §6 交付清单

1. 修复项的 diff（文件 + 行号）+ **跨租户 403 断言测试输出**
2. 若发现某项已安全未改 → 列出并给证据
3. T2 两条规则改动的说明（改了什么 → 违规数变化 → 被正确豁免的方法）
4. T3 反向验证输出（含新增的"有 user 参数却不用 tenantId"反例）
5. 基线清单 diff（< 27）
6. 五项门禁输出 + 提交推送

---

## §7 一句话总结

**27 条里我抽查发现仍有误报（登录入口本来就没有租户上下文、Webhook 的隔离藏在
私有方法里）。本批修掉最确凿的 5 项（Ticket 与 Playbook 系），把这两类误报从规则层
消掉，并且必须证明：放宽之后，那种"手里拿着 user 却不用 tenantId"的漏洞依然能被抓住。**

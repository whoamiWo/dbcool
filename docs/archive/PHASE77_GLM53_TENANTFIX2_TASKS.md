# PHASE77 任务书 — 先纠偏清单，再消化真越权

> **本批的触发原因**：PHASE76 的 T3 筛选清单（`backend-java/docs/tenant-isolation-real-violations.md`）
> 列出了"高风险 5 项"。我逐项复核代码后发现 —— **5 项里只有 2 项是真越权，准确率 40%**。
> 若照单全修，会有 3 处**改坏本已正确的代码**（甚至可能引入回归）。
>
> 所以本批**第一件事是纠偏**：把 13 项清单全部复核一遍，再修真正的问题。

---

## §0 清单复核结果（CodeBuddy 逐项读代码实测）

`backend-java/docs/tenant-isolation-real-violations.md` 的高风险 5 项：

| 清单项 | 清单理由 | **我的复核** | 代码证据 |
|---|---|---|---|
| `AgentService#executeInChannel` | "AI 对话记录无租户校验" | ❌ **误报** | 签名 `:75` **有 `String tenantId` 参数**，`:83 findAgent(tenantId, channelId)` 用其查询 |
| `ProjectService#update` | "项目更新无租户归属校验" | ❌ **误报** | `:112` **有 `String tenantId` 参数**，`:113 get(id, tenantId)` 内部已校验 |
| `WikiPageService#restoreVersion` | "Wiki 恢复无租户归属校验" | ❌ **误报** | `:229` **已有** `page.getTenantId().equals(tenantId)` → FORBIDDEN |
| `WikiPageService#createFromTemplate` | "模板创建无租户归属校验" | ✅ **真越权** | `:298 WikiPageEntity tpl = get(templateId);` —— **未校验模板归属**，可传他租户 templateId 读其内容 |
| `WikiPageService#softDelete` | "软删除无租户归属校验" | ✅ **真越权** | `:305 get(id)` 后直接改状态保存，全程无归属校验 |

**结论**：清单是按「方法内出现 `findById(`/`get(id)`」这个**文本特征**筛的，
但没有同时检查「方法签名是否已有 tenantId 参数 / 内部委托是否已校验」，
所以把大量**已经安全**的方法也筛进来了。

> 我自己在 PHASE76 审计时只抽查了 `softDelete` 一项（恰好是真越权），
> 没有逐项抽查 —— 这是我的疏漏，本批一并纠正。

---

## §1 任务

### T1（P0）复核全部 13 项清单，逐项给结论

对 `backend-java/docs/tenant-isolation-real-violations.md` 里的
**5 高 + 5 中 + 3 低 = 13 项**逐项读代码，判定二选一：

- **真越权**：能构造出跨租户读写路径（说清楚怎么构造）
- **误报**：已有隔离 —— 必须指出**具体是哪一行**提供了隔离
  （签名里的 tenantId / 内部委托的 `get(id, tenantId)` / 查询条件含 tenant_id）

**判定方法（必须同时看三处，缺一不可）**：

1. 方法**签名**是否已有 `tenantId` 参数
2. 方法**内部**是否有归属比对，或是否委托给了"带 tenantId 的 get"
3. 方法**调用链上游**是否已校验

产出：更新该文档，每项标注 `[复核] 真越权 / 误报 + 证据行号 + 复核人日期`。

### T2（P0）修复核确认的真越权，每项补 403 断言

**已知确凿的 2 项**（我复核过，可直接动手）：

1. `WikiPageService#softDelete`（`:304`）—— `get(id)` 无校验
2. `WikiPageService#createFromTemplate`（`:296`）—— `get(templateId)` 未校验模板归属

修复范式（同 PHASE76）：

```java
if (!entity.getTenantId().equals(tenantId)) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
}
```

要求：
- 每项**至少 1 条跨租户 403 断言测试**（用另一租户身份访问）
- 签名缺 `tenantId` 就从**上游调用链传入**，不要硬编码
- `createFromTemplate` 注意：模板本身可能允许跨 KB 复用 —— 若业务上允许，
  需在回报里说明并改为校验 **kbId 的归属**（而不是模板归属）

### T3（P1）把复核经验固化进审计规则（防清单再失真）

当前审计器判定"缺归属校验"时，似乎没有把「**方法签名已含 tenantId 参数**」
作为豁免条件，导致 `ProjectService#update`、`AgentService#executeInChannel`
这类**签名已有 tenantId** 的方法被误报。

请评估并改进审计规则（改完在回报里说明改了哪条、违规数变化多少），
目标是让清单的**准确率显著提升**（而不只是数量变化）。

### T4（P0）基线更新与门禁

- 修复项从基线清单移除（基线只应下降）
- 复核为误报的：改**规则**让它不再报（不是改基线数字）
- 门禁全绿后提交推送

---

## §2 范围边界（明确不做）

- **不改已被复核为误报的方法**（`AgentService#executeInChannel`、
  `ProjectService#update`、`WikiPageService#restoreVersion` 保持原样）
- 不修中风险/低风险项（T1 只复核并标注，留给下一批）
- 不做看板 Label、灰度发布、钉钉 JS-SDK

---

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1355**（新增测试） |
| `cd frontend && npm run test:run` | ≥ 382（不改前端，持平即可） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |
| **基线清单条数** | **< 128**（PHASE76 后为 128） |

---

## §4 红线（沿用 + 本批强调）

既有红线全部有效。本批特别强调：

1. 🚫 **严禁照单全修未经复核的清单** —— T1 未完成前不得动 T2。
   （本次 5 项清单有 3 项误报，照修就是改坏正确代码。）
2. 🚫 **严禁"加了校验但没测试"** —— 每项必须有跨租户 403 断言。
3. 🚫 **严禁靠改基线让门禁通过**（基线只应下降）。
4. 🚫 **严禁把误报标成真越权来凑修复数量**。

---

## §5 本项目教训（择要）

1. **文本特征筛选会大量误报** —— "方法内出现 `findById(`"不等于"没有租户校验"，
   必须**同时看签名 + 内部 + 上游**（本次准确率仅 40%）。
2. **审计/抽样结论必须逐项复核后才能作为施工依据** —— 我抽查 1 项就放行是疏漏。
3. **执行方可以推翻前一轮的判定**（PHASE76 对 RowAclService 的做法是对的）——
   判断优先于照搬，前提是**给证据**。
4. **基线/阈值机制必须存 key 集合**（PHASE75 R3）。
5. **"能力存在"≠"每次都用对"**（PHASE74）。
6. **验收第一步跑 `tsc`**（几秒）。

---

## §6 交付清单

1. **更新后的 `tenant-isolation-real-violations.md`**：13 项全部标注
   `[复核] 真越权/误报 + 证据行号`
2. 修复项的代码 diff + **跨租户 403 断言测试输出**
3. T3 审计规则改动说明（改了哪条、违规数变化）
4. 基线清单 diff（证明 < 128）
5. 五项门禁实测输出 + 提交推送

---

## §7 一句话总结

**上一批筛出来的"高风险 5 项"，我逐项读代码后发现 3 项是误报 —— 它们签名里就有
tenantId、内部也已经校验过了。照单全修等于把正确的代码改坏。**
本批先把 13 项清单全部复核标真/伪，再只修真正的那几处（已知至少 2 项），
并把"签名已有 tenantId 应豁免"这条经验固化进审计规则，让清单以后别再失真。

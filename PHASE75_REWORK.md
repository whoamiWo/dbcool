# PHASE75 返工单（项目经理版）

> 技术工作质量**是本批最好的一次**（抽样表尤其出色），只剩两件事。
> 但第 2 件是**原则问题**，必须纠正。

---

## §0 审计结论

### ✅ 已通过的部分（不要动）

| 项 | 证据 |
|---|---|
| 任务 1 抽样 | `docs/tenant-isolation-audit-sample.md` 20 行逐条判定，**每行都有代码证据（行号+片段）**；真越权 4/20、误报 16/20；给了规则改进方案与修复优先级 |
| 前端 Checklist | `BoardColumn.tsx:104-106` 渲染、`:78-79` 进度 `3/5`、勾选走 `PUT /checklist-items/{itemId}` 落库 |
| 前端 dueDate | `:54 formatDueDate`、`:61 isOverdue`、`:146 color={isOverdue ? 'error' : 'default'}` 逾期高亮 |
| 后端加固 | `ProjectBoardController:211/227/293/310` 四处 tenant 校验 → 403 |
| 审计器改进 | 新增 `user.tenantId().equals(` 与 `tenantId().equals(X.getTenantId)` 两条识别模式 |
| 门禁 | tsc **0** ✅、mvn **1348**（+11）✅ |

### ❌ 返工项 1：前端零测试（vitest 未增长）

```
实测 vitest = 377，要求 > 377
```

前端是本批**主体改动**（`BoardColumn.tsx` +76 行、`api.ts` +27 行），却**没有新增任何前端测试**。

`frontend/src/features/project/BoardView.test.tsx` 已有 6 个用例 —— **有可测先例，不是不可测**。

### ❌ 返工项 2（原则问题）：靠上调基线让门禁通过

```
基线文件实测：136   （提交前：134）
```

**本批新代码引入了 2 处租户校验缺失，而处置方式是把基线从 134 上调到 136，
让"禁新增"检查通过。**

回报写的是「基线从 134 更新为 132」—— 与实际相反（是**升高 2**，不是降低 2）。

这属于「**为跑通而放宽验收标准**」，与 PHASE69 的 `validate-on-migrate: false`
是同一类红线（"严禁禁用校验绕过问题"）。

**正确做法**：修那 2 处新引入的违规，基线保持或回落 —— **不是上调基线**。

---

## §1 返工任务

### R1（P0）补前端测试 —— vitest 必须 > 377

至少 3 条，覆盖本批前端改动：

1. **Checklist 渲染**：有 checklists 时渲染条目 + 进度（`3/5` 类文案）
2. **逾期高亮**：dueDate 为过去日期 → 渲染为 error/红色态；未来日期 → 非 error
3. **无 dueDate 时不渲染占位**（"有值才渲染"这条规则）

要求：
- 放在 `frontend/src/features/project/`（与 `BoardView.test.tsx` 同级）
- 可 mock 数据，但**必须断言真实渲染结果**（不是只测 `formatDueDate`/`isOverdue`
  这两个纯函数就交差 —— 那测不到"有没有渲染"）
- 若组件依赖过重难以整体渲染，至少断言 `isOverdue` 与渲染条件的组合行为，
  并在回报里说明为什么没做整组件渲染

### R2（P0）修掉那 2 处新引入的违规，基线回落

1. 找出本批新增的 2 处租户校验缺失（对比 134 → 136 的差异）
2. **补上归属校验**（照 `ProjectBoardController:211` 已有写法：`user.tenantId().equals(x.getTenantId())` → 403）
3. 基线文件 **降回 ≤ 134**（不得大于提交前的 134）
4. 若确认某处确实是规则误报而非真缺失 → 改**规则**（让审计器不再报它），
   而不是改基线数字；并在回报里说明改了哪条规则

### R3（P1）基线机制加固（防再次被滥用）

当前基线只存一个数字，**改大就能让新违规合法化**。改为：

- 基线文件存**违规清单**（`类名#方法名` 列表），而不是只存数量
- 检查逻辑改为：比对**集合**，新增的方法名 → 失败；消失的方法名 → 提示可收紧基线
- 数量可作为辅助信息打印，但**不作为唯一判据**

（若实现成本过高，至少做到：基线文件在数字旁附带清单，人工可核对。）

---

## §2 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd frontend && npm run test:run` | **> 377**（本批关键） |
| `cd backend-java && mvn -o test` | ≥ 1348 |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |
| **基线文件** | **≤ 134**（不得高于提交前） |

---

## §3 红线（本批特别强调）

既有红线全部有效。特别强调：

1. 🚫 **严禁靠上调基线/放宽阈值让门禁通过**。判据：基线数字**只应下降**。
   发现上升 → 必问"是不是新代码引入了违规却在调基线"。
2. 🚫 **严禁只测纯函数冒充组件测试** —— 要断言**渲染结果**。
3. 🚫 **严禁回报数字与实际不符**（本次"132" vs 实际"136"）。
   数字必须来自实测输出，不得凭印象写。

---

## §4 提示词（整段复制投喂）

```
# PHASE75 返工：补前端测试 + 修 2 处新引入违规（基线必须回落）

仓库：/home/who/multistack-project（工作目录即仓库根）

环境速查：
  tsc:        cd frontend && npx tsc --noEmit     （先跑，几秒）
  前端测试:   cd frontend && npm run test:run
  后端测试:   cd backend-java && mvn -o test      （离线）
  E2E:        cd frontend && npx playwright test
  pytest:     cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider
  登录:       curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' → data.access_token
  多行提交:   必须 git commit -F - <<'EOF' … EOF（不要用 -m "...\n..."）

## 已通过的部分（不要动）
- docs/tenant-isolation-audit-sample.md 的 20 处抽样（逐条有证据，很好）
- BoardColumn.tsx:104-106 checklist 渲染 / :78-79 进度 / :54 formatDueDate /
  :61 isOverdue / :146 逾期高亮
- ProjectBoardController:211/227/293/310 的 tenant 校验
- 审计器新增的 2 条识别模式

## R1（P0）补前端测试 —— vitest 必须 > 377
实测 vitest = 377（要求 >377），因为前端是本批主体改动却零测试。
至少 3 条，放 frontend/src/features/project/：
  1. checklists 有值时渲染条目 + 进度（如 3/5）
  2. dueDate 为过去 → 渲染 error/红色态；未来 → 非 error
  3. 无 dueDate 时不渲染占位
要求断言**真实渲染结果**；只测 formatDueDate / isOverdue 两个纯函数不算
（那测不到"有没有渲染"）。同目录 BoardView.test.tsx 有 6 个用例可作参照。

## R2（P0）修掉本批新引入的 2 处违规，基线回落
基线实测 136（提交前 134）→ 本批新代码引入了 2 处租户校验缺失，
而处置方式是上调基线到 136 让"禁新增"通过 —— 这属于放宽验收标准（红线）。
  1. 找出新增的 2 处（对比 134 → 136 差异）
  2. 补归属校验（照 ProjectBoardController:211 写法：
     user.tenantId().equals(x.getTenantId()) → 403）
  3. 基线文件降回 ≤ 134
  4. 若某处确是规则误报 → 改规则（让审计器不再报），不改基线数字

## R3（P1）基线机制加固
基线只存一个数字 → 改大就能让新违规合法化。改为存**违规清单（类名#方法名）**，
按集合比对（新增即失败），数量仅作辅助打印。

## 门禁（全部满足并贴输出）
  npm run test:run   > 377
  mvn -o test        ≥ 1348
  tsc --noEmit       0
  playwright         ≥ 66
  pytest             48 passed, 1 skipped
  基线文件           ≤ 134

## 红线
- 严禁靠上调基线/放宽阈值让门禁通过（基线只应下降）
- 严禁只测纯函数冒充组件测试（要断言渲染结果）
- 严禁回报数字与实际不符（数字必须来自实测输出）

## 回报清单
1. 新增前端测试用例名 + 通过输出（vitest 总数）
2. 修复的 2 处违规（文件+行号+补丁）+ 基线文件 diff（证明 ≤134）
3. R3 若做了：基线格式变更说明
4. 五项门禁实测输出
5. 提交并推送（git log --oneline + git status 干净）
```

# PHASE77 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE77：先纠偏清单，再消化真越权

> **为什么本批第一件事是纠偏**：你上一批产出的筛选清单
> （`backend-java/docs/tenant-isolation-real-violations.md`）列了"高风险 5 项"。
> 我逐项读代码复核后发现 —— **5 项里只有 2 项是真越权，准确率 40%**。
> 若照单全修，会有 3 处**改坏本已正确的代码**。

## §0 工作目录与环境速查

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 工作目录即仓库根目录

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**，不能下载新依赖） |
| 类型检查 | `cd frontend && npx tsc --noEmit`（几秒，验收第一步就跑） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| **多行提交信息** | **必须** `git commit -F - <<'EOF' … EOF`；不要用 `-m "...\n..."`（bash 不解释 `\n`，`&&` 会被命令替换吃掉） |

⚠️ 租户相关文档在 **`backend-java/docs/`**（不是根目录 `docs/`）。

---

## §1 我已复核的结果（5 项高风险，只有 2 项是真越权）

| 清单项 | 我的复核 | 证据 |
|---|---|---|
| `AgentService#executeInChannel` | ❌ **误报** | 签名 `:75` 已有 `String tenantId` 参数；`:83 findAgent(tenantId, channelId)` 用它查询 |
| `ProjectService#update` | ❌ **误报** | `:112` 已有 `String tenantId` 参数；`:113 get(id, tenantId)` 内部已校验 |
| `WikiPageService#restoreVersion` | ❌ **误报** | `:229` 已有 `page.getTenantId().equals(tenantId)` → FORBIDDEN |
| `WikiPageService#createFromTemplate` | ✅ **真越权** | `:298 get(templateId)` 未校验模板归属，可传他租户 templateId 读其内容 |
| `WikiPageService#softDelete` | ✅ **真越权** | `:305 get(id)` 后直接改状态保存，全程无归属校验 |

**失真原因**：清单是按「方法内出现 `findById(`/`get(id)`」这个**文本特征**筛的，
但没有同时检查「方法签名是否已有 tenantId 参数 / 内部委托是否已校验 / 上游是否已校验」。

---

## §2 任务

### T1（P0）复核全部 13 项清单，逐项给结论（先做完这个再动 T2）

对文档里的 **5 高 + 5 中 + 3 低 = 13 项**逐项读代码，判定二选一：

- **真越权**：能构造出跨租户读写路径（说清楚怎么构造）
- **误报**：已有隔离 —— 必须指出**具体哪一行**提供了隔离

**判定必须同时看三处（缺一不可）**：

1. 方法**签名**是否已有 `tenantId` 参数
2. 方法**内部**是否有归属比对，或是否委托给了"带 tenantId 的 get"
3. 方法**调用链上游**是否已校验

产出：更新 `backend-java/docs/tenant-isolation-real-violations.md`，
每项标注 `[复核] 真越权/误报 + 证据行号 + 复核日期`。

### T2（P0）修复核确认的真越权，每项补 403 断言

**已知确凿的 2 项**（我已复核，可直接动手）：

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
- `createFromTemplate` 注意：若业务上允许跨 KB 复用模板，
  改为校验 **kbId 的归属**（而不是模板归属），并在回报里说明

### T3（P1）把复核经验固化进审计规则

审计器判定"缺归属校验"时，似乎没把「**方法签名已含 tenantId 参数**」作为豁免条件，
导致 `ProjectService#update`、`AgentService#executeInChannel` 这类被误报。

请评估并改进规则（回报里说明改了哪条、违规数变化多少），
目标是让清单**准确率显著提升**（而不只是数量变化）。

### T4（P0）基线更新与门禁

- 修复项从基线清单移除（基线只应下降）
- 复核为误报的：改**规则**让它不再报（不是改基线数字）
- 门禁全绿后提交推送

---

## §3 范围边界（明确不做）

- **不改已被复核为误报的方法**（`AgentService#executeInChannel`、
  `ProjectService#update`、`WikiPageService#restoreVersion` 保持原样）
- 不修中风险/低风险项（T1 只复核标注，留给下一批）
- 不做看板 Label、灰度发布、钉钉 JS-SDK

---

## §4 门禁基线（全部满足并贴输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1355** |
| `cd frontend && npm run test:run` | ≥ 382 |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |
| **基线清单条数** | **< 128** |

---

## §5 红线

既有红线全部有效。本批特别强调：

1. 🚫 **严禁照单全修未经复核的清单** —— T1 未完成前不得动 T2
   （本次 5 项有 3 项误报，照修就是改坏正确代码）。
2. 🚫 **严禁"加了校验但没测试"** —— 每项必须有跨租户 403 断言。
3. 🚫 **严禁靠改基线让门禁通过**（基线只应下降）。
4. 🚫 **严禁把误报标成真越权来凑修复数量**。

---

## §6 本项目教训（择要）

1. **文本特征筛选会大量误报** —— "方法内出现 `findById(`"不等于"没有租户校验"，
   必须同时看**签名 + 内部 + 上游**（本次准确率仅 40%）。
2. **审计/抽样结论必须逐项复核后才能作为施工依据**。
3. **执行方可以推翻前一轮判定**（PHASE76 对 RowAclService 是对的）——
   判断优先于照搬，前提是**给证据**。
4. **基线/阈值机制必须存 key 集合**（PHASE75 R3 + 反向验证）。
5. **"能力存在"≠"每次都用对"**（PHASE74）。
6. **验收第一步跑 `tsc`**（几秒）。
7. **MUI v9 `Drawer` 无 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
8. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效，`{id}` 才生效。

---

## §7 回报清单（缺项会被打回）

1. **更新后的 `backend-java/docs/tenant-isolation-real-violations.md`**：
   13 项全部标注 `[复核] 真越权/误报 + 证据行号`
2. 修复项的代码 diff + **跨租户 403 断言测试输出**
3. T3 审计规则改动说明（改了哪条、违规数变化）
4. 基线清单 diff（证明 < 128）
5. 五项门禁实测输出
6. 提交推送（`git log --oneline` + `git status` 干净）
7. 未做项说明（中/低风险明确写"本批范围外"）

-----END PROMPT-----

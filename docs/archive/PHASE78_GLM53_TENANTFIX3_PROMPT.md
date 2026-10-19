# PHASE78 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE78：第二轮抽样消化（基线 123 → ?）

> **主题为什么改了**：原计划"消化中风险 5 项"，但我复核后发现**那 13 项已经全部处理完**：
> - 中风险 4 项**误报**（`updateRule`/`toggleRule`/`deleteRule` 都走 `getRule(ruleId, tenantId)`
>   → `findByIdAndTenantId`；`FormController` create/update 已传 `user.tenantId()`）
> - `executeRule` **已修**（`:174 getRule(ruleId, tenantId)`）
> - 低风险 `restore`/`share`/`unshare` 已随 PHASE77 修复
>
> 所以本批转向：**用纠偏后的审计器，重新面对剩余 123 条基线**。

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

## §1 当前状态（实测）

- 基线违规数：**123**（132 → 128 → 123）
- 已修真越权：PHASE76 修 4 处、PHASE77 修 6 处
- 审计规则：PHASE77 已新增"委托隔离"模式（调用时传 tenantId 即视为有防护）
- **未知**：剩余 123 条里还有多少真越权 —— 本批要回答这个

## §2 任务

### T1（P0）第二轮抽样：从 123 条抽 20 处，逐处判定

按包分层（Controller 10 + Service 10），**不要只挑眼熟的**。

判定用**三步法 + 对象级判据**：

1. 方法签名是否已有 `tenantId`
2. 内部是否有归属比对，或委托给"带 tenantId 的 get"
3. 上游是否已校验
4. **（关键）这个方法读写的每一个对象，是否都做了归属校验？**
   —— 签名有 tenantId ≠ 安全（PHASE77 的 `createFromTemplate` 就是反例：
   tenantId 只保护目标页，模板来源没校验）

产出：更新 `backend-java/docs/tenant-isolation-audit-sample.md`（第二轮），
每行 = 文件 / 方法 / 判定 / **证据行号 + 代码片段** / 攻击路径（真越权时）。

**统计真越权比例**，并与第一轮（20%）对比，说明规则改进是否让清单更准。

### T2（P0）修复确认的真越权

- 逐项补归属校验（范式：`getTenantId().equals(tenantId)` → FORBIDDEN）
- **每项至少 1 条跨租户 403 断言**（用另一租户身份，不是只测正常路径）
- 若某方法业务上允许跨租户访问某对象（如共享模板），
  改为校验**真正需要保护的对象**，并在回报里说明

### T3（P1）修正文档瑕疵 + 路径统一

- `backend-java/docs/tenant-isolation-real-violations.md` 第 8 项 `deleteRule`
  的日期写成 `2026-06-24`（应为 `2026-10-06`）—— 顺手改掉
- 租户文档散在 `backend-java/docs/`，请在审计器注释里标明**绝对路径**，
  避免下次搜错目录

### T4（P0）基线更新与门禁

- 修复项从基线移除（基线只应下降）
- 复核为误报的：改**规则**，不是改基线数字
- 门禁全绿后提交推送

## §3 范围边界（明确不做）

- 不修低风险/风险可选项（清单已标注）
- 不动已复核为误报的方法
- 不做看板 Label、灰度发布、钉钉 JS-SDK

## §4 门禁基线（全部满足并贴输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1360** |
| `cd frontend && npm run test:run` | ≥ 382 |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |
| **基线清单条数** | **< 123** |

## §5 红线

既有红线全部有效。本批特别强调：

1. 🚫 **严禁照单全修未经复核的清单**（T1 未完成前不得动 T2）。
2. 🚫 **严禁"加了校验但没测试"** —— 每项跨租户 403 断言。
3. 🚫 **严禁靠改基线让门禁通过**（基线只应下降）。
4. 🚫 **严禁把"签名有 tenantId"当作安全结论** —— 必须逐个对象确认。

## §6 本项目教训（择要）

1. **签名有 tenantId ≠ 安全** —— 要问"读写的**每个对象**是否都校验了"
   （PHASE77 `createFromTemplate` 反例）。
2. **文本特征筛选会大量误报**，必须同时看签名 + 内部 + 上游。
3. **分析类产物要逐项复核后才能施工**（PHASE77"先纠偏再修"有效）。
4. **基线/阈值机制必须存 key 集合**（PHASE75 R3 + 反向验证）。
5. **验收第一步跑 `tsc`**（几秒）。
6. **抽样时特意挑"看起来不像"的项**，别只挑顺眼的。
7. **MUI v9 `Drawer` 无 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
8. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效。

## §7 回报清单（缺项会被打回）

1. 第二轮抽样表（20 处，每行证据行号 + 片段；真越权附攻击路径）
2. 真越权比例统计 + 与第一轮（20%）的对比说明
3. 修复项 diff + **跨租户 403 断言测试输出**
4. T3 文档修正 diff
5. 基线清单 diff（证明 < 123）
6. 五项门禁实测输出
7. 提交推送（`git log --oneline` + `git status` 干净）

-----END PROMPT-----

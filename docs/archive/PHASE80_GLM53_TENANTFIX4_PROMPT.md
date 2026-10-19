# PHASE80 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE80：消化剩余 27 条（第一批：Playbook 系 + Ticket）

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

## §1 背景与已核实证据（不用再调研）

PHASE79 把基线从 119 调到 **27**。我抽查后发现 27 条里**仍有少量误报**，
所以本批同时处理两类。

### ✅ 确认真越权（本批修）

| 位置 | 证据 |
|---|---|
| `PlaybookService#update`<br>`playbook/PlaybookService.java:91-92` | `PlaybookEntity p = get(id);` 后直接改字段保存，全程无 tenantId |
| `TicketService#updateStatus`<br>`ticket/TicketService.java:64-72` | `ticketRepository.findById(id).orElseThrow(...)`，与已修的 `addNote` 完全同构，最确凿 |

同构问题一并处理（请确认后修）：
- `PlaybookService#finishRun`
- `PlaybookService#updateChecklist`
- `PlaybookController#update`（上游传参）

### ❌ 复核为误报（本批改规则消掉，不改代码）

| 位置 | 为什么是误报 |
|---|---|
| `AuthController#login`（`:59-60`）/ `#refresh` | 认证入口，此时**还没有租户上下文**（用户尚未登录），按 username 查用户是设计使然 |
| `WebhookSubscriptionController#delete`（`:92-93`） | 它调私有方法 `mustGet(id)`，而 `mustGet` 内部（`:99`）用了 `TenantContext.currentTenantId()` —— 隔离在**私有辅助方法**里，审计器没跨方法识别 |

## §2 任务

### T1（P0）修复确凿的真越权（5 项）

1. `TicketService#updateStatus`
2. `PlaybookService#update`
3. `PlaybookService#finishRun`
4. `PlaybookService#updateChecklist`
5. `PlaybookController#update`（上游传参）

范式（同 PHASE76/77）：

```java
if (!entity.getTenantId().equals(tenantId)) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
}
```

要求：
- 取到实体后**立即**比对，再执行业务
- 签名缺 tenantId → 从**上游调用链传入**（Controller 有 `user.tenantId()`）
- **每项至少 1 条跨租户 403 断言**（用另一租户身份，不是只测正常路径）
- 若某项其实**已安全**（如内部已委托带 tenantId 的方法），**不要改它**，
  在回报里标注并给证据 —— 避免为凑数改坏正确代码

### T2（P0）把两类误报从规则层消掉

**T2.1 认证入口豁免**：`AuthController#login` / `#refresh` 这类方法**签名里没有任何
租户上下文来源**（无 `AuthenticatedUser` 参数、无 `tenantId` 参数、方法体也无
`TenantContext` 调用）。
建议规则：若方法**拿不到**租户上下文，则**不判违规**（物理上做不了归属校验，
审计器不该要求做不到的事）。⚠️ 这条可能放过真漏洞 → **必须配合 T3**。

**T2.2 跨方法委托隔离**：判断"是否有防护"时，把**被调用的同类私有方法体**
也纳入检查（至少支持"本类内方法调用"这一种）。

### T3（P0）反向验证（硬门槛）

每次放宽规则都要重新证明防线仍有效：

- 现有反例（`T3_newAntiExample_noTenantIdDirectReturn` 等）**必须仍通过**
- **新增 1 个反例**：方法签名里**有** `AuthenticatedUser user` 参数、
  取实体后直接返回、**全程不用 `user.tenantId()`** —— 必须被报出
  （这条专门防 T2.1 的误伤：**有上下文却不用 ≠ 没上下文**）

放宽后反例漏报 → **判 T2 未实现**。

### T4（P0）门禁与提交

- 五项门禁全绿、基线清单同步、提交推送

## §3 范围边界

- **不修**本批未列出的剩余项（留给下一批）
- 不改租户隔离方案本身
- 不做看板 Label、灰度发布、钉钉 JS-SDK

## §4 门禁（全部满足并贴输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1367** |
| `cd frontend && npm run test:run` | ≥ 382 |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |
| **基线条数** | **< 27** |

## §5 红线

1. 🚫 **严禁照单全修未经核实的清单**（本批已给复核结论，未列出的不动）。
2. 🚫 **严禁"加了校验但没测试"** —— 每项跨租户 403 断言。
3. 🚫 **严禁为降数把规则调成"什么都不报"** —— T3 是硬门槛。
4. 🚫 **严禁靠改基线数字达标**。
5. 🚫 严禁回报与实现不符（PHASE78 教训）。

## §6 本项目教训（择要）

1. **规则调准是迭代过程** —— 119→27 后仍有误报，每轮放宽都要配反向验证。
2. **"拿不到租户上下文"的方法不该判违规**，但**"有上下文却不用"必须判**。
3. **签名有 tenantId ≠ 安全**，要看读写的每个对象（PHASE77）。
4. **正则写窄会让规则失效**（PHASE79 根因：`[^)]*` 匹配不了带括号实参）。
5. **回报必须逐项核**（PHASE78）。
6. **判断产物是否存在先 `ls` 真实文件名**（PHASE78）。

## §7 回报清单（缺项会被打回）

1. 修复项 diff（文件 + 行号）+ **跨租户 403 断言测试输出**
2. 若发现某项已安全未改 → 列出并给证据
3. T2 两条规则改动说明（改了什么 → 违规数变化 → 被正确豁免的方法）
4. T3 反向验证输出（含新增的"有 user 参数却不用 tenantId"反例）
5. 基线清单 diff（< 27）
6. 五项门禁输出
7. 提交推送（`git log --oneline` + `git status` 干净）

-----END PROMPT-----

# PHASE97 任务书：前后端契约修复 + 真契约测试（🔒-SaaS-P0）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §3.4（P0-4）
> 现状一句话：**告警中心、看板、集成页三个模块在真实后端上"看着有按钮、点了没反应"**，
> 而契约测试是空心的，还把 bug 固化成了"通过"。

---

## §1 为什么做这个（三条理由）

### 1.1 契约漂移 = 功能不存在

`frontend/src/api/client.ts:8` 的 baseURL 已经是 `/api`，而 `api/integrations.ts`、`features/project/BoardView.tsx` 里又写了一遍 `/api` → 实际请求 `/api/api/...`。
`pages/AlertCenter.tsx` 整页调 `/ai/alerts/*`，后端是 `/api/alerts/*` → 全页 404。

对用户来说，这和"没做这个功能"没有区别。

### 1.2 契约测试是空心的，反而固化了 bug

- `frontend/src/api/endpoints.contract.test.ts:12-63` 名为"与后端 @RequestMapping 契约"，实际**只断言常量等于自身字面量**，没有做任何后端比对；`api/endpoints.ts:12-48` 的常量表仅被该测试引用，**生产代码零引用**
- `features/project/BoardView.test.tsx:39-40` 断言被调用的是**带双前缀的错误路径** → 把断链 bug 固化成"通过"

**这是最危险的一类测试：它不仅没发现问题，还让问题无法被发现。**

### 1.3 门禁绿 ≠ 功能可用，必须先解决这个才能谈"好用"

362+ 前端测试全绿，但告警中心整页打不通。PHASE98（错误态）只有在契约修好之后才有意义 —— 否则只是把 404 显示得更漂亮。

## §2 现状（实测）

### 2.1 URL 双前缀

| 位置 | 问题 |
|---|---|
| `features/project/BoardView.tsx:54,55,91,117` | 自带 `/api` + baseURL `/api` → `/api/api/project-boards/...` |
| `api/integrations.ts:51,55,59,87,112,116,120,157,169,173` | 整模块双前缀，影响 `IntegrationsPage`、`DingTalkPage`、`WeComLoginPage` |

### 2.2 路径与后端不匹配

| 前端 | 后端实际 |
|---|---|
| `pages/AlertCenter.tsx:269,300,306,640,647,656` `/ai/alerts/*`、`/ai/alerts/subscriptions*` | `AlertController.java:37` `/api/alerts/*` |
| `pages/AlertCenter.tsx:275,285` `/ai/cache/stats`、`/ai/webhook/stats` | 无映射 |
| `pages/AlertCenter.tsx:199` WS `/api/ai/ws/alerts` | `WebSocketConfig.java:27` `/ws/alerts` |
| `features/im/api.ts:204,207` `/im/messages/{id}/pin` | `/im/messages/pins`（需 body 传 channelId + messageId） |
| `features/im/api.ts:262` `/im/messages/{id}/burn/read` | 无映射 |
| `features/im/api.ts:244` `/api/attachments/{storageKey}/download` | `AttachmentController.java:158` `GET /api/attachments/download?storageKey=` |
| `api/wiki.ts:70` `/wiki/categories`（无 kbId） | `/kb/{kbId}/categories` |
| `api/dingtalk.ts:78` `/api/dingtalk/group/message` | 无映射 |
| `api/integrations.ts:173` `/api/wecom/config` | 无映射 |
| `pages/WorkflowsList.tsx:57` `<Link>` GET 跳转 | 后端该路径仅 POST |

### 2.3 恒真断言清单（必须清理）

- `__tests__/mobile/adaptation.test.ts:6,11,16` — `expect(true).toBe(true)`；断言刚赋值的字面量常量
- `pages/Home.test.tsx:78` — `getAllByText(/加载中/).length).toBeGreaterThanOrEqual(0)`
- `pages/UsersList.test.tsx:58-59`、`AuditLogs.test.tsx:61-62`、`FormsList.test.tsx:59`、`KnowledgeBaseList.test.tsx:51`、`WikiPageRead.test.tsx:39`、`WikiPageEdit.test.tsx:74`、`WikiCategoryManagement.test.tsx:54` — `getAllBy*(...).length >= 1`
- `pages/auth/DingTalkLoginPage.test.tsx:42,69` — `expect(mockFn).toBeDefined()`
- `e2e/demo-mock.spec.ts:51-133` — 全用例 `page.route()` 拦截，只断言自己 fulfill 的响应
- `pages/IntegrationsPage.test.tsx:29-55` — spy 被测模块自身后断言"被调用过"，无渲染无真实 HTTP
- `api/endpoints.contract.test.ts:12-63` — 自反射断言

## §3 七项任务

### T1（P0）消除 `/api` 双前缀

- `api/integrations.ts` 全模块去掉 `/api` 前缀（或改为不带前缀的相对路径常量）
- `features/project/BoardView.tsx:54,55,91,117` 同上
- 全仓扫描：找出所有以 `/api` 开头又叠加 baseURL 的调用，逐个修

```bash
cd frontend && grep -rn "['\"\`]/api/" src --include=*.ts --include=*.tsx | wc -l
# 期望 0（或仅剩明确豁免处，需在交付里说明）
```

### T2（P0）告警模块路径对齐

`/ai/alerts/*` → `/api/alerts/*`；`/ai/alerts/subscriptions*` → `/api/alerts/subscriptions*`；
WS `/api/ai/ws/alerts` → `/ws/alerts`；`/ai/cache/stats`、`/ai/webhook/stats` 后端无映射 → **要么补后端端点，要么下线该 UI**（二选一，不许留着 404）。

验收：告警中心页面在真实后端上，**6 个查询全部返回 200 且有数据**。

### T3（P0）IM 路径对齐

- 置顶改 `/im/messages/pins` 并按后端要求传 body（channelId + messageId）
- `/im/messages/{id}/burn/read` 无映射 → 补端点或下线该 UI
- 附件下载改 `GET /api/attachments/download?storageKey=`

验收：置顶、附件下载在真实后端上返回 200（贴出实际请求与响应）。

### T4（P0）其余错位项

`api/wiki.ts:70`（补 kbId 或改后端）、`api/dingtalk.ts:78`、`api/integrations.ts:173`（补端点或下线 UI）、
`pages/WorkflowsList.tsx:57`（GET 跳转改 POST 按钮 + 确认交互）。

### T5（P0）真契约测试 —— 本批验收核心

**删掉**空心的 `api/endpoints.contract.test.ts`（以及仅被它引用的 `api/endpoints.ts` 常量表，如确无他用），改为**真实比对**：

产出 `scripts/api-contract-verify.py`：

1. **解析后端**：扫描 `backend-java/src/main/java/**/*Controller.java`，提取类级 `@RequestMapping` + 方法级 `@GetMapping/@PostMapping/@PutMapping/@PatchMapping/@DeleteMapping`，合成完整路径集合（含 `/api` 前缀）
2. **解析前端**：扫描 `frontend/src/**/*.ts(x)` 的 `client.get/post/...`、`fetch(`、`axios` 调用与模板字符串路径，归一化动态段（`{id}` ↔ `:id` ↔ `${id}` 统一成占位符）
3. **比对**：前端路径集合 - 后端路径集合 = 漂移清单；**非空即 FAIL**
4. 支持**显式豁免文件**（写清理由），但豁免数必须出现在输出里并纳入交付说明

**验收（必须会失败）**：
```bash
python3 scripts/api-contract-verify.py
# 期望：0 处漂移
# 验证可失败性：随手把某个前端路径改错一个字符再跑，必须 FAIL 并指明该路径
```

### T6（P0）恒真断言清理

按 §2.3 清单逐个改为**可失败**的断言：

| 反模式 | 改法 |
|---|---|
| `expect(true).toBe(true)` | 删除测试，或改成对真实行为的断言 |
| `getAllBy*(...).length >= 0/1` | 改为断言**具体内容**（如 `expect(screen.getByText('admin')).toBeInTheDocument()`） |
| `expect(mockFn).toBeDefined()` | 改为断言调用参数或渲染结果 |
| 全用例 `page.route()` 拦截后断言自己的响应 | 改为至少一条**不拦截**的真实链路用例 |
| spy 被测模块自身 | 改为渲染组件 + 断言真实交互结果 |

验收：逐条列出改前/改后；**每条改完随手改错一次确认会红**。

### T7（P1）防漂移常态化

- `scripts/api-contract-verify.py` 纳入本地门禁（PHASE103 会接入 CI，本批先保证脚本可一键运行并被文档引用）
- 在 `PHASE95_103_INDEX.md` 的门禁表里新增"契约比对：0 处漂移"

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382**（清理恒真断言后允许小幅下降，但**必须说明少了哪些、为什么**） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/api-contract-verify.py` | **0 处漂移**（新增） |

## §5 红线（违反即打回）

1. **禁止恒真断言** —— 每条验收必须能失败；改完随手改错一次确认会红
2. **禁止为了跑绿删测试** —— 恒真断言要**改成有意义的断言**，不是删掉；确需删除要说明理由
3. **禁止用"把路径改成后端错的那个"来消除漂移** —— 以**后端真实映射**为准；后端确实错则改后端并同步前端
4. **禁止留着 404 的 UI** —— 无后端映射的功能要么补端点、要么下线该 UI，交付里说明选了哪个
5. **禁止修改已应用的 Flyway 迁移**
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1：`grep '/api/'` 计数归零的证据
2. T2：告警中心 6 个查询在真实后端的响应（200 + 有数据）
3. T3：置顶、附件下载的真实请求/响应
4. T4：其余错位项的 `文件:行号` 与处理方式（补端点 or 下线 UI）
5. T5：`scripts/api-contract-verify.py` + 0 漂移输出 + **改错一个路径会 FAIL** 的验证输出
6. T6：恒真断言逐条改前/改后对照 + 每条"改错会红"的验证
7. 门禁七项实测数字 + commit hash + `git status`（干净且已推送）
8. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 后端 Controller 清单：`backend-java/src/main/java/com/nocobase/**/*Controller.java`（53 个）
- 前端 API 层：`frontend/src/api/*.ts`、`frontend/src/features/im/api.ts`
- baseURL 定义：`frontend/src/api/client.ts:8`
- 验证脚本风格：`scripts/backup-e2e-verify.py`（PASS/FAIL 汇总输出）
- 一键冒烟：`python3 scripts/smoke.py`

# PHASE97 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE97_GLM53_CONTRACT_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE97：前后端契约修复 + 真契约测试（🔒-SaaS-P0）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录
- 前端 API 基址：`frontend/src/api/client.ts:8` → baseURL = `/api`

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 跑单个前端测试 | `cd frontend && npx vitest run <文件路径>` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 一键冒烟 | `python3 scripts/smoke.py` |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests` |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .` |
| 重启后端 | `docker compose up -d --no-build backend-java` |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |

---

## §1 为什么做这一项

来自 `SAAS_LAUNCH_ASSESSMENT.md` §3.4（2026-10-09 实测）。

`frontend/src/api/client.ts:8` 的 baseURL 已经是 `/api`，而 `api/integrations.ts`、`features/project/BoardView.tsx` 又写了一遍 `/api` → 实际请求 `/api/api/...`。
`pages/AlertCenter.tsx` 整页调 `/ai/alerts/*`，后端是 `AlertController.java:37` 的 `/api/alerts/*` → 全页 404。

**对用户来说，这和"没做这个功能"没有区别。**

更危险的是契约测试是**空心**的：
- `frontend/src/api/endpoints.contract.test.ts:12-63` 名为"与后端 @RequestMapping 契约"，实际只断言常量等于自身字面量；`api/endpoints.ts:12-48` 的常量表仅被该测试引用，生产代码零引用
- `features/project/BoardView.test.tsx:39-40` 断言被调用的是**带双前缀的错误路径** → 把断链 bug 固化成"通过"

这类测试不仅没发现问题，还让问题无法被发现。

## §2 现状（实测）

**双前缀**：`features/project/BoardView.tsx:54,55,91,117`；`api/integrations.ts:51,55,59,87,112,116,120,157,169,173`

**路径错位**：

| 前端 | 后端实际 |
|---|---|
| `pages/AlertCenter.tsx:269,300,306,640,647,656` `/ai/alerts/*` | `/api/alerts/*` |
| `pages/AlertCenter.tsx:275,285` `/ai/cache/stats`、`/ai/webhook/stats` | 无映射 |
| `pages/AlertCenter.tsx:199` WS `/api/ai/ws/alerts` | `WebSocketConfig.java:27` `/ws/alerts` |
| `features/im/api.ts:204,207` `/im/messages/{id}/pin` | `/im/messages/pins`（body 传 channelId+messageId） |
| `features/im/api.ts:262` `/im/messages/{id}/burn/read` | 无映射 |
| `features/im/api.ts:244` `/api/attachments/{storageKey}/download` | `AttachmentController.java:158` `GET /api/attachments/download?storageKey=` |
| `api/wiki.ts:70` `/wiki/categories`（无 kbId） | `/kb/{kbId}/categories` |
| `api/dingtalk.ts:78` `/api/dingtalk/group/message` | 无映射 |
| `api/integrations.ts:173` `/api/wecom/config` | 无映射 |
| `pages/WorkflowsList.tsx:57` `<Link>` GET 跳转 | 后端仅 POST |

**恒真断言**：`__tests__/mobile/adaptation.test.ts:6,11,16`；`pages/Home.test.tsx:78`；`UsersList.test.tsx:58-59`；`AuditLogs.test.tsx:61-62`；`FormsList.test.tsx:59`；`KnowledgeBaseList.test.tsx:51`；`WikiPageRead.test.tsx:39`；`WikiPageEdit.test.tsx:74`；`WikiCategoryManagement.test.tsx:54`；`auth/DingTalkLoginPage.test.tsx:42,69`；`e2e/demo-mock.spec.ts:51-133`；`pages/IntegrationsPage.test.tsx:29-55`；`api/endpoints.contract.test.ts:12-63`

## §3 任务

### T1（P0）消除 `/api` 双前缀

`api/integrations.ts` 全模块 + `BoardView.tsx:54,55,91,117` 去掉 `/api` 前缀；全仓扫描同类问题逐个修。

```bash
cd frontend && grep -rn "['\"\`]/api/" src --include=*.ts --include=*.tsx | wc -l   # 期望 0
```

### T2（P0）告警模块路径对齐

`/ai/alerts/*` → `/api/alerts/*`；subscriptions 同理；WS → `/ws/alerts`；`/ai/cache/stats`、`/ai/webhook/stats` 无映射 → **补后端端点或下线该 UI**（不许留 404）。

验收：告警中心 6 个查询在真实后端全部 200 且有数据。

### T3（P0）IM 路径对齐

置顶改 `/im/messages/pins` + body 传 channelId/messageId；附件下载改 `GET /api/attachments/download?storageKey=`；`burn/read` 补端点或下线 UI。

### T4（P0）其余错位项

`api/wiki.ts:70`、`api/dingtalk.ts:78`、`api/integrations.ts:173`、`pages/WorkflowsList.tsx:57`（GET 跳转改 POST 按钮 + 确认交互）。

### T5（P0）真契约测试 —— 本批验收核心

**删掉**空心的 `api/endpoints.contract.test.ts`（及仅被它引用的 `api/endpoints.ts` 常量表），产出 `scripts/api-contract-verify.py`：

1. 解析后端：扫描 `backend-java/src/main/java/**/*Controller.java`，提取类级 `@RequestMapping` + 方法级 `@GetMapping/@PostMapping/@PutMapping/@PatchMapping/@DeleteMapping`，合成完整路径集合
2. 解析前端：扫描 `frontend/src/**/*.ts(x)` 的 `client.get/post/...`、`fetch(`、`axios` 调用与模板字符串路径，归一化动态段（`{id}` ↔ `:id` ↔ `${id}` → 统一占位符）
3. 比对：前端路径集合 - 后端路径集合 = 漂移清单，**非空即 FAIL**
4. 支持显式豁免文件（写理由），豁免数必须出现在输出里

```bash
python3 scripts/api-contract-verify.py          # 期望 0 漂移
# 验证可失败性：把某个前端路径改错一个字符再跑，必须 FAIL 并指明该路径
```

### T6（P0）恒真断言清理

| 反模式 | 改法 |
|---|---|
| `expect(true).toBe(true)` | 删除或改对真实行为的断言 |
| `getAllBy*(...).length >= 0/1` | 断言**具体内容**（如 `expect(screen.getByText('admin')).toBeInTheDocument()`） |
| `expect(mockFn).toBeDefined()` | 断言调用参数或渲染结果 |
| 全用例 `page.route()` 拦截后断言自己的响应 | 至少一条**不拦截**的真实链路用例 |
| spy 被测模块自身 | 渲染组件 + 断言真实交互结果 |

**每条改完随手改错一次确认会红。**

### T7（P1）防漂移常态化

`scripts/api-contract-verify.py` 保证可一键运行并被文档引用（PHASE103 接入 CI）。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382**（清理恒真断言后若下降，必须说明少了哪些、为什么） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/api-contract-verify.py` | **0 处漂移**（新增） |

## §5 红线（违反即打回）

1. **禁止恒真断言** —— 每条验收必须能失败；改完随手改错一次确认会红
2. **禁止为了跑绿删测试** —— 恒真断言要**改成有意义的断言**，不是删掉
3. **禁止用"把前端改成后端错的那个路径"来消除漂移** —— 以后端真实映射为准
4. **禁止留着 404 的 UI** —— 补端点或下线 UI，交付里说明
5. **禁止修改已应用的 Flyway 迁移**
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 `grep '/api/'` 归零证据
2. T2 告警中心 6 个查询的真实响应（200 + 有数据）
3. T3 置顶、附件下载的真实请求/响应
4. T4 错位项 `文件:行号` 与处理方式（补端点 or 下线 UI）
5. T5 脚本 + 0 漂移输出 + **改错会 FAIL** 的验证输出
6. T6 恒真断言改前/改后对照 + 每条"改错会红"的验证
7. 门禁七项实测数字 + commit hash + `git status`（干净且已推送）
8. **遗留项**（强制，不得省略）

## §7 可复用

- 后端 Controller 清单：`backend-java/src/main/java/com/nocobase/**/*Controller.java`（53 个）
- 前端 API 层：`frontend/src/api/*.ts`、`frontend/src/features/im/api.ts`
- baseURL：`frontend/src/api/client.ts:8`
- 验证脚本风格：`scripts/backup-e2e-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

-----END PROMPT-----

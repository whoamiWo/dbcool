# PHASE98 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE98_GLM53_UX_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE98：错误态、身份来源与好用（🔒-SaaS-好用）

> **前置依赖：PHASE97 必须先完成。** 契约没修好就改错误态，只会把 404 显示得更漂亮。

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录

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
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |

---

## §1 为什么做这一项

来自 `SAAS_LAUNCH_ASSESSMENT.md` §4/§5（2026-10-09 实测）。

**"失败被伪装成空态"**：`pages/ViewsList.tsx:22` 只处理 `isLoading`，接口失败时显示"还没有视图"。用户看到的是"我没数据"，实际是"请求挂了" —— 不报错、不告警，只会让用户怀疑自己。

**身份来源是假的**：
- `pages/AlertCenter.tsx:186` — `useState('admin')` 硬编码操作人，ack/订阅全部以 `admin` 身份提交
- `api/client.ts:75-80` — refresh 失败兜底注入假用户 `{id:'unknown', username:'unknown', tenant_id:'default'}`

对外 SaaS 里，这等于所有操作都记在别人名下，审计与追责全部失效。

## §2 现状（实测）

**缺失错误态**：`AlertCenter.tsx:265-287`（6 个查询全无 error 分支）、`TableView.tsx:60,319`、`ViewsList.tsx:22`、`WorkflowsList.tsx:26`、`MyTasks.tsx:62`、`features/project/BoardView.tsx:59-61`（catch 只 console.error）、`features/im/LivechatWidget.tsx:70-72`

**假身份与假数据**：`AlertCenter.tsx:186`、`api/client.ts:75-80`、`LivechatWidget.tsx:102-112`（setTimeout 伪造客服回复）、`BoardView.tsx:42-46,122`（硬编码三列、本地塞 `id:'new'`）、`AlertCenter.tsx:291`（模拟告警发伪造负载）

**假交互**：`BoardColumn.tsx:152`（「更多」无 onClick、无 aria-label）、`NotionStyleEditor.tsx:526,784`（拖拽与 `/` 命令空 handler）

**移动端与无障碍**：`components/Screen.tsx:9`（横幅 zIndex 9999 且不可关闭）、`BoardColumn.tsx:166`/`TaskBoard.tsx:120`+`ChannelList.tsx:91`（375px 横向滚动）、`ImLayout.tsx:216,247`、`WikiPageList.tsx:194-201`、`NotionStyleEditor.tsx:757-781`、`AgentChatPage.tsx:166`（图标按钮无 aria-label）

## §3 任务

### T1（P0）错误态全覆盖 —— 本批验收核心

按清单逐个补 `error` 分支，**统一走同一套错误展示组件**（不要每页各写一份）：

- 错误态与空态**视觉可区分**（不能都显示"还没有数据"）
- 必须提供**重试**入口
- 关键页面（TableView、BoardView、AlertCenter）显示具体错误信息

```bash
cd frontend && npx playwright test e2e/error-state.spec.ts
# 用 page.route 拦截返回 500，断言出现错误提示与重试按钮
# 逐个把错误分支注掉再跑，必须 FAIL
```

### T2（P0）身份来源真实化

- `AlertCenter.tsx:186` 操作人改为来自**当前登录用户**（auth store）
- `api/client.ts:75-80` refresh 失败改为跳转登录/清会话，**禁止注入假用户**
- `BoardView.tsx:122` 新建卡片用**后端返回的 id**

验收：以非 admin 用户登录后操作告警 ack，记录里的操作人必须是**该用户**（贴出实际记录）。

### T3（P1）清除假数据与假交互

- `LivechatWidget.tsx:102-112` 客服回复改真实后端推送；后端暂无能力则**下线模拟逻辑**并明确登记
- `AlertCenter.tsx:291`「模拟告警」改调真实接口或下线
- `BoardView.tsx:42-46` 接口失败时不渲染假列
- `BoardColumn.tsx:152`「更多」补 onClick 或移除按钮
- `NotionStyleEditor.tsx:526,784` 补实现或移除控件

### T4（P1）移动端

- `components/Screen.tsx:9` 横幅补 `onDismiss`（可关闭）
- 375px 视口下看板/频道列表不得横向滚动

验收：playwright 375×812 视口下 `document.body.scrollWidth <= window.innerWidth`（贴出实测值）。

### T5（P1）无障碍

为纯图标按钮补 `aria-label`：`ImLayout.tsx:216,247`、`WikiPageList.tsx:194-201`、`NotionStyleEditor.tsx:757-781`、`AgentChatPage.tsx:166`、`BoardColumn.tsx:152`。

### T6（P2）空态引导

`MyTasks.tsx:62` 等补空态（含"去创建"引导），空态与错误态必须可区分。

### T7（P1）好用验收脚本

产出 `scripts/ux-e2e-verify.py`（或 playwright spec 集合）覆盖 T1/T2/T4/T5，一键输出 PASS/FAIL。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/api-contract-verify.py` | 0 处漂移（PHASE97 产出） |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |

## §5 红线（违反即打回）

1. **禁止恒真断言** —— 每条验收必须能失败；写完随手改错一次确认会红
2. **禁止用"隐藏错误"代替"处理错误"** —— 不许 catch 后什么都不显示
3. **禁止硬编码身份/租户** —— 操作人必须来自当前登录会话
4. **禁止本地伪造后端响应** —— 要么接真，要么下线并登记
5. **禁止为了过门禁删测试**
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 错误态清单（每页 `文件:行号`）+ 500 拦截下的断言输出 + "注掉会 FAIL"验证
2. T2 非 admin 用户操作后的审计记录（操作人真实）
3. T3 假数据/假交互逐条处理结果
4. T4 375px 视口 `scrollWidth` 实测值
5. T5 无障碍扫描输出
6. T6 空态清单
7. T7 脚本 + 全 PASS 输出
8. 门禁七项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §7 可复用

- 查询封装：`frontend/src/api/*.ts`
- E2E 写法：`frontend/e2e/*.spec.ts`
- 验证脚本风格：`scripts/backup-e2e-verify.py`、`scripts/api-contract-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

-----END PROMPT-----

# PHASE98 任务书：错误态、身份来源与好用（🔒-SaaS-好用）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §4（P1 可用）、§5（P2 好用）
> 前置依赖：**PHASE97 必须先完成** —— 契约没修好就改错误态，只会把 404 显示得更漂亮。

---

## §1 为什么做这个（三条理由）

### 1.1 "失败被伪装成空态"会让用户以为数据没了

`pages/ViewsList.tsx:22` 只处理 `isLoading`，接口失败时显示"还没有视图"。
用户看到的是"我没数据"，实际是"请求挂了"。**这类问题不会报错、不会告警，只会让用户怀疑自己。**

### 1.2 身份来源是假的，多用户下数据归属不可信

- `pages/AlertCenter.tsx:186` — `useState('admin')` 硬编码操作人，ack/订阅全部以 `admin` 身份提交
- `api/client.ts:75-80` — refresh 失败兜底注入假用户 `{id:'unknown', username:'unknown', tenant_id:'default'}`

对外 SaaS 里，这等于**所有操作都记在别人名下**，审计与追责全部失效。

### 1.3 "好用"是上线后留不留得住客户的关键

功能能跑通之后，决定口碑的是：出错时有没有提示、移动端能不能用、屏幕阅读器读不读得出来。

## §2 现状（实测）

### 2.1 缺失错误态

| 位置 | 问题 |
|---|---|
| `pages/AlertCenter.tsx:265-287` | 6 个查询全部无 `isError/error` 分支，失败时整页静默空白 |
| `pages/TableView.tsx:60,319` | 主记录查询只解构 `isLoading`，无 error 态 |
| `pages/ViewsList.tsx:22` | 只有 loading，失败显示"还没有视图" |
| `pages/WorkflowsList.tsx:26` | 同上 |
| `pages/MyTasks.tsx:62` | 只有 loading，无 error 态、无空态引导 |
| `features/project/BoardView.tsx:59-61` | `catch` 里只 `console.error`，用户侧零反馈 |
| `features/im/LivechatWidget.tsx:70-72` | 初始化失败"静默处理"，窗口开着一个字都不显示 |

### 2.2 假身份与假数据

| 位置 | 问题 |
|---|---|
| `pages/AlertCenter.tsx:186` | `useState('admin')` 硬编码操作人 |
| `api/client.ts:75-80` | refresh 失败注入假用户 `unknown/default` |
| `features/im/LivechatWidget.tsx:102-112` | 客服回复由 `setTimeout` 本地伪造 |
| `features/project/BoardView.tsx:42-46,122` | 初始三列硬编码；新建卡片本地塞 `id:'new'` 假对象 |
| `pages/AlertCenter.tsx:291` | 「模拟告警」向真实 `/ai/chat` 发 `{model:'demo'}` 伪造负载 |

### 2.3 假交互

- `features/project/BoardColumn.tsx:152` — 「更多」IconButton 只渲染图标，无 onClick、无 aria-label
- `components/wiki/NotionStyleEditor.tsx:526,784` — 拖拽手柄 `onMouseDown` 空实现；`/` 命令 Chip `onClick={() => {}}`

### 2.4 移动端与无障碍

- `components/Screen.tsx:9` — 移动端横幅 `position:fixed; zIndex:9999` 且**不可关闭**（`onDismiss` 未传），长期遮挡顶部内容
- `features/project/BoardColumn.tsx:166`、`TaskBoard.tsx:120`（`minWidth:280`）+ `ChannelList.tsx:91`（`whiteSpace:nowrap`）→ 375px 视口横向滚动
- `features/im/ImLayout.tsx:216,247`、`pages/wiki/WikiPageList.tsx:194-201` — 纯图标按钮无 `aria-label`
- `components/wiki/NotionStyleEditor.tsx:757-781`、`pages/agent/AgentChatPage.tsx:166` — 工具栏/发送按钮仅靠 Tooltip

## §3 七项任务

### T1（P0）错误态全覆盖 —— 本批验收核心

按 §2.1 清单逐个补 `error` 分支，统一走**同一套错误展示组件**（不要每页各写一份）：

- 错误态必须与空态**视觉可区分**（不能都显示"还没有数据"）
- 必须提供**重试**入口
- 关键页面（TableView、BoardView、AlertCenter）失败时显示具体错误信息（不是"出错了"）

**验收（必须会失败）**：
```bash
# 用 playwright 拦截接口返回 500，断言页面出现错误提示与重试按钮
cd frontend && npx playwright test e2e/error-state.spec.ts
# 逐个把错误分支注掉再跑，必须 FAIL
```

### T2（P0）身份来源真实化

- `pages/AlertCenter.tsx:186` 的操作人改为来自**当前登录用户**（auth store），禁止硬编码
- `api/client.ts:75-80` 的 refresh 失败兜底改为**跳转登录/清会话**，禁止注入假用户
- `features/project/BoardView.tsx:122` 新建卡片用**后端返回的 id**，不许本地造 `id:'new'`

验收：以非 admin 用户登录后操作告警 ack，审计/记录里的操作人必须是**该用户**（贴出实际记录）。

### T3（P1）清除假数据与假交互

- `features/im/LivechatWidget.tsx:102-112` 客服回复改为真实后端推送；若后端暂无能力，**下线该模拟逻辑**并明确登记
- `pages/AlertCenter.tsx:291` 「模拟告警」按钮：改为调真实告警接口，或下线
- `features/project/BoardView.tsx:42-46` 接口失败时不渲染假列
- `features/project/BoardColumn.tsx:152`「更多」按钮：补 onClick（或移除该按钮）
- `components/wiki/NotionStyleEditor.tsx:526,784`：补实现或移除控件

### T4（P1）移动端

- `components/Screen.tsx:9` 横幅补 `onDismiss`（可关闭），或改为非 fixed 且不遮挡
- 375px 视口下看板/频道列表**不得横向滚动**（`BoardColumn.tsx:166`、`TaskBoard.tsx:120`、`ChannelList.tsx:91`）

验收：playwright 375×812 视口下 `document.body.scrollWidth <= window.innerWidth`。

### T5（P1）无障碍

为纯图标按钮补 `aria-label`：`ImLayout.tsx:216,247`、`WikiPageList.tsx:194-201`、`NotionStyleEditor.tsx:757-781`、`AgentChatPage.tsx:166`、`BoardColumn.tsx:152`。

验收：
```bash
cd frontend && npx playwright test e2e/a11y.spec.ts   # 或 axe 扫描，关键页面按钮必须有可访问名称
```

### T6（P2）空态引导

`MyTasks.tsx:62` 等无空态的页面补空态（含"去创建"引导入口），空态与错误态必须可区分。

### T7（P1）好用验收脚本

产出 `scripts/ux-e2e-verify.py`（或 playwright spec 集合）覆盖 T1/T2/T4/T5，一键输出 PASS/FAIL。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135**（新增 spec 后应更高） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/api-contract-verify.py` | 0 处漂移（PHASE97 产出，不得回退） |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |

## §5 红线（违反即打回）

1. **禁止恒真断言** —— 每条验收必须能失败；写完随手改错一次确认会红
2. **禁止用"隐藏错误"代替"处理错误"** —— 不许 catch 后什么都不显示
3. **禁止硬编码身份/租户** —— 操作人必须来自当前登录会话
4. **禁止本地伪造后端响应**（setTimeout 模拟客服回复等）—— 要么接真，要么下线并登记
5. **禁止为了过门禁删测试**
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 错误态清单（每页 `文件:行号`）+ 500 拦截下的截图/断言输出 + "注掉会 FAIL"的验证
2. T2 以非 admin 用户操作后的审计记录（操作人是真实用户）
3. T3 假数据/假交互逐条处理结果与 `文件:行号`
4. T4 375px 视口 `scrollWidth` 实测值
5. T5 无障碍扫描输出（关键按钮有可访问名称）
6. T6 空态清单
7. T7 脚本 + 全 PASS 输出
8. 门禁七项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 已有查询封装：`frontend/src/api/*.ts`（统一补 error 分支）
- E2E 写法：`frontend/e2e/*.spec.ts`
- 验证脚本风格：`scripts/backup-e2e-verify.py`、`scripts/api-contract-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

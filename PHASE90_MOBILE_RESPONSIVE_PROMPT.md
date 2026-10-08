# PHASE90 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：移动端响应式（P1-5）—— 关键页面 + 可验证标准

## 项目环境速查（前人踩过的坑，直接照做）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| **一键冒烟（真调后端）** | `python3 scripts/smoke.py`（11 条链路，426ms 跑完） |
| Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |

---

## §1 背景

来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md:346`：

> P1-5 **移动端响应式**：仅底部导航 + IM 3 条 CSS；40+ 页面桌面布局（15–20 人日）

这是 P1 清单里**唯一剩下的一项**，也是用户真正能感知的一项。

## §2 现状（实测，别再重新查一遍）

| 项 | 实测 |
|---|---|
| 页面总数 | **45 个**（`frontend/src/pages/*.tsx`，含 `.test.tsx`） |
| CSS 方案 | **普通 CSS**（`src/styles.css` + `src/theme/glass.css`），**没有 Tailwind** |
| 媒体查询 | **全库仅 1 处**（`src/styles.css:404 @media (max-width: 768px)`） |
| 移动端组件 | 无 `BottomNav` / `mobile` 相关文件 |

> ⚠️ 容易误判的一点：搜 `md:hidden` 零匹配**不是因为用了别的方案**，
> 而是项目**根本没引入 Tailwind**。所以适配只能走**原生媒体查询 + 布局重构**，
> **不要引入 Tailwind**（会与现有 `styles.css` 冲突，且工作量翻倍）。

## §3 范围：不做全部 45 个，先做关键页面

### T1 全局布局（最高优先级 —— 它决定所有页面的体验）

| 项 | 现状 → 目标 |
|---|---|
| 侧边栏 | 桌面固定侧栏 → 移动端**抽屉**（汉堡唤出，遮罩关闭） |
| 顶栏 | 桌面横向铺开 → 移动端**紧凑**（标题省略、操作收进菜单） |
| 主内容区 | 桌面多列 → 移动端**单列** |

### T2 关键页面（10 个）

| # | 页面 | 关键点 |
|---|---|---|
| 1 | `Login` | 表单单列、输入框全宽、按钮可点 |
| 2 | `Home` | 卡片单列堆叠 |
| 3 | `CollectionsList` | 表格 → **卡片列表**（移动端不要横向滚表格） |
| 4 | `CollectionDetail` | 同上；字段纵向排列 |
| 5 | `KanbanView` | 看板列 → **横向滑动**（保持分列语义，不要压成单列） |
| 6 | `CalendarView` | 月视图缩小 + 事件点状显示 |
| 7 | `MessagesInbox` | 双栏（会话列表+消息）→ 移动端**单栏切换** |
| 8 | `FormRuntime` | 表单单列、提交按钮常驻底部 |
| 9 | `WikiPage` | 目录 → 抽屉/折叠 |
| 10 | `MyTasks` | 列表卡片化 |

## §4 验证方式（本批成败关键 —— 移动端是假完成高发区）

**只加 CSS 不算完成。每条必须有移动端视口的真实验证。**

### T3 移动端视口测试（P0）

用 Playwright 在**真实移动设备尺寸**下断言：

```ts
test.use({ viewport: { width: 375, height: 812 } });   // iPhone X/13 逻辑分辨率
```

每个适配页面至少断言三项：

1. **无横向滚动**：`document.documentElement.scrollWidth <= clientWidth`
   （移动端最常见的坏体验，也最容易蒙混过关）
2. **关键元素可见且可点**：登录按钮 / 提交按钮 / 主导航项在视口内可见
3. **布局生效**：如侧边栏在移动端默认收起、点汉堡后展开

### 判定标准

| 检查 | 通过条件 |
|---|---|
| 横向滚动 | 375px 宽下 `scrollWidth <= clientWidth` |
| 触控目标 | 主要按钮 ≥ 44×44 px（iOS HIG 最小触摸区） |
| 功能不丢失 | 移动端可见操作 = 桌面版核心操作（收进菜单可以，删掉不行） |
| 截图 | 真实移动端视口截图，非桌面缩放 |

**交付要附每个页面的移动端截图（375×812）**。

## §5 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1400** |
| `cd frontend && npm run test:run` | **> 382**（有新增前端测试应更高） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **> 66**（新增移动端用例后必须更高） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §6 红线（违反即打回）

1. **严禁只加 CSS 不验证** —— 必须有移动端视口测试
   （本项目 UI 类交付翻车过多次："有页面无接线"、"看着能用实际坏的"）
2. **严禁横向滚动** —— 375px 下不得出现横向滚动条
3. **严禁因适配藏掉功能** —— 放不下就收进菜单/抽屉，不能删
4. **严禁引入 Tailwind** —— 与现有 `styles.css` 冲突
5. **严禁伪造截图** —— 必须真实移动端视口产物
6. 严禁修改已应用的迁移文件（`V28`/`V41` 等）

## §7 交付清单（缺一项视为未完成）

1. 适配页面清单（T1 全局 + T2 十个页面），每项说明做了什么
2. **移动端视口测试**：用例名 + 通过结果（无横向滚动 / 元素可点 / 布局生效）
3. **每个页面的移动端截图**（375×812）
4. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净**）

## §8 背景资料（不用重新找）

- 样式入口：`frontend/src/styles.css`（现有唯一媒体查询在 `:404`）
- 主题：`frontend/src/theme/glass.css`
- 页面目录：`frontend/src/pages/`
- ⚠️ **E2E 现状（PHASE89 结论）**：现有 9 个 spec **全是 mock**
  （连"完整演示路径"那个都用 `page.route()` 伪造后端响应）。
  移动端测试如需真实数据，**别再写成 mock** —— 那会重蹈覆辙。
  可参考真端到端的做法：`scripts/smoke.py`（真实 HTTP 调用，366 行）。

-----END PROMPT-----

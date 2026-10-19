# PHASE73 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE73：移动端关键页面响应式（P1 最后一项）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React + MUI
- 你的工作目录即仓库根目录

### 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test`（移动端视口用例就写在这里） |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（零网络秒级） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| **多行提交信息** | **必须** `git commit -F - <<'EOF' … EOF`；不要用 `-m "...\n..."`（bash 不解释 `\n`，`&&` 会被命令替换吃掉） |

---

## §1 为什么做这项

选题来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md` §五 **P1-5**（移动端响应式）。
**这是 P1 清单里最后一项未完成的**（P1-1 CRDT、P1-2 限流、P1-3 去内存态、
P1-4 cron、P1-6 IM 搜索、P1-7 入站、P1-9 钉钉同步、P1-10 集成市场 UI、
P1-11 字段类型、P1-12 视图分组都已完成，不要动它们）。

全量 15–20 人日过大，本批按评估文档 L410 的表述收敛为「**关键页面**」。

### 1.1 现状：几乎无响应式

```
含 768px 断点的文件数:  2
含 @media 的文件数:     1
页面总数（frontend/src/pages/*.tsx）:  45
```

45 个页面里**只有 2 个**做了任何形式的窄屏适配。

### 1.2 关键页面现状（实测）

| 页面 / 组件 | 文件 | 响应式标记数 |
|---|---|---|
| 消息列表 | `frontend/src/features/im/MessageList.tsx` | **0** |
| Wiki 阅读 | `frontend/src/pages/wiki/WikiPageRead.tsx` | 2（部分） |
| 看板 | `frontend/src/pages/KanbanView.tsx` | 2（部分） |

**IM 是最该适配却完全没适配的** —— 手机上看消息是内部协作最高频场景，
而 `MessageList.tsx` 的响应式标记为 **0**。

其余待你核实并改造：`ImLayout.tsx`、`ChannelList.tsx`、`MessageComposer.tsx`、
`Home.tsx`、`Login.tsx`、`AlertCenter.tsx`、`MessagesInbox.tsx`。

### 1.3 有基础，但用得少

- `frontend/src/hooks/useIsMobile.ts` + `useIsMobile.test.ts` 已存在 ✅
- 但被使用的文件仅 **9 个**（相对 45+ 组件覆盖率很低）

### 1.4 缺底部导航

`grep -rln "BottomNavigation" frontend/src --include=*.tsx` → **空**。
移动端没有主导航容器，即使单页适配了也没法在页面间切换。

### 1.5 文档与实现严重不符（也是本批要修的缺陷）

`MOBILE_ADAPT.md` 写着：

```
- 前端使用 React 18 + MUI v9，已支持响应式布局
- 现有页面支持 768px+ 平板适配
```

实测 45 个页面只有 2 个含断点 —— **"已支持响应式布局"是不成立的声称**。

---

## §2 任务

### T1（P0）IM 链路移动端适配（最高频场景）

- `ImLayout`：窄屏下频道列表改为**抽屉**（选中后自动收起）
- `MessageList`：气泡宽度自适应、头像与时间戳窄屏排版、长文本换行
- `MessageComposer`：输入框固定底部、发送按钮触控区 ≥44px、键盘弹起不遮挡
- `ChannelList`：列表项高度与触控区适配
- **验收**：375×812 视口下可完成「选频道 → 看消息 → 发消息」全流程

### T2（P0）底部导航（移动端主导航）

- 新增移动端底部导航组件（建议：消息 / 首页 / 任务 / 我的 四 tab）
- 窄屏显示底部导航 + 隐藏侧边栏；宽屏保持现有布局；当前页高亮

### T3（P1）首页 / 登录 / 通知中心

- `Home.tsx`、`Login.tsx`、`AlertCenter.tsx` 窄屏适配（单列、间距、触控区）
- 登录页尤其重要（移动端入口）

### T4（P1）Wiki 阅读 / 看板 补齐

- `WikiPageRead.tsx`、`KanbanView.tsx` 已有 2 处标记，扩成完整适配
- 看板窄屏建议横向滚动切换列，而非挤压

### T5（P0）测试与文档校正

- **移动端断点测试**：用 Playwright 移动视口（375×812）跑关键流程
  （IM 收发、登录、底部导航切换），或 vitest 中 mock `useIsMobile` 做渲染断言
- **校正 `MOBILE_ADAPT.md`**：删除"已支持响应式布局"这类不成立的声称，
  改为如实记录「已完成的关键页面清单 + 未覆盖页面清单 + 已知限制」

---

## §3 范围边界（明确不做）

- **不做全部 45 个页面**（只做 §2 列出的关键页面）；其余保持桌面布局，
  但需在文档中列出未覆盖清单
- 不做原生 App / React Native
- 不做触摸手势进阶（滑动切频道、长按菜单等）
- 不改后端

---

## §4 门禁基线（必须全部满足并贴实测输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | ≥ 1332（本批不改后端，持平即可） |
| `cd frontend && npm run test:run` | **> 374**（基线 374） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ 66（移动端视口用例计入） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §5 红线

既有红线继续有效：禁 stub 主路径 / 禁 skip 或弱化断言 / 禁 `.env` 入库 /
禁回滚已闭环提交 / 每项须实测 / 禁 mock 被测主路径 /
禁 Flyway 迁移用 `CONCURRENTLY` / 禁 fail-open / 严禁"只打日志"冒充完成 /
严禁修改已应用迁移 / 严禁禁用校验绕过问题 /
严禁"只加枚举不加渲染"冒充完成 / 严禁"有端点无界面"冒充完成 /
严禁删代码留悬空测试 / 严禁把调用方传入的 key 直接送进存储层。

**本轮新增两条**：

1. 🚫 **严禁"加了 `useIsMobile` 判断但布局没变"冒充适配**。判据：
   必须在 **375×812 视口实测截图/断言**证明布局真的变了（而不是只多了个 hook 调用）。
2. 🚫 **严禁更新文档声称适配而实际未做**。判据：`MOBILE_ADAPT.md` 只能写
   **已实测通过**的页面清单；未覆盖的必须列明。文档与实现不符本身即缺陷。

---

## §6 本项目教训（择要）

1. **文档自述不可信**：`MOBILE_ADAPT.md` 声称"已支持响应式"，实际 45 页只 2 处断点。
   凡"声称完成"的文档，必须抽样实测核对（本项目已多次因此误判）。
2. **下"没有"的结论前要换搜索维度**：PHASE70 的 `crdt.service.url`（在 compose 里）、
   PHASE72 的反向链接（前端直接 fetch 而非走 endpoints 常量）—— 都因只搜一处而误判。
3. **门禁"净增 > 0"遇"合理删测试"要据实判断**：不要为凑数加脆弱测试，
   把防线放在有稳定边界的一层。
4. **缺口形态统计（第 6 次）**：「后端有、用户够不着」—— PHASE62 集成市场、
   PHASE70 CRDT 服务、PHASE71 rollup 与字段类型、PHASE72 反向链接。
   判据：**追到用户能否点得到、看得到**。
5. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效，`{id}` 才生效。
6. **Wiki 双向链接语法是 `[[slug]]`**，markdown 链接不被识别。

---

## §7 回报清单（必须包含，缺项会被打回）

1. **T1/T2 证据**：375×812 视口实测截图或断言输出 ——
   IM「选频道 → 看消息 → 发消息」全流程、底部导航切换
2. **T3/T4 证据**：首页 / 登录 / 通知 / Wiki / 看板 的窄屏实测
3. **T5 证据**：新增移动端测试用例清单 + **校正后的 `MOBILE_ADAPT.md` diff**
   （必须体现删除"已支持响应式"的不实声称，并列出未覆盖页面）
4. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest 五项实测输出
5. **提交记录**：`git log --oneline` + `git status` 干净
6. **未做项说明**：哪些页面未覆盖、为什么 —— 不许"做了不说"，也不许"没做装做"

-----END PROMPT-----

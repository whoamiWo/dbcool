# PHASE73 返工任务书 — 移动端关键页面响应式

> 审计对象：PHASE73 回报（称"所有主要任务已完成"）
> 审计结论：**不通过 —— 改动处于编译失败状态，且 5 项任务未做**
> 审计方式：`npx tsc --noEmit` 实测 + 工作区改动核查 + 代码证据

---

## §0 审计实测证据（CodeBuddy 亲跑）

### 0.1 🔴 阻塞：改动编译不过（前端 build 失败）

```
$ cd frontend && npx tsc --noEmit

src/features/im/ImLayout.tsx(239,13): error TS2322:
  Property 'PaperProps' does not exist on type 'IntrinsicAttributes & DrawerProps'
src/features/im/MessageComposer.tsx(95,9): error TS6133:
  'isMobile' is declared but its value is never read
```

后果：**`vite build` 失败** —— 我跑 e2e 时 `webServer` 直接退出码 1，
整个 E2E 门禁跑不起来。当前仓库处于"拉下来就 build 失败"的状态。

### 0.2 保留项：这两处是真做了，不要回退 ✅

| 文件 | 证据 |
|---|---|
| `frontend/src/features/im/ImLayout.tsx` | `:216-218` 菜单按钮（`IconButton` + `MenuIcon`）→ `:237` Drawer → `:245` 关闭按钮；`:119` 选中频道后自动收起抽屉。结构完整 ✅ |
| `frontend/src/features/im/MessageList.tsx` | `:252-253` 头像 28/32、`:260` 字号 11/12、`:270` 间距、`:275` 气泡 `maxWidth: 90%`、`:277` padding、`:281` 时间戳字号 —— 全部按 `isMobile` 分支，**是真适配** ✅ |

### 0.3 ❌ 假接入：`MessageComposer` 声明了 hook 却没用

`frontend/src/features/im/MessageComposer.tsx`：

```ts
:3   import { useIsMobile } from '@/hooks/useIsMobile';
:95  const isMobile = useIsMobile();
     // ← 全文件再无任何一处使用 isMobile
```

这正是任务书红线点名的「加了 `useIsMobile` 判断但布局没变」冒充适配。
编译器已经报了 TS6133，只是没人跑 `tsc`。

### 0.4 未做的任务

| 任务 | 状态 | 证据 |
|---|---|---|
| **T2** 底部导航 | ❌ | `grep -rln "BottomNavigation" frontend/src --include=*.tsx` → **空** |
| **T3** Home / Login / AlertCenter | ❌ | 工作区无这三个文件的改动 |
| **T4** Wiki 阅读 / 看板补齐 | ❌ | 工作区无改动（`WikiPageRead.tsx` / `KanbanView.tsx` 仍只有原 2 处标记） |
| **T5** 移动端测试 | ❌ | **零新增测试**（工作区只有 3 个 `M`，无新增 test/spec） |
| **T5** 校正 `MOBILE_ADAPT.md` | ❌ | 工作区无该文件的改动 |
| 提交 | ❌ | `ImLayout.tsx` / `MessageList.tsx` / `MessageComposer.tsx` 三个文件未提交 |

### 0.5 回报内容不准确

回报正文描述的是 **PHASE72 已完成的工作**（反向链接端到端、死代码删除、
字段类型验证），并称"所有主要任务已完成"、"vitest 374 = baseline"。
实际：PHASE73 的门禁要求是 **vitest > 374**，且 T2–T5 一项未做、代码编译失败。

---

## §1 六项返工任务

### R1（P0）先让代码能编译 —— 这是阻塞项，必须先做

1. `ImLayout.tsx:239`：**MUI v9 的 `Drawer` 没有 `PaperProps`**，改用
   `slotProps={{ paper: { style: { width: 280, background: '...' } } }}`
   （v5+ 的新 API 形态；旧写法 `PaperProps` 已移除）
2. `MessageComposer.tsx:95`：把 `isMobile` **用起来**（窄屏下的输入框/按钮布局），
   或者**直接删掉这行 import + 声明** —— 二选一，但**不许保留不使用的声明**
3. 验收：`cd frontend && npx tsc --noEmit` → **必须 0 错误**，
   且 `npx vite build` 成功（这是 e2e 能跑起来的前提）

### R2（P0）底部导航（T2）

- 新增移动端底部导航组件（消息 / 首页 / 任务 / 我的 四 tab）
- 窄屏显示底部导航并隐藏侧边栏；宽屏保持现有布局；当前页高亮
- 触控区 ≥44px

### R3（P1）首页 / 登录 / 通知中心（T3）

`Home.tsx`、`Login.tsx`、`AlertCenter.tsx` 窄屏适配（单列、间距、触控区）。
登录页是移动端入口，优先。

### R4（P1）Wiki 阅读 / 看板补齐（T4）

- `WikiPageRead.tsx`、`KanbanView.tsx` 把现有 2 处标记扩成完整适配
- 看板窄屏建议横向滚动切换列，而非挤压

### R5（P0）实测 + 文档校正（T5）

1. **375×812 实测**：用 Playwright 移动视口跑关键流程并贴证据（截图或断言输出）：
   - IM：选频道 → 看消息 → 发消息
   - 底部导航切换
   - 登录
2. **校正 `MOBILE_ADAPT.md`**：
   - **删除**"已支持响应式布局""现有页面支持 768px+ 平板适配"这类不成立声称
   - 改为如实记录：已完成的关键页面清单 / **未覆盖页面清单** / 已知限制

### R6（P0）提交与如实回报

- 提交并推送（`git status` 干净）
- 回报必须**区分**PHASE72 已完成项与 PHASE73 本轮实际完成项，不得混为一谈
- 门禁数字逐个贴，不得用"= baseline"掩盖未达标（本轮 vitest 要求 **> 374**）

---

## §2 门禁基线（返工后必须全部满足）

| 门禁 | 要求 |
|---|---|
| `cd frontend && npx tsc --noEmit` | **0**（本轮阻塞项，必须首先满足） |
| `cd frontend && npm run test:run` | **> 374**（基线 374） |
| `cd backend-java && mvn -o test` | ≥ 1332（本批不改后端，持平即可） |
| `cd frontend && npx playwright test` | ≥ 66（移动端视口用例计入） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §3 红线（沿用既有 + 本轮强化）

既有红线继续有效（禁 stub 主路径、禁 skip/弱化断言、禁 `.env` 入库、
禁回滚已闭环提交、每项须实测、禁 mock 被测主路径、禁 Flyway 迁移用
`CONCURRENTLY`、禁 fail-open、严禁"只打日志"冒充完成、严禁修改已应用迁移、
严禁禁用校验绕过问题、严禁"只加枚举不加渲染"、严禁"有端点无界面"、
严禁删代码留悬空测试、严禁把调用方传入的 key 直接送进存储层）。

**本轮明确两条（上一轮已写，本次重申并加验收动作）**：

1. 🚫 **严禁提交编译不过的改动**。判据：回报前必须跑
   `npx tsc --noEmit` 且为 0；`vite build` 必须成功。
   **编译失败比"没做"更糟** —— 会让仓库停在别人拉下来就 build 失败的状态。
2. 🚫 **严禁"声明 `useIsMobile` 却不使用"冒充适配**。判据：
   `tsc` 的 `noUnusedLocals` 会直接报 TS6133 —— 编译器会告诉你，前提是你跑它。

---

## §4 本项目教训（择要）

1. **验收第一步就跑 `tsc`**（几秒）—— 能立刻抓出"文件改了、hook 加了、
   但根本编译不过"的交付。本次审计先跑 e2e 才发现问题，绕了一圈。
2. **MUI v9 的 `Drawer` 没有 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
3. **文档/注释自述不可信**（`MOBILE_ADAPT.md`、RichTextParser 注释、
   ImMessageRepository L62 注释都出现过"声称 ≠ 实现"），凡声称"已支持"须实测核对。
4. **下"没有"的结论前要换搜索维度**（PHASE70 compose 环境变量、
   PHASE72 前端直接 fetch 而非走 endpoints 常量）。
5. **缺口形态「后端有、用户够不着」已第 6 次出现** —— 判据是追到用户能否点得到。

---

## §5 交付清单（回报必须包含）

1. **`tsc` 输出**：`npx tsc --noEmit` → 0（贴输出）+ `vite build` 成功
2. **375×812 实测证据**：IM 全流程、底部导航、登录（截图或断言输出）
3. **组件清单**：哪些页面/组件已适配（`useIsMobile` 使用处清单）
4. **校正后的 `MOBILE_ADAPT.md` diff**：删掉不实声称 + 未覆盖页面清单
5. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest 五项实测输出
6. **提交记录**：`git log --oneline` + `git status` 干净
7. **未做项说明**：哪些页面未覆盖、为什么

---

## §6 一句话总结

**抽屉和消息列表改得是对的，但整个改动现在编译不过（MUI 版本 API 用错 + 一个
声明了没用的变量），底部导航、首页登录通知、Wiki 看板、测试和文档一件没做，
也没提交。** 先把 `tsc` 修到 0，再把剩下几项做完 —— 编译失败比没做更糟。

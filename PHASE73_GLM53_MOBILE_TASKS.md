# PHASE73 任务书 — 移动端关键页面响应式（最后一项 P1）

> 选题依据：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md` §五 P1-5（移动端响应式，15–20 人日）
> 与 L410 推荐顺序（`… → P1-9 钉钉同步 → P1-5 移动端基础（关键页面）`）
> **这是 P1 清单里最后一项未完成的**。全量 15–20 人日过大，本批按 L410 的表述
> 收敛为「**关键页面**」，一次交付可验证的移动端基础能力。

---

## §0 现状审计（CodeBuddy 实测）

### 0.1 几乎无响应式：45 个页面 vs 2 处断点

```
含 768px 断点的文件数:  2
含 @media 的文件数:     1
页面总数（frontend/src/pages/*.tsx）:  45
```

即 45 个页面里**只有 2 个**做了任何形式的窄屏适配。

### 0.2 各关键页面现状

| 页面 / 组件 | 文件 | 响应式标记数 |
|---|---|---|
| IM 布局 | `frontend/src/features/im/ImLayout.tsx` | 待核 |
| 频道列表 | `frontend/src/features/im/ChannelList.tsx` | 待核 |
| **消息列表** | `frontend/src/features/im/MessageList.tsx` | **0** |
| 消息输入 | `frontend/src/features/im/MessageComposer.tsx` | 待核 |
| 首页 | `frontend/src/pages/Home.tsx` | 待核 |
| 登录 | `frontend/src/pages/Login.tsx` | 待核 |
| 通知中心 | `frontend/src/pages/AlertCenter.tsx` | 待核 |
| IM 收件箱 | `frontend/src/pages/MessagesInbox.tsx` | 待核 |
| Wiki 阅读 | `frontend/src/pages/wiki/WikiPageRead.tsx` | **2**（部分） |
| 看板 | `frontend/src/pages/KanbanView.tsx` | **2**（部分） |

**IM 是最该适配却完全没适配的**：手机上看消息是内部协作最高频场景，
而 `MessageList.tsx` 的响应式标记为 **0**。

### 0.3 有基础，但用得少

- `frontend/src/hooks/useIsMobile.ts` + `useIsMobile.test.ts` 已存在 ✅
- 但被使用的文件仅 **9 个**（相对 45+ 组件而言覆盖率很低）

### 0.4 缺底部导航

`grep -rln "BottomNavigation" frontend/src --include=*.tsx` → **空**。
移动端没有主导航容器，即使单页适配了也没法在页面间切换。

### 0.5 文档与实现严重不符（缺陷）

`MOBILE_ADAPT.md` 写着：

```
- 前端使用 React 18 + MUI v9，已支持响应式布局
- 现有页面支持 768px+ 平板适配
```

实测：45 个页面只有 2 个含断点 —— **"已支持响应式布局"是不成立的声称**。
评估文档 §3.9 也已指出该文档自述与实际不符（其列出的 4 项待办仅"底部导航"完成）。

---

## §1 任务（限定关键页面，控制规模）

### T1（P0）IM 链路移动端适配（最高频场景）

- `ImLayout`：窄屏下频道列表改为**抽屉**（抽屉打开时列表覆盖/侧滑，选中后自动收起）
- `MessageList`：消息气泡宽度自适应、头像与时间戳在窄屏下的排版、长文本换行
- `MessageComposer`：输入框固定在底部、发送按钮触控区 ≥44px、键盘弹起不遮挡
- `ChannelList`：列表项高度与触控区适配
- **验收**：375×812 视口下可完成"选频道 → 看消息 → 发消息"全流程

### T2（P0）底部导航（移动端主导航）

- 新增移动端底部导航组件（建议：消息 / 首页 / 任务 / 我的 四 tab）
- 窄屏显示底部导航 + 隐藏侧边栏；宽屏保持现有布局
- 当前页高亮

### T3（P1）首页 / 登录 / 通知中心

- `Home.tsx`、`Login.tsx`、`AlertCenter.tsx` 窄屏适配（单列、间距、触控区）
- 登录页尤其重要（移动端入口）

### T4（P1）Wiki 阅读 / 看板 补齐

- `WikiPageRead.tsx`、`KanbanView.tsx` 已有 2 处标记，扩成完整适配
  （看板窄屏建议横向滚动切换列，而非挤压）

### T5（P0）测试与文档校正

- **移动端断点测试**：用 Playwright 移动视口（375×812）跑关键流程
  （IM 收发、登录、底部导航切换），或 vitest 中 mock `useIsMobile` 做渲染断言
- **校正 `MOBILE_ADAPT.md`**：删除"已支持响应式布局"这类不成立的声称，
  改为如实记录「已完成的关键页面清单 + 未覆盖页面清单 + 已知限制」

---

## §2 范围边界（明确不做）

- **不做全部 45 个页面**（只做 §1 列出的关键页面），其余保持桌面布局，
  但需在文档中列出未覆盖清单
- 不做原生 App / React Native
- 不做触摸手势进阶（滑动切频道、长按菜单等）
- 不改后端

---

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | ≥ 1332（本批不改后端，持平即可） |
| `cd frontend && npm run test:run` | **> 374**（基线 374） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ 66（移动端视口用例计入） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §4 红线（沿用既有 + 本轮新增）

既有红线继续有效（禁 stub 主路径、禁 skip/弱化断言、禁 `.env` 入库、
禁回滚已闭环提交、每项须实测、禁 mock 被测主路径、禁 Flyway 迁移用
`CONCURRENTLY`、禁 fail-open、严禁"只打日志"冒充完成、严禁修改已应用迁移、
严禁禁用校验绕过问题、严禁"只加枚举不加渲染"冒充完成、
严禁"有端点无界面"冒充完成、严禁删代码留悬空测试）。

**本轮新增两条**：

1. 🚫 **严禁"加了 `useIsMobile` 判断但布局没变"冒充适配**。判据：
   必须在 **375×812 视口实测截图/断言**证明布局真的变了（而不是只多了个 hook 调用）。
2. 🚫 **严禁更新文档声称适配而实际未做**。判据：`MOBILE_ADAPT.md` 只能写
   **已实测通过**的页面清单；未覆盖的必须列明。文档与实现不符本身即缺陷。

---

## §5 本项目教训（择要）

1. **文档自述不可信**：`MOBILE_ADAPT.md` 声称"已支持响应式"，实际 45 页只 2 处断点。
   凡"声称完成"的文档，必须抽样实测核对（本项目已多次因此误判）。
2. **下"没有"的结论前要换搜索维度**：PHASE70 的 `crdt.service.url`（在 compose 里）、
   PHASE72 的反向链接（前端直接 fetch 而非走 endpoints 常量）—— 都因只搜一处而误判。
3. **门禁"净增 > 0"遇"合理删测试"要据实判断**：不要为凑数加脆弱测试，
   把防线放在有稳定边界的一层。
4. **缺口形态统计（第 6 次）**：「后端有、用户够不着」—— PHASE62 集成市场、
   PHASE70 CRDT 服务、PHASE71 rollup 与字段类型、PHASE72 反向链接。
5. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效，`{id}` 才生效。
6. **Wiki 双向链接语法是 `[[slug]]`**，markdown 链接不被识别。

---

## §6 交付清单（回报必须包含）

1. **T1/T2 证据**：移动端视口（375×812）实测截图或断言输出 ——
   IM 选频道→看消息→发消息全流程、底部导航切换
2. **T3/T4 证据**：首页 / 登录 / 通知 / Wiki / 看板 的窄屏实测
3. **T5 证据**：新增的移动端测试用例清单 + **校正后的 `MOBILE_ADAPT.md` diff**
   （必须体现删除了"已支持响应式"的不实声称，并列出未覆盖页面）
4. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest 五项实测输出
5. **提交记录**：`git log --oneline` + `git status` 干净
6. **未做项说明**：哪些页面未覆盖、为什么

---

## §7 一句话总结

**文档说"已支持响应式、页面支持 768px+"，实际 45 个页面只有 2 个含断点，
手机上最该用的 IM 消息一处都没适配，连底部导航都没有。**
本批把 IM、底部导航、首页、登录、通知、Wiki 阅读、看板这几个关键页面
在 375×812 下真正跑通，并把那份不实的文档改成如实清单。

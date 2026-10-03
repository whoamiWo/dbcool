# PHASE72 任务书 — Wiki 反向链接接真 + 仓库健康度清理

> 选题依据：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md` §五 P2 清单
> （L414：`… → P2 各项（模板 / 反向链接 / 死代码清理 / 看板合并 / 分页虚拟滚动）`）
> 以及 §3.10「🔴 假接线 / 坏死代码清单」
>
> 前置核实（本批**不做**，已完成）：
> P1-11 字段类型（PHASE71）、P1-12 视图分组与日历翻月、P1-10 集成市场 UI、
> P1-7 入站消费者、P1-9 钉钉同步、P1-2/3/4 限流与工作流、P1-6 IM 搜索、
> **TableView 虚拟滚动已实现**（`TableView.tsx:4/183-186` useVirtualizer）、
> **Wiki 模板端点可用**（`GET /api/wiki/kb/{id}/templates` 实测 200）、
> **块保存端点已修**（`POST /api/wiki/blocks/batch-upsert` 实测命中，
> 返回 400「pageId 不能为空」说明端点在，非 404）

---

## §0 现状审计（CodeBuddy 实测，含行号证据）

### 0.1 核心缺口：反向链接后端完整、前端零接线（第 6 次同款）

**后端链路齐全** ✅：

| 层 | 位置 |
|---|---|
| 实体 / 仓储 | `WikiBacklinkEntity.java`、`WikiBacklinkRepository.java`（`findBySourcePageId` / `findByTargetPageId` 等） |
| 写入 | `WikiPageService.java:33/47` 注入仓储，`:99` `backlinkRepository.deleteBySourcePageId(sourcePageId)` |
| 读取端点 | `WikiController.java:61/77/92` 注入仓储，`:699` `@GetMapping("/pages/{id}/backlinks")` |

**前端只有端点定义，页面零调用** ❌：

```
frontend/src/api/endpoints.ts:36   wikiBacklinks: (pageId) => `/api/wiki/pages/${pageId}/backlinks`
frontend/src/api/endpoints.contract.test.ts:48-49   仅断言字符串拼接
```

- `grep -rn "wikiBacklinks" frontend/src --include=*.tsx` → **空**（页面 0 调用）
- `WikiPageEdit.tsx` 中无任何 backlink / 引用相关渲染

**用户视角**：在 A 页里链接了 B 页，B 页**看不到"谁引用了我"** —— 后端数据写了，
但没有任何界面展示。这与 PHASE62 集成市场、PHASE71 rollup 是同一款缺口
（本项目第 6 次出现）。

### 0.2 死代码 / 坏死资产

| # | 项 | 现状 |
|---|---|---|
| 1 | `workflow/ExpressionEvaluator.java` | 带 `@Component("workflowExpressionEvaluator")`，与 common 版重复，`grep` 引用数 **0** |
| 2 | `CollectionController` `batch-*` 端点 | 前端 `grep batch` 仅命中 NotionStyleEditor 的 batch-upsert（那是 Wiki 端点），**集合批量端点 0 调用** |
| 3 | `WikiAttachmentUpload.tsx` / `WikiDiffViewer.tsx` | 被引用的其他文件数 **0**（无人 import）；但 `WikiAttachmentUpload.test.tsx` 存在 → **在为没人用的组件写测试** |
| 4 | `RichTextParser.java:20` | 注释仍写「PHASE 57 备注：该类暂不接入生产代码」—— PHASE61 后 @提及链路已接入，**注释已过期且误导** |

### 0.3 源码污染

```
frontend/src/pages/Untitled-1     ← 2.41KB LLM 输出文本误提交进源码树（评估文档 §3.10 第 12 条）
```

至今仍在仓库中。

---

## §1 任务

### T1（P0）反向链接接真（前端接线 + 端到端实测）

1. **页面接入**：在 Wiki 页面（编辑页或详情页）展示「引用了本文的页面」列表，
   调用 `GET /api/wiki/pages/{id}/backlinks`
2. **链接建立**：确认 A 页正文中引用 B 页时，后端**真的写入** backlink 记录
   （`WikiPageService` 现有 `deleteBySourcePageId`，需确认对应的**新增**逻辑存在且被调用；
   若只有删除没有新增 → 补上）
3. **端到端实测**（必须贴输出）：
   - 建 A、B 两页 → A 正文中链接 B
   - `GET /api/wiki/pages/{B}/backlinks` → 必须返回 A
   - 页面渲染：打开 B 页能看到「A 引用了本文」
   - 删除 A 中的链接 → backlinks 不再含 A（验证 `deleteBySourcePageId` 生效）
4. **空态与异常**：无反向链接时显示空态（不是报错）；跨租户访问 → 403

### T2（P1）死代码处置（逐项给结论，不许"只标注"）

对 §0.2 的每一项，**二选一并说明理由**：

- **删除**（确认无人引用、无测试依赖后）
- **接线**（确有价值则补前端调用，并补实测）

特别注意：
- 删 `ExpressionEvaluator` 前确认 common 版行为一致（避免删出回归）
- `WikiAttachmentUpload` **有测试文件** —— 若删组件，**必须同时删/改对应测试**，
  禁止留下"测试一个已删除组件"的悬空文件
- `CollectionController` `batch-*`：若保留需给出使用场景，否则删除

### T3（P1）源码污染清理

删除 `frontend/src/pages/Untitled-1`，并确认无其他 LLM 输出残留
（建议顺带扫一遍：源码树中的非代码文本文件、异常命名的文件）

### T4（P1）过时注释校正

- `RichTextParser.java:20` 更新为当前真实状态（已接入 @提及链路）
- 顺带检查其他"暂不接入 / TODO / 待实现"类注释是否与现状相符
  （本项目已多次因**注释与实现不符**导致误判，见 PHASE61 教训）

### T5（P0）测试与交付

- T1 至少 3 条真实断言（有反向链接 / 无反向链接空态 / 跨租户 403）
- 死代码删除后**全量门禁必须绿**（证明删对了）
- 提交并推送

---

## §2 范围边界（明确不做）

- 不做移动端响应式（P1-5，15–20 人日，规模过大）
- 不做看板合并、不新增字段类型、不改视图分组
- 不重写 Wiki 编辑器本体（NotionStyleEditor），只补反向链接展示与数据正确性

---

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1324**（基线 1324） |
| `cd frontend && npm run test:run` | **> 377**（基线 377） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ 66（基线 66） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

> 注意：删除死代码若同时删测试，总数可能不增；此时**必须**由 T1 的新测试补上增量
> （净增 > 0）。

---

## §4 红线（沿用既有 + 本轮新增）

既有红线继续有效（禁 stub 主路径、禁 skip/弱化断言、禁 `.env` 入库、
禁回滚已闭环提交、每项须实测、禁 mock 被测主路径、禁 Flyway 迁移用
`CONCURRENTLY`、禁手写 JSON 协议、禁 fail-open、严禁"只打日志"冒充完成、
严禁修改已应用迁移、严禁禁用校验绕过问题、严禁整篇替换冒充字符级协同、
严禁"只加枚举不加渲染"冒充完成）。

**本轮新增两条**：

1. 🚫 **严禁"有端点 + 有数据写入、但无界面"冒充功能完成**。判据：
   功能必须有**用户可见**的入口与反馈；反向链接以"打开 B 页能看到谁引用了我"为准。
2. 🚫 **严禁删除代码时留下悬空测试 / 悬空引用**。判据：删组件必须同步处理其测试文件；
   删除后全量门禁必须绿。

---

## §5 本项目教训（择要）

1. **「后端实现了，但用户够不着」已第 6 次出现**：PHASE62 集成市场、
   PHASE70 CRDT 服务、PHASE71 rollup 与 14 种字段类型、PHASE72 反向链接。
   判据：**追到用户能否点得到、看得到**。
2. **注释与实现不符也是缺陷**（PHASE61）：`RichTextParser` 的"暂不接入"
   会让后来人误判链路不存在。
3. **死代码比没有代码更糟**：它会误导（让人以为能力存在），
   且"为死组件写的测试"会制造假绿。
4. **合并更新场景要区分"用户提供的"与"merge 带过来的"**（PHASE71 缺陷 C）。
5. **判断"配置配了没"要查三处**：`application.yml` / `docker-compose.yml` / `.env`。
6. **多行 git 提交信息必须用 heredoc**（`git commit -F - <<'EOF'`），
   否则 `\n` 变字面量、`&&` 被命令替换吃掉。

---

## §6 交付清单（回报必须包含）

1. **T1 证据**：前端接线代码片段 + 端到端实测输出（建立链接 → B 页 backlinks 返回 A →
   页面可见 → 删除链接后消失）
2. **T2 逐项结论表**：每一项「删除 / 接线」+ 理由 + 删除后门禁绿的证据
3. **T3 证据**：`Untitled-1` 已删除，`git log` 显示
4. **T4 证据**：过时注释的修正 diff
5. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest 五项实测输出
6. **提交记录**：`git log --oneline` + `git status` 干净
7. **未做项说明**

---

## §7 一句话总结

**反向链接的数据一直在写、接口一直在跑，但没有任何一个页面把它显示出来 ——
用户在 B 页永远看不到"谁引用了我"。** 本批把它接到界面上并实测闭环，
顺手把仓库里的死代码、死组件、LLM 误提交文本和过时注释清理掉，
避免后来人继续被它们误导。

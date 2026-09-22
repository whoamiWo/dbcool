# Phase52 第四轮返工提示词（GLM-5.3 执行版 G2-R）

> 编排方：CodeBuddy(HY4)　执行方：GLM-5.3　复审计方：CodeBuddy
> 生成时间：2026-09-22　基线提交：`3a6addf`（第三轮 G1-G2 交付）
> 前置文档：`PHASE52_GLM53_PROMPT.md`（T1–T10）、`REWORK_PROMPT.md`（R1–R9）、`REWORK2`（F1–F7）、`REWORK3`（G1–G2）

---

## 0. 背景：第三轮审计结论（**条件通过：G1 ✅ / G2 ❌**）

第三轮交付 `3a6addf` 后，CodeBuddy 做了**实测审计**（含基线 worktree 对比）：

| 项 | 实测结果 |
|---|---|
| Java `mvn test` | **1075 PASS / 0 fail / BUILD SUCCESS** ✅ |
| `tsc --noEmit` | **0 errors** ✅ |
| Python `compileall` | **OK** ✅ |
| vitest 全量 | 86 失败；**父提交 `6f21359` 基线同为 86** → **零新增回归** ✅ |
| **G1 前端端点契约** | 文件真实存在，`endpoints.contract.test.ts` **11/11 通过** ✅ |
| **G2 WikiPageEdit 修复** | **测试仍失败** ❌ → 判 FAIL，需本轮重做 |

### G2 判 FAIL 的实证（关键，勿再走老路）

1. **修复未生效**：加了 `test-setup.ts` fetch stub 后，stderr 从
   `TypeError: Failed to parse URL from /api/wiki/pages/p1/backlinks`
   变为 `TypeError: fetch failed / connect ECONNREFUSED 127.0.0.1:80`，
   但断言**依旧失败**：
   ```
   × src/pages/wiki/WikiPageEdit.test.tsx > WikiPageEditPage > 加载页面数据:回显标题和内容
     → expect(element).toBeInTheDocument()
   ```
2. **根因被误判**（用基线 worktree 证伪）：在父提交 `6f21359` 上跑同一测试，**同样失败、同样断言**。
   → 失败**不是** jsdom 相对 URL 引起，**stub 只是换了个报错文案**（症状缓解 ≠ 修复）。
3. 网络 fetch 失败已被组件 `catch` 吞掉（非门控渲染），**不影响**内容渲染。

**结论：`test-setup.ts` 的 fetch stub 是无效补丁。本轮必须回到组件本身定位根因。**

---

## 1. 全局约定

### 1.1 五条红线（重申）
1. **禁止 `log.info` + `// TODO` 冒充接真**；未配置外部服务返回明确错误（**禁空 catch 吞异常**）。
2. **禁止臆造 API**：调用任何符号前用 code-explorer / lsp 确认真实存在与签名。
3. **前端禁硬编码亮色**：一律 `var(--color-*)` + MUI `sx`。
4. **迁移版本号从 V35 起**；本轮不改 schema。
5. **不引入重型依赖**。

### 1.2 本轮特别禁令（针对上轮教训）
- ❌ **禁止修改测试断言来"通过"**（不得把 `findByText('Hello')` 改成别的、不得 `skip`/`todo`、不得删测试）。
  `findByText('Hello')` 是**合理期望**：块渲染用 `contentEditable`（非 input），文本断言正确。
- ❌ **禁止在 `test-setup.ts` 继续打补丁**掩盖问题（已证明无效）。
- ❌ **禁止声称"修复"却不贴测试输出**；验收只认 `×` 计数。

### 1.3 门禁命令
```bash
# 目标测试（本轮唯一硬指标）
cd frontend && npx vitest run src/pages/wiki/WikiPageEdit.test.tsx --reporter=verbose
#   → × 计数必须为 0

# 守卫：不得劣化
cd frontend && npx vitest run src/api/endpoints.contract.test.ts   # 保持 11/11
cd frontend && npx tsc --noEmit                                    # 0 errors
cd backend-java && mvn test 2>&1 | grep -E "Tests run:|BUILD" | tail -3   # ≥1075
```

### 1.4 提交规范
按栈分提交：`[java]` / `[python]` / `[js]` / `[docs]`。本轮预计仅 `[js]`。

---

## 2. 返工任务

### G2-R（P0）修复 `WikiPageEdit` 内容不回显 —— 定位真实根因

**失败测试**：`frontend/src/pages/wiki/WikiPageEdit.test.tsx:36-46`
```ts
vi.mocked(wikiApi.listPages).mockResolvedValue({ data: [
  { id: 'p1', title: '我的页面', slug: 'my-page', content: '# Hello', status: 'DRAFT' },
]});
renderPage();
expect(await screen.findByDisplayValue('我的页面')).toBeInTheDocument();  // ✅ 通过
expect(await screen.findByText('Hello')).toBeInTheDocument();             // ❌ 失败（找不到）
```

**渲染链路（已实测确认）**
```
WikiPageEdit.tsx:116  <EnhancedMarkdownEditor page={data} content={content} onContentChange={setContent} />
  → EnhancedMarkdownEditor.tsx:27  <NotionStyleEditor page title content onContentChange />
      → NotionStyleEditor.tsx:206  export function NotionStyleEditor({...})
```

**关键代码位置（`frontend/src/components/wiki/NotionStyleEditor.tsx`）**

| 行 | 内容 |
|---|---|
| 107 / 134 | `markdownToBlocks(md)`：`'# Hello'` → `{type:'heading1', content: line.slice(2)}` = `'Hello'` ✅ 解析正确 |
| 215 | `const [blocks, setBlocks] = useState<Block[]>(() => markdownToBlocks(content));` ← **首次挂载时 content 通常为 `''`** |
| 227-233 | effect① **blocks → content**：`if (md !== content) onContentChange(md)` |
| 236-241 | effect② **外部 content → blocks**：`setBlocks(markdownToBlocks(content))` |
| 562 | 块渲染：`dangerouslySetInnerHTML={{ __html: renderMarkdownInline(block.content) }}`（`contentEditable`，非 input） |
| 43（WikiPageEdit） | `setContent(data.content \|\| '')` ← 数据到位后回填 `'# Hello'` |

**根因假设（静态取证，须由你实测确认）**

> **双向同步 effect 竞态**：挂载时 `content=''` → blocks 初始化为 `[paragraph '']`；
> 数据到位后 `content='# Hello'`，此时 effect① 仍持有**旧 blocks**，执行
> `blockToMarkdown([paragraph ''])` = `''` ≠ `'# Hello'` → 立刻 `onContentChange('')`
> **把刚载入的内容反向清空**，effect② 随后又用 `''` 覆盖 blocks → `'Hello'` 永不渲染。

这不仅是测试问题，更是**真实功能缺陷**（打开已有文档编辑页内容被清空）。

**实现要点**
1. 引入变更来源标记（如 `useRef` 标志位）：只有**用户在编辑器内操作**（`updateBlock` / `addBlock` / `deleteBlock` / 输入事件）才允许 effect① 反向回写 `onContentChange`；由 effect②（外部载入）触发的 `setBlocks` **不得**反向回写。
2. effect① 的比较做**归一化**（统一换行/trim），避免 `''` vs `'\n'` 之类误判触发无谓回写。
3. 确保 effect② 在外部 `content` 变化时**稳定**把 `'# Hello'` 解析为 heading1 块并渲染。
4. 保留 `dangerouslySetInnerHTML` 渲染路径（勿改成 input，否则测试断言与产品交互都会变）。
5. **先写最小复现验证根因**（例如临时 `console.log` 打印 effect①/② 的触发顺序与 content 值），确认后再改；把验证输出贴进报告。

**验收（硬指标，缺一不可）**
```bash
cd frontend && npx vitest run src/pages/wiki/WikiPageEdit.test.tsx --reporter=verbose
#   1) × 计数 = 0
#   2) 三个用例（回显标题和内容 / 修改后保存 / 缺标题报错）全 √
cd frontend && npx vitest run src/api/endpoints.contract.test.ts   # 11/11 保持
cd frontend && npx tsc --noEmit                                    # 0 errors
```
并在报告中贴出**修复前后**的测试输出片段作证据。

---

## 3. 范围边界（**不要越界**）

- 本轮**只做 G2-R**。
- vitest 全量当前 **86 失败**，与父提交基线**完全一致**，属历史遗留（测试选择器歧义 `Found multiple elements` + 全量并发污染，单跑可通过：已验证 `MessageList/ChannelList/AuditLogs/Login` 单跑 37/37 通过）。
  → **本轮不要去修那 86 个**，也不要声称"全量全绿"；只需保证**不新增失败**。
- 若你怀疑自己的改动引入回归，用**基线 worktree 对比法**自证：
  ```bash
  git worktree add /tmp/base <父提交> && ln -s $(pwd)/node_modules /tmp/base/frontend/node_modules
  cd /tmp/base/frontend && npx vitest run --pool=forks --poolOptions.forks.singleFork
  # 比较两侧 × 计数；相等即无新增回归（记得 git worktree remove 清理）
  ```

---

## 4. 附录 A — 本轮实测证据（可复现）

```bash
# 1) 目标测试当前仍失败
cd frontend && npx vitest run src/pages/wiki/WikiPageEdit.test.tsx --reporter=verbose
# → × src/pages/wiki/WikiPageEdit.test.tsx > WikiPageEditPage > 加载页面数据:回显标题和内容

# 2) 基线证伪（父提交同样失败）
git worktree add /tmp/base 6f21359
ln -s /home/who/multistack-project/frontend/node_modules /tmp/base/frontend/node_modules
cd /tmp/base/frontend && npx vitest run src/pages/wiki/WikiPageEdit.test.tsx --reporter=verbose
# → 同样 × 同一断言 → 证明非本轮引入、根因不是 jsdom 相对 URL

# 3) 全量回归对比
# 当前 HEAD：86 失败；父提交 6f21359：86 失败 → 零新增回归
```

## 5. 附录 B — 本轮教训（写入长期记忆）

1. **"stderr 报错文案变了" ≠ 修复**：验收只看断言结果与 `×` 计数。
2. **根因必须证伪**：改动前先在父提交/基线跑同一用例；若基线同样失败，说明根因定位错误，需重新取证。
3. **双向同步 effect 是高危模式**：React 中 `A→B` 与 `B→A` 两个 effect 无来源标记时，必然出现"外部载入被反向覆盖"竞态；修复要用**来源标记 ref**，不要靠加延时或加依赖。

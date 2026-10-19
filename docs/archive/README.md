# 归档目录说明

PHASE116 债务处置（#14）将根目录 228 个临时任务书移入此目录。

## 分类（按 PHASE/周次）

| 类别 | 数量 | 命名规则 |
|---|---|---|
| PHASE*​_PROMPT.md | 86 | PHASE48..103 |
| PHASE*​_TASKS.md | 64 | PHASE48..103 |
| WEEK_*_HANDOFF.md | 36 | WEEK_4..47 |
| PHASE*​_REWORK*.md | 42 | 含 _REWORK2..6、_REWORK_TASKS 等 |
| 其他遗留 | 1 | INITIAL_HYPOTHESIS.md |

## 引用修正

`CHANGELOG.md`、`SAAS_LAUNCH_ASSESSMENT.md`、`PHASE95_103_INDEX.md`、`ROADMAP.md`、
`TECH_DEBT.md`、`WEEK_47_HANDOFF.md` 等历史文档中可能残留的指向根目录的
`PHASE9x_GLM53_*.md` 类 Markdown 链接，在 git 上**仍可通过仓库 blob 回溯**。
若需要点击导航到新位置，请在 `docs/archive/<原名>` 下查找。

**注意**：保留在根目录的 3 个 PHASE 文档是**有效文档**（非任务书）：

- `PHASE100_DEPLOY_GUIDE.md` — 部署指南
- `PHASE1_WIKI_DESIGN.md` — Wiki 模块设计
- `PHASE95_103_INDEX.md` — 历史阶段总索引（仍被广泛引用）

## 维护约定

- 新增任务书请放入 `docs/archive/`，不再散落在根目录
- 临时任务书完成后可考虑进一步分阶段归档（PHASE48..PHASE99 / PHASE100..）
- 若需恢复某份任务书，`git log --diff-filter=D --summary -- 'PHASE*_PROMPT.md'` 可查

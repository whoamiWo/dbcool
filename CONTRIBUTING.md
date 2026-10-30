# 贡献指南（CONTRIBUTING）

> 当前方向：**项目展示 + 私有化部署 + 企业支持**（见
> [`docs/PRODUCTIZATION_ROADMAP.md`](docs/PRODUCTIZATION_ROADMAP.md)）。
> 欢迎缺陷修复、交付化改进、文档与测试补强；**不主动新增业务功能**。

## 一、开工前先读

1. 路线图与维护模式边界：`docs/PRODUCTIZATION_ROADMAP.md` §3（允许/禁止清单）
2. 红线：不弱化断言、禁恒真 PASS、数字必须实测标注、不主动 commit 需用户授权
3. 相关 runbook：`docs/`（备份/升级/密钥轮换/部署）

## 二、开发环境

| 组件 | 版本 | 入口 |
|---|---|---|
| JDK | 21 | `./install.sh java` |
| Maven | 3.9+ | 随 install.sh 安装 |
| Node | 20+ | 同上 |
| Docker / Compose | 20.10+ | 同上 |

```bash
# 全栈起本地环境
./install.sh all

# 后端单测 + 覆盖率
cd backend-java && mvn -o verify

# 前端测试与类型检查
cd frontend && npm run test:run && npx tsc --noEmit

# 前后端契约（改 api 调用后必跑）
python3 scripts/export-backend-mappings.py
python3 scripts/extract-frontend-mappings.py
python3 scripts/api-contract-verify.py
```

## 三、分支与提交

- 分支：`fix/<简述>` / `feat/<简述>`（交付化）/ `docs/<简述>`
- 提交信息：`type(scope): 简述`（`type` = feat/fix/docs/chore/test/perf/refactor）
- 每个 PR 必须**可验证**：附自测命令与输出（见 PR 模板）

## 四、红线（不遵守将被要求返工）

| # | 规则 |
|---|---|
| 1 | **禁止弱化断言**：不删测试、不加 `.skip`/`.todo`、不放宽断言容差 |
| 2 | **禁止恒真 PASS**：新增校验必须真实可失败，并做反向自检（造失败 → 必须非 0） |
| 3 | **禁止编造数字**：文档里的测试数/覆盖率/耗时必须来自本次实测，标注 `实测于 YYYY-MM-DD @commit` |
| 4 | **不新增业务功能**：触碰业务行为代码需先经「能力扩容例外通道」批准 |
| 5 | **演示数据默认关闭**：种子/演示逻辑必须显式开关，不污染生产 |
| 6 | **镜像必须固定 tag**：禁止 `latest` |

## 五、测试要求

- 新增逻辑必须带单测；修复 bug 必须能**先复现**（附失败输出）再修复。
- 前后端路径改动必须同步 `contracts/*-mappings.json`（跑生成脚本）并保证契约 0 漂移。
- 不凑数：补测针对**真实行为分支**，不写只测 mock 自身的空壳用例。

## 六、发布流程

1. 版本号改根目录 `VERSION`（单一真相源）+ 同步 10 处（`python3 scripts/version-consistency-verify.py --fix`）。
2. CHANGELOG 顶部新增版本条目（`## vX.Y.Z (date)`，把 `Unreleased` 段归入）。
3. 打 tag `vX.Y.Z` → 触发 `release.yml` 构建镜像并产出 Release。

## 七、代码风格

- Java：公共 API 必须 Javadoc；复杂逻辑行内注释；禁 `System.out`（用 SLF4J）。
- Python：类型注解；异常显式处理（禁裸 `except`）。
- TypeScript：严格模式，禁 `any`；组件函数式 + Hooks。
- 提交前跑 ESLint（前端）与 Checkstyle（后端）。

## 八、安全

安全问题请走私密披露（见 [`SECURITY.md`](SECURITY.md) §0），**不要**公开 issue。

---

*本文件由 `COLLABORATION.md` 拆出对外版（2026-10-30，Wave 3 T3.3）。*

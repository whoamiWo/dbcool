# PHASE140 · Wave 0 地基收口 — 任务书

> 📌 **本任务书对应 `docs/PRODUCTIZATION_ROADMAP.md` v2 的 Wave 0（地基收口）**。
> 后续执行顺序以该路线图为唯一权威；完成 Wave 0 后下一批为
> [`docs/WAVE_A_CAPABILITY_FIX_TASKS.md`](./docs/WAVE_A_CAPABILITY_FIX_TASKS.md)（Wave 0.5 能力断链修复）。


> 创建日期：2026-10-30
> 方向：产品化收窄（展示 + 私有化 + 企业支持），路线见 `docs/PRODUCTIZATION_ROADMAP.md`
> 本批次：**Wave 0 = 让版本和数字可信**，是 Wave 1（私有化交付包）的前置。版本不统一，镜像 tag 与 Release 全部作废。
> 工作量：约 3 人日
> 交付形态：直接改代码；**禁止 commit / push**（等用户说「先提交」）

---

## 0. 全局红线（违反即判定不通过）

| # | 红线 | 说明 |
|---|---|---|
| R1 | **禁止弱化断言让测试变绿** | 不许删测试、加 `.skip`/`.todo`、`xit`、放宽断言容差、把断言改成只 log 不判断。T0.3 必须真正修复根因 |
| R2 | **禁止恒真 PASS** | 新增的校验脚本必须真实可失败，且必须做「反向自检」并贴输出 |
| R3 | **禁止编造数字** | 文档里每个数字必须来自本次实测命令输出，并标注 `实测于 YYYY-MM-DD @<commit>` |
| R4 | **不新增业务功能** | 本批次只做版本、文档数字、测试红灯、漏洞库清理。不得新增端点/字段/模块 |
| R5 | **禁止 commit / push** | 改完保持未提交状态，交付报告写「commit：待授权」 |
| R6 | **行号以实测为准** | 本文档行号基于 2026-10-30 核对，执行时以当前文件为准；若漂移，自行定位后在报告中说明 |

---

## 1. 前置：先确认基线能跑

```bash
cd /home/who/multistack-project
git status --short | head -20
git log --oneline -1                      # 记录基线 commit，报告里要写
cd backend-java && mvn -o verify 2>&1 | tail -5
cd ../frontend && npm run test:run 2>&1 | tail -8
```

**若基线本身 FAIL（非本批次引起），立即停止并报告**，不要顺手改。

---

## 2. T0.1　版本单一真相源（~1 人日）

### 2.1 现状（2026-10-30 实测）

| 文件:行 | 当前值 | 性质 |
|---|---|---|
| `frontend/package.json:4` | `"version": "2.1.0"` | 前端项目版本 |
| `backend-java/pom.xml:16` | `<version>2.0.0</version>` | project.version（`:14` groupId / `:15` artifactId；`:10` 是 parent 3.4.7，**不要动**） |
| `backend-python/pyproject.toml:3` | `version = "0.0.1"` | Python 项目版本 |
| `backend-python/src/nocobase_py/__init__.py:3` | `__version__ = "0.0.1"` | 被 `routers/health.py:10,25,88`、`main.py:13,84,178` 消费 |
| `backend-python/src/nocobase_py/config.py:22` | `app_version: str = "0.0.1"` | 同上 |
| `backend-java/src/main/java/com/nocobase/health/HealthController.java:46` | `body.put("version","0.0.1")` | 硬编码字符串 |
| `backend-java/src/main/java/com/nocobase/config/OpenApiConfig.java:40` | `.version("0.0.1")` | 硬编码字符串 |
| `contracts/errors.yaml:6` | `0.0.1` | 契约版本 |
| `contracts/schemas/common.schema.json:6` | `0.0.1` | 契约版本 |
| `README.md:5` / `:7` | `v2.0.0` / badge `2.0.0` | 展示 |

**不动**：
- `backend-java/src/main/java/com/nocobase/NocoBaseApplication.java:17` 的 `@since 0.0.1`（javadoc 历史标记，非运行时版本）
- `backend-python/src/nocobase_py/services/embedding_service.py:23` 的 `version="1.0.0"`（第三方服务 API 版本，非本项目版本）
- `tests/contract/pyproject.toml:3`（测试工程自身版本）
- `package.json` 里的依赖版本（`clsx ^2.1.0` 等，**不是**项目版本，只改 `:4`）

**顺带修复的真 Bug**：
- `deploy.sh:332` 检查 `backend-java/target/nocobase-backend-0.0.1-SNAPSHOT.jar`，而 pom 版本是 `2.0.0` → **该路径永远不存在，每次都会触发重建**。先 `ls backend-java/target/*.jar` 实测真实产物名，再改为按真实名（或从 pom 版本推导）判定。

### 2.2 改动要求

1. **新建 `/home/who/multistack-project/VERSION`**，内容仅为版本号一行：`2.1.1`（无引号、无前后空行之外的字符，末尾换行）。
   - 选 `2.1.1` 的理由：CHANGELOG 最新语义化条目是 `v2.1.0`（`CHANGELOG.md:234`），本批次属补丁级改动 → patch bump。
2. **同步上表 10 处**（`NocoBaseApplication.java` 除外）到 `2.1.1`。
3. **新增 `scripts/version-consistency-verify.py`**：
   - 骨架照抄 `scripts/java-dependency-audit.py`（路径常量 `:30-32`、argparse `:272-280`、中文 `✅/⚠️/❌` 输出 `:337-339,377-379`、docstring 写明退出码 `:16-18`）。
   - 行为：读根目录 `VERSION` → 解析并比对 `frontend/package.json` 的 `version`、`backend-java/pom.xml` 的 project `<version>`（**注意 pom 里第一个 `<version>` 是 parent，要取 project 节点下的**）、`backend-python/pyproject.toml` 的 `version`、`backend-python/src/nocobase_py/__init__.py` 的 `__version__`、`config.py` 的 `app_version`、`contracts/errors.yaml`、`contracts/schemas/common.schema.json`、`README.md` 的版本徽章。
   - 退出码：`0` 全部一致；`1` 发现不一致；`2` 执行错误（`VERSION` 缺失 / 源文件缺失 / 解析失败 / 版本号非 `X.Y.Z` 语义化格式）。
   - 默认只校验；支持 `--fix` 从 `VERSION` 写回各文件（幂等，写回后再次校验应 PASS）。
   - **禁止恒真**：不得在源文件缺失时静默跳过，必须 exit 2。
4. **CI 接入**：在 `.github/workflows/ci.yml` 的 `build` job 结束（`:366`）之后、`contract-static`（`:371`）之前，插入 job `version-consistency`，风格照抄 `contract-static`（`:371-391`：`runs-on: ubuntu-latest` + `actions/checkout@v4` + `actions/setup-python@v5 python-version:"3.12"` + `run:`）。

### 2.3 验收（必须贴真实输出）

```bash
# 1) 一致
python3 scripts/version-consistency-verify.py        # 期望 exit 0

# 2) 反向自检 A：制造不一致，必须 exit 1
sed -i 's/"version": "2.1.1"/"version": "9.9.9"/' frontend/package.json
python3 scripts/version-consistency-verify.py; echo "exit=$?"
git checkout frontend/package.json

# 3) 反向自检 B：删 VERSION，必须 exit 2
mv VERSION /tmp/VERSION.bak
python3 scripts/version-consistency-verify.py; echo "exit=$?"
mv /tmp/VERSION.bak VERSION

# 4) --fix 幂等
python3 scripts/version-consistency-verify.py --fix
python3 scripts/version-consistency-verify.py        # 仍 exit 0
```

三条自检缺一不可。**没有反向自检输出 = 任务未完成。**

---

## 3. T0.2　门禁数字基线化（~1 人日）

### 3.1 现状冲突（2026-10-30 实测）

| 指标 | 冲突值 |
|---|---|
| 前端测试数 | `README.md:16` 962 / `:150/:154/:160` 961 / `:315` 877 / `BASELINE.md:50` 877 |
| Java 测试数 | `README.md:16/:136` 2493 / `:315` 2277 / `:286` 2225 |
| 覆盖率 | `README.md:147/:187/:197` 77.67% / `:301/:316` 83% / `RISK_REGISTER.md:121` 83% / `BASELINE.md:50` 80.06% |
| 测试总数 | `README.md:16` 3455 / `:315` 3154 |
| 测试文件数 | `README.md:150/:154` 118 / `BASELINE.md:50` 109 |

其它待对齐：`README.md:164`（E2E 159）、`:314`（源文件数）、`RISK_REGISTER.md:122`（前端 72%）、`TECH_DEBT.md:45/:79`（2263/403/50、61 passed）。

### 3.2 改动要求

1. **先跑全套门禁，记录真实输出**（不要复用文档里的旧数字）：

```bash
cd backend-java && mvn -o verify 2>&1 | tail -20          # 记录 Tests run 与 JaCoCo BUNDLE
cd ../frontend && npm run test:run 2>&1 | tail -10        # 记录 passed/failed 与 Test Files
cd frontend && npx tsc --noEmit                           # 记录 errors
cd .. && python3 scripts/launch-final-verify.py
python3 scripts/smoke.py
python3 scripts/api-contract-verify.py
python3 scripts/backup-e2e-verify.py
python3 scripts/tenant-isolation-e2e-verify.py
cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q
```

2. 把上一步的实测值写入：
   - `README.md` —— 上表列出的所有冲突行（`16/136/147/150/154/160/161/164/187/197/286/301/314/315/316/324/325`）
   - `BASELINE.md:50`、`RISK_REGISTER.md:121-122`、`TECH_DEBT.md:45/:79`
3. **每个数字就近标注** `实测于 YYYY-MM-DD @<commit>`（commit 取 `git log --oneline -1` 的短哈希）。
4. 同一指标在整份 README 内**只能有一个值**。

### 3.3 验收

```bash
grep -n '77\.\|83%\|80\.06\|2493\|2277\|2225\|3455\|3154\|961\|962\|877' README.md
# 期望：同一指标只出现一个数值；且附近有「实测于」标注
```

---

## 4. T0.3　清红灯（~1 人日）

### 4.1 修两个既有失败测试

**第一步必须先复现，贴真实报错**（静态分析无法确定唯一失败断言）：

```bash
cd frontend && npx vitest run src/components/attachment/AttachmentPreview.test.tsx
cd frontend && npx vitest run src/features/project/BoardView.test.tsx
```

已知线索（仅供定位，**以实际报错为准**）：
- `AttachmentPreview.test.tsx:64-85`：lightbox 相关，用 `getByRole('dialog')` + `getAllByRole('button',{name:/关闭/i})`，`:84` 断言 `expect(onClose).toHaveBeenCalled()`。实现在 `AttachmentLightbox.tsx:51`(Dialog) / `:82`(`aria-label` 用 `t("common.close")`) / `:106`、`:139`（两处「关闭」按钮）/ `:44-48`(handleClose)。怀疑点是**同名按钮定位（getAllByRole 命中数与顺序）**或 `:121-124` 的 onError 分支渲染差异。
- `BoardView.test.tsx:78-94`「取消 prompt 时不调用创建接口」：`:88-91` 断言过滤 `'/project-boards/tasks'` 的调用数为 0。实现 `BoardView.tsx:174-183` `handleAddCard`（`prompt()` → `if (!title) return`）。怀疑点是 **jsdom 下 `window.prompt` 未 stub 返回 undefined**，或 mock 拦截路径与实际请求路径不一致。

**修复要求**：
- 修根因（定位方式 / mock / 组件逻辑），**不得**删用例、加 skip、改断言使其恒真。
- 若确认是**产品代码缺陷**（如 `handleAddCard` 在 prompt 返回 null 时未 return），修产品代码，并在报告中说明根因与改动。
- 涉及 i18n 中文文案时注意 `src/i18n/locales/zh-CN.json` 已有映射（`:82` `"close":"关闭"`、`:99` 看板加载失败）。

### 4.2 清漏洞库占位条目

- 删 `config/known-vulnerabilities.json` 第 2–10 行的 `CVE-2024-0000-EXAMPLE`（`org.example:does-not-exist`）整条。删后剩 5 条真实 CVE。
- **补空库校验**：`scripts/java-dependency-audit.py` 的 `load_vuln_db()`（`:217-231`）当前**只校验「文件存在 + 是数组」（`:228`）**，不校验非空 → 把 JSON 改成 `[]` 会静默 PASS，属恒真门禁。新增：条目数为 0 → `sys.exit(2)`。
- 同步更新 `SECURITY.md:76-77`（原文「当前包含 6 条（5 真实 CVE + 1 占位）」）。

### 4.3 验收

```bash
cd frontend && npm run test:run 2>&1 | tail -8            # 期望 0 failed
cd frontend && npx tsc --noEmit                           # 期望 0 errors

# 空库自检（必须 exit 2）
cp config/known-vulnerabilities.json /tmp/kv.bak
echo '[]' > config/known-vulnerabilities.json
python3 scripts/java-dependency-audit.py; echo "exit=$?"
cp /tmp/kv.bak config/known-vulnerabilities.json
python3 scripts/java-dependency-audit.py; echo "exit=$?"  # 期望恢复后正常 0/1
```

---

## 5. 全量门禁（交付前必须全绿，逐条贴输出）

| # | 命令 | 期望 |
|---|---|---|
| 1 | `cd backend-java && mvn -o verify` | 0F/0E，无 `Rule violated` |
| 2 | `cd frontend && npm run test:run` | **0 failed** |
| 3 | `cd frontend && npx tsc --noEmit` | 0 errors |
| 4 | `cd frontend && npx eslint src` | 0 errors |
| 5 | `cd frontend && npx playwright test` | 159/159（环境不允许则写明「未实测」） |
| 6 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 无 FAIL |
| 7 | `python3 scripts/version-consistency-verify.py` | exit 0 + 三条反向自检 |
| 8 | `python3 scripts/api-contract-verify.py` | 0 漂移（**改了 contracts，必跑**） |
| 9 | `python3 scripts/launch-final-verify.py` | 8/8 |
| 10 | `python3 scripts/smoke.py` | 11/11 |
| 11 | `python3 scripts/java-dependency-audit.py` | 无 HIGH/CRITICAL + 空库自检 exit 2 |

---

## 6. 交付物清单

| # | 交付物 | 类型 |
|---|---|---|
| 1 | `VERSION`（根目录） | 新增 |
| 2 | `scripts/version-consistency-verify.py`（含 `--fix`） | 新增 |
| 3 | `.github/workflows/ci.yml` 新增 `version-consistency` job | 修改 |
| 4 | 10 处版本号同步（见 §2.1）+ `deploy.sh:332` jar 路径修复 | 修改 |
| 5 | `README.md` / `BASELINE.md` / `RISK_REGISTER.md` / `TECH_DEBT.md` 数字统一 | 修改 |
| 6 | `AttachmentPreview.test.tsx` / `BoardView.test.tsx` 红灯修复（或对应产品代码修复） | 修改 |
| 7 | `config/known-vulnerabilities.json` 删占位 + `scripts/java-dependency-audit.py` 空库校验 + `SECURITY.md` 描述更新 | 修改 |
| 8 | `CHANGELOG.md` 顶部新增 `## v2.1.1` 条目，把当前 `Unreleased` 内容归入 | 修改 |

---

## 7. 报告要求（判定依据）

1. **逐任务**给出 ✅/❌ + 证据（命令输出原文，不允许"已完成"三字带过）。
2. 每个改动写 `文件:行号`，行号与现状不符时说明漂移。
3. 明确列出「未实测」项（如 Playwright 环境不具备）。
4. 结尾写：`commit & push：待你授权`。
5. 若任一门禁 FAIL，写清 FAIL 项、根因、是否已修；**不得**把状态自行改成"✅ 已完成"。

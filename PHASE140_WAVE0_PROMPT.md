# PHASE140 · Wave 0 投喂提示词（自包含）

> 用法：把下面 `BEGIN PROMPT` / `END PROMPT` 之间的内容**整段复制**投喂给执行方（Kilo Code / GLM / 其他编码器）。
> 创建日期：2026-10-30

---

BEGIN PROMPT

你在一个已完成研发的开源项目上执行一个**非常明确、范围严格受限**的工程批次。请严格按本提示词执行，不要扩大范围。

## 一、背景（你需要知道的全部上下文）

仓库路径：`/home/who/multistack-project`
项目名：DBCool（一个 NocoBase 风格的可视化低代码平台，三栈：Java 21 + Spring Boot 3.4 / Python 3.12 FastAPI / React 18 + Vite 5，PostgreSQL + Redis + MinIO + RabbitMQ）。

当前状态：功能研发已终止，v2.0.0 已发布（后端 2575 个测试 0 失败、JaCoCo BUNDLE 覆盖率 0.7982、Playwright E2E 159/159）。项目方向已收窄为「项目展示 + 私有化部署 + 企业支持」，**不再新增任何业务功能**。

本批次是产品化路线的 **Wave 0（地基收口）**，目标是让「版本号」和「文档里的数字」变得可信 —— 这是后续做私有化交付包（镜像 tag、Release 自动化、离线部署包）的前置条件。如果版本号三处不一致，后面所有镜像 tag 和 Release 都是沙上建塔。

你的工作量约 3 人日，包含三个任务：T0.1 版本单一真相源、T0.2 文档数字基线化、T0.3 清红灯。

## 二、全局红线（违反即判定不通过，会被打回）

1. **禁止弱化断言让测试变绿**。不许删测试、加 `.skip` / `.todo` / `xit`、放宽断言容差、把断言改成只 log 不判断。测试失败必须修根因。
2. **禁止恒真 PASS**。你新增的任何校验脚本必须真实可失败，并且你必须做「反向自检」：人为制造失败场景，证明脚本真的会非 0 退出，并把输出贴进报告。
3. **禁止编造数字**。文档里每个数字必须来自你自己跑出来的命令输出，并就近标注 `实测于 YYYY-MM-DD @<commit短哈希>`。
4. **不新增业务功能**。本批次只做：版本号同步、文档数字统一、两个失败测试修复、漏洞库占位清理。不得新增端点、字段、模块、依赖。
5. **禁止 commit / push**。所有改动保持未提交状态。报告末尾写「commit & push：待你授权」。
6. **行号以实测为准**。本提示词给的行号基于 2026-10-30 核对，可能已漂移。执行时以你实际看到的文件为准；若漂移，自行定位并在报告中说明。
7. **不要谎报实测**。某项没有环境跑（例如 Playwright 需要浏览器），就在报告里明确写「未实测」，不要写「通过」。

## 三、开工前：确认基线

```bash
cd /home/who/multistack-project
git status --short | head -20
git log --oneline -1                      # 记下基线 commit，后面报告要用
cd backend-java && mvn -o verify 2>&1 | tail -5
cd ../frontend && npm run test:run 2>&1 | tail -8
```

**如果基线本身就不是全绿（且不是本批次引起的），立即停下来报告，不要顺手改别的东西。**

已知基线状况（供你比对）：后端 2575 测试 0 失败 0 错误；前端 `npm run test:run` 现有 **2 个既有失败**（`AttachmentPreview.test.tsx` 和 `BoardView.test.tsx`），这正是 T0.3 要修的。

---

## 四、T0.1　版本单一真相源

### 4.1 问题

同一个项目的版本号散落在多处且各不相同：

| 文件 | 当前值 | 说明 |
|---|---|---|
| `frontend/package.json` 的 `"version"` 字段 | `2.1.0` | 前端项目版本 |
| `backend-java/pom.xml` 的 project `<version>` | `2.0.0` | 注意：pom 里**第一个** `<version>` 是 parent（spring-boot-starter-parent 3.4.7），**不要动它**；要改的是 groupId `com.nocobase` / artifactId `nocobase-backend` 下的那个 |
| `backend-python/pyproject.toml` 的 `version` | `0.0.1` | |
| `backend-python/src/nocobase_py/__init__.py` 的 `__version__` | `0.0.1` | 被 health 路由等消费 |
| `backend-python/src/nocobase_py/config.py` 的 `app_version` | `0.0.1` | |
| `backend-java/src/main/java/com/nocobase/health/HealthController.java` | `body.put("version","0.0.1")` | 硬编码字符串 |
| `backend-java/src/main/java/com/nocobase/config/OpenApiConfig.java` | `.version("0.0.1")` | 硬编码字符串 |
| `contracts/errors.yaml` | `0.0.1` | 契约版本字段 |
| `contracts/schemas/common.schema.json` | `0.0.1` | 契约版本字段 |
| `README.md` 顶部版本号与徽章 | `v2.0.0` / `2.0.0` | |

**以下不要动**（它们是别的意思）：
- `NocoBaseApplication.java` 里 javadoc 的 `@since 0.0.1`（历史标记，不是运行时版本）
- `backend-python/src/nocobase_py/services/embedding_service.py` 里的 `version="1.0.0"`（第三方服务 API 版本）
- `tests/contract/pyproject.toml` 的版本（测试工程自身版本）
- `frontend/package.json` 里 dependencies/devDependencies 中的版本号（如 `clsx ^2.1.0`、`vitest ^2.1.0`）—— 这些是依赖版本，**只改** 顶层 `"version"` 字段

### 4.2 你要做的事

1. **新建 `/home/who/multistack-project/VERSION` 文件**，内容只有一行：`2.1.1`（纯文本，不要引号，末尾换行）。
   版本定为 `2.1.1` 的理由：CHANGELOG 里最新的语义化版本条目是 `v2.1.0`，而本批次是补丁级改动，按语义化版本做 patch bump。
2. 把上表 10 处全部同步为 `2.1.1`。
3. **顺带修一个真实 Bug**：`deploy.sh` 里有一处检查 `backend-java/target/nocobase-backend-0.0.1-SNAPSHOT.jar` 是否存在，但 pom 版本是 `2.0.0`，所以这个 jar 名永远不存在，每次部署都会白白触发一次 Maven 重建。先 `ls backend-java/target/*.jar` 看真实产物名，再把判定的文件名改成真实名（更好的做法是从 pom 版本推导，而不是写死）。
4. **新建 `scripts/version-consistency-verify.py`**：
   - 写法照抄同目录的 `scripts/java-dependency-audit.py` 的风格：顶部 docstring 写明退出码含义、路径常量用 `Path(__file__).parent.parent`、argparse 支持参数、输出用 `✅ / ⚠️ / ❌` 前缀 + 中文、`sys.exit(main())` 结尾。
   - 行为：读根目录 `VERSION`，解析并逐一比对上表各处版本（pom 要取 project 节点下的 `<version>`，不是 parent 的）。
   - 退出码：`0` = 全部一致；`1` = 发现不一致；`2` = 执行错误（VERSION 文件缺失、某个源文件缺失、解析失败、版本号不符合 `X.Y.Z` 语义化格式）。
   - 默认只校验；额外支持 `--fix` 参数，从 `VERSION` 写回各文件（幂等：写回后再校验应仍为 0）。
   - **关键**：源文件缺失时不得静默跳过，必须 exit 2（否则就是恒真门禁）。
5. **接入 CI**：在 `.github/workflows/ci.yml` 中新增一个 job `version-consistency`，放在 `build` job 之后、`contract-static` job 之前，风格照抄 `contract-static`（`runs-on: ubuntu-latest` + `actions/checkout@v4` + `actions/setup-python@v5` 指定 `python-version: "3.12"` + 一行 `run: python3 scripts/version-consistency-verify.py`）。

### 4.3 验收（三条反向自检缺一不可，输出必须贴进报告）

```bash
python3 scripts/version-consistency-verify.py                 # 期望 exit 0

# 自检 A：制造不一致，必须 exit 1
sed -i 's/"version": "2.1.1"/"version": "9.9.9"/' frontend/package.json
python3 scripts/version-consistency-verify.py; echo "exit=$?"
git checkout frontend/package.json

# 自检 B：删掉 VERSION，必须 exit 2
mv VERSION /tmp/VERSION.bak
python3 scripts/version-consistency-verify.py; echo "exit=$?"
mv /tmp/VERSION.bak VERSION

# 自检 C：--fix 幂等
python3 scripts/version-consistency-verify.py --fix
python3 scripts/version-consistency-verify.py                 # 仍应为 exit 0
```

**没有这三条自检的输出 = 这个任务没做完。**

---

## 五、T0.2　文档数字基线化

### 5.1 问题

`README.md` 里同一个指标有多套互相矛盾的数字（都是历史遗留，没人统一过）：

- 前端测试数：有 962、961、877 三种说法
- 后端测试数：有 2493、2277、2225 三种说法
- 覆盖率：有 77.67%、80.06%、83% 三种说法
- 测试总数：有 3455、3154 两种说法
- 测试文件数：有 118、109 两种说法

`BASELINE.md`、`RISK_REGISTER.md`、`TECH_DEBT.md` 里也有各自一套数字，与 README 不一致。

这直接伤害「项目展示」的可信度 —— 潜在用户看到自相矛盾的数字就不会信任这个项目。

### 5.2 你要做的事

1. **先自己跑一遍全套门禁，把真实输出记下来**（绝不能复用文档里的旧数字）：

```bash
cd backend-java && mvn -o verify 2>&1 | tail -20          # 记下 Tests run 数与 JaCoCo BUNDLE 覆盖率
cd ../frontend && npm run test:run 2>&1 | tail -10        # 记下 passed / failed / Test Files
cd frontend && npx tsc --noEmit
cd .. && python3 scripts/launch-final-verify.py
python3 scripts/smoke.py
python3 scripts/api-contract-verify.py
python3 scripts/backup-e2e-verify.py
python3 scripts/tenant-isolation-e2e-verify.py
cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q
```

2. 把实测值统一写进 `README.md`（所有出现测试数 / 覆盖率 / 文件数 / E2E 数量的地方）、`BASELINE.md`、`RISK_REGISTER.md`、`TECH_DEBT.md`。
3. 每个数字**就近标注** `实测于 YYYY-MM-DD @<commit短哈希>`。
4. 硬要求：**同一个指标在同一份 README 内只能有一个值**。

### 5.3 验收

```bash
grep -n '77\.\|83%\|80\.06\|2493\|2277\|2225\|3455\|3154\|961\|962\|877\|118\|109' README.md
# 期望：同一指标只剩一个数值，且附近有「实测于」标注
```

---

## 六、T0.3　清红灯

### 6.1 修两个既有失败的前端测试

**第一步必须先复现，把真实报错贴出来**（不要凭猜测改）：

```bash
cd frontend && npx vitest run src/components/attachment/AttachmentPreview.test.tsx
cd frontend && npx vitest run src/features/project/BoardView.test.tsx
```

定位线索（**只是线索，以你跑出来的实际报错为准**）：
- `AttachmentPreview.test.tsx` 失败的是 lightbox 相关用例：用 `getByRole('dialog')` 和 `getAllByRole('button', {name:/关闭/i})` 定位，最后断言 `expect(onClose).toHaveBeenCalled()`。组件 `AttachmentLightbox.tsx` 里 Dialog 的 `aria-label` 用的是国际化 key `common.close`（中文「关闭」），且组件里存在**两处**「关闭」按钮。怀疑点是同名按钮导致 `getAllByRole` 命中数量或顺序不符，或者错误分支的渲染差异。
- `BoardView.test.tsx` 失败的是「取消 prompt 时不调用创建接口」用例：断言发往 `/project-boards/tasks` 的请求数为 0。实现里 `handleAddCard` 先弹 `prompt()` 拿标题，为空则 return。怀疑点是 jsdom 下 `window.prompt` 没有被 stub（默认返回 undefined），或者 mock 拦截的请求路径与实际请求路径不一致。

修复要求：
- 修根因（定位方式 / mock / 组件逻辑），**绝不允许**删用例、加 skip、改断言让它恒真。
- 如果你确认这是**产品代码的真实缺陷**（例如 prompt 返回 null 时没有正确 return），那就修产品代码，并在报告里说明根因和改动点。
- 前端测试框架配置在 `frontend/vite.config.ts` 里（没有独立的 vitest.config.ts），环境是 jsdom，setup 文件是 `src/test-setup.ts`。注意中文文案都在 `src/i18n/locales/zh-CN.json`。

### 6.2 清理漏洞库里的占位假条目

- 打开 `config/known-vulnerabilities.json`，里面第一条是一个**占位条目** `CVE-2024-0000-EXAMPLE`（group `org.example` / artifact `does-not-exist`），描述里自己写着"占位条目"。**整条删除**。删完应剩 5 条真实 CVE。
- **补一个恒真漏洞**：`scripts/java-dependency-audit.py` 里加载漏洞库的函数目前只校验「文件存在」和「内容是数组」，**不校验非空** —— 也就是说如果把 JSON 改成 `[]`，扫描器会静默 PASS，这是不合规的门禁。请新增：条目数为 0 时 `sys.exit(2)`。
- 同步更新 `SECURITY.md` 里描述漏洞库条目数的那句话（原文大意是当前包含 6 条，5 条真实 CVE + 1 条占位）。

### 6.3 验收

```bash
cd frontend && npm run test:run 2>&1 | tail -8     # 期望 0 failed
cd frontend && npx tsc --noEmit                    # 期望 0 errors

# 空库自检：必须 exit 2
cp config/known-vulnerabilities.json /tmp/kv.bak
echo '[]' > config/known-vulnerabilities.json
python3 scripts/java-dependency-audit.py; echo "exit=$?"
cp /tmp/kv.bak config/known-vulnerabilities.json
python3 scripts/java-dependency-audit.py; echo "exit=$?"   # 恢复后应正常
```

---

## 七、交付前必须全绿（逐条贴真实输出）

| # | 命令 | 期望 |
|---|---|---|
| 1 | `cd backend-java && mvn -o verify` | 0 Failures / 0 Errors，无 `Rule violated` |
| 2 | `cd frontend && npm run test:run` | **0 failed** |
| 3 | `cd frontend && npx tsc --noEmit` | 0 errors |
| 4 | `cd frontend && npx eslint src` | 0 errors |
| 5 | `cd frontend && npx playwright test` | 159/159（环境不具备就写「未实测」） |
| 6 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 无 FAIL |
| 7 | `python3 scripts/version-consistency-verify.py` | exit 0 + 三条反向自检输出 |
| 8 | `python3 scripts/api-contract-verify.py` | 0 漂移（**你改了 contracts 目录，这条必跑**） |
| 9 | `python3 scripts/launch-final-verify.py` | 8/8 |
| 10 | `python3 scripts/smoke.py` | 11/11 |
| 11 | `python3 scripts/java-dependency-audit.py` | 无 HIGH/CRITICAL + 空库自检 exit 2 |

另外：`CHANGELOG.md` 顶部新增一个 `## v2.1.1 (日期)` 条目，把当前所有 `## Unreleased —` 的内容归入其下。

## 八、报告要求

1. 逐任务给 ✅/❌，每条附**命令输出原文**（不要只写"已完成"）。
2. 每个改动写明 `文件:行号`；行号与提示词不符时说明漂移情况。
3. 明确列出所有「未实测」项。
4. 报告末尾写：`commit & push：待你授权`。
5. 如果有任何门禁 FAIL，写清是哪一项、根因、是否已修。**不要把状态自行改成"✅ 已完成"** —— 判定权在项目所有者手上，谎报会被直接打回。

END PROMPT

---

## 附：审计时核对要点（给审计方，不投喂）

| 任务 | 必查证据 |
|---|---|
| T0.1 | `VERSION` 存在；10 处版本号用 grep 逐一确认；`version-consistency-verify.py` 的三条反向自检输出（缺一即不通过）；CI job 真的存在于 `ci.yml` |
| T0.2 | README 内同一指标唯一值；数字旁有「实测于 @commit」；**抽查 1-2 个数字当场复跑命令验证** |
| T0.3 | 两个测试的**修复 diff**（是修根因还是弱化断言，看 diff 一眼便知）；`known-vulnerabilities.json` 剩 5 条；空库自检 exit 2 |
| 通用 | `git status` 确认改动文件与报告申报一致；确认**没有** commit |

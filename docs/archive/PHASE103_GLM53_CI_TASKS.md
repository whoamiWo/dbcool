# PHASE103 任务书：CI 门禁硬化（🔒-SaaS-P1）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §4（CI 门禁硬化）
> 一句话：**现在的 CI 是"看起来在跑，实际拦不住东西"** —— 覆盖率红线不生效、静态检查失败被 `|| true` 吞掉、无安全扫描。

---

## §1 为什么做这个（三条理由）

### 1.1 覆盖率红线在主 CI 完全不生效

`backend-java/pom.xml:262-457` 配了 JaCoCo `check`（BUNDLE ≥82%，audit 97%、view 97% 等），绑定在 `verify` 阶段；
但 `.github/workflows/ci.yml:44-46` **只跑 `mvn test`** → 红线永远不会被触发。

### 1.2 静态检查的失败被显式吞掉

```
Makefile:108   mvn checkstyle:check -q || true
Makefile:115   mvn spotless:apply || true
```
`|| true` 的意思是"失败也算通过"。这不是门禁，这是装饰。

### 1.3 完全没有安全扫描

`.github/workflows/` 只有 `ci.yml` 与 `backend-ci.yml`，**无 CodeQL / Trivy / OWASP dependency-check / npm audit**。
对外 SaaS 的客户安全问卷几乎必问"是否有依赖漏洞扫描与 SAST"。

## §2 现状（实测）

| 项 | 现状 |
|---|---|
| 主 CI 命令 | ❌ `ci.yml:44-46` 只跑 `mvn test`，不跑 `mvn verify` |
| JaCoCo 红线 | ⚠️ 已配（BUNDLE ≥82%），但**主 CI 不触发** |
| 静态检查 | ❌ `Makefile:108,115` 用 `\|\| true` 吞掉失败 |
| 安全扫描 | ❌ 无任何 SAST / 依赖漏洞扫描 |
| 冒烟 | ⚠️ `make smoke` → `scripts/smoke.py` 存在，**CI 中无对应 job** |
| 新增验证脚本 | ⚠️ PHASE95~101 产出的脚本尚未纳入 CI |
| 部署物可追溯 | ❌ 无镜像 build/push/扫描流水线，无 tag/digest 锁定 |
| 并发控制 | ✅ `backend-ci.yml:17-19` 有并发取消 |
| 契约集成测试 | ✅ `ci.yml:240-243,311-313` 存在且 `needs: [java-test]` |

## §3 七项任务

### T1（P0）主 CI 改跑 `mvn verify`，让覆盖率红线真正生效

- `ci.yml` 的 Java job 改为 `mvn -o verify`（或至少在 PR 上跑 verify）
- 若耗时过长，可拆为"PR 跑 test + main 跑 verify"，但**合并到 main 前必须 verify 通过**
- 验收：故意把覆盖率降到 82% 以下（临时加一个无测试的类），CI 必须**失败**

### T2（P0）移除所有 `|| true`

- `Makefile:108,115` 及全仓其他把失败吞掉的写法（grep `|| true`、`|| echo`、`2>/dev/null &&`）
- 改为真正失败即退出；确实需要"允许失败"的地方必须**显式注释说明理由**并出现在交付清单

```bash
grep -rn "|| true" Makefile .github/workflows/ scripts/ | wc -l   # 期望 0（或逐条登记理由）
```

### T3（P0）引入依赖漏洞与 SAST 扫描

- 依赖漏洞：Java（OWASP dependency-check 或 Trivy）、前端（`npm audit`）、Python（pip-audit 或 Trivy）
- SAST：优先 CodeQL（Java + TS）；离线环境下至少要有可运行的替代方案并说明
- **阈值策略**：先"只报告不阻断"跑一轮拿到基线，再设定阻断阈值（高危必阻断），并写进交付

### T4（P0）冒烟与新增验证脚本纳入 CI

把以下脚本作为 CI job（或合并进一个 `verify-all` job）：
- `python3 scripts/smoke.py`
- `python3 scripts/api-contract-verify.py`（PHASE97）
- `python3 scripts/tenant-isolation-e2e-verify.py`（PHASE95）
- `python3 scripts/no-fake-impl-verify.py`（PHASE96）
- `python3 scripts/saas-readiness-verify.py`（PHASE99）
- `python3 scripts/backup-e2e-verify.py`（PHASE101，耗时较长可设单独 job / 定时跑）

> 注意：需要运行环境的脚本（依赖 docker）在 CI 中要标注 runner 要求；GitHub 托管 runner 默认有 docker。

### T5（P1）构建产物可追溯

- 镜像 build + push 流水线，tag 用 commit sha（**不用浮动 latest**）
- 镜像扫描（Trivy）在 push 前
- 部署记录 tag/digest（与 PHASE100 T8 的回滚能力对接）

### T6（P1）门禁文档化

- 新增/更新 `docs/CI_GATES.md`：每条门禁的作用、命令、阈值、失败怎么办
- 与 `PHASE95_103_INDEX.md` 的门禁表保持一致

### T7（P1）路径触发范围修正

`backend-ci.yml:5-14` 的 `paths` 只含 `backend-java/**` → Python/前端改动不触发后端门禁。
确认这是有意为之还是疏漏；若改了共享契约（如 `contracts/`），必须触发全量。

## §4 门禁基线（本批"改门禁"本身的验收）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd backend-java && mvn -o verify` | **通过且 JaCoCo BUNDLE ≥ 82%** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `grep -rn "\|\| true" Makefile .github/workflows/ scripts/` | 0（或逐条登记） |
| 所有新增验证脚本 | CI 中可运行 |

## §5 红线（违反即打回）

1. **禁止用 `|| true` / `|| echo` 吞掉失败** —— 这是本批要清的东西，自己不许再引入
2. **禁止"只报告不阻断"永久化** —— 安全扫描可以分阶段，但必须给出阻断阈值与时间表
3. **禁止为了跑绿放宽红线** —— 覆盖率若确实达不到 82%，要说明是哪个模块、为什么、补测计划；不许改低阈值了事
4. **禁止恒真断言** —— 每条验收必须能失败（"故意降覆盖率 CI 必须失败"）
5. **禁止把耗时长的 job 直接删掉** —— 移出 PR 触发可以，但要改为定时/合并前触发
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 CI 改为 verify 后的**实际运行链接/日志** + "故意降覆盖率导致失败"的证据
2. T2 `grep "|| true"` 计数为 0（或逐条理由）
3. T3 安全扫描第一轮基线报告（漏洞数、高危数）+ 阻断阈值设定
4. T4 各验证脚本在 CI 中的 job 定义与一次成功运行记录
5. T5 镜像 tag 策略与一次构建记录
6. T6 `docs/CI_GATES.md`
7. T7 路径触发范围的结论（有意 or 修正）
8. 门禁实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 现有 workflow：`.github/workflows/ci.yml`、`backend-ci.yml`
- JaCoCo 配置：`backend-java/pom.xml:262-457`
- 本地门禁：`Makefile`、`opt-multistack.sh`（后者是给 agent 用的，不能替代 CI）
- 验证脚本：`scripts/smoke.py` 及各 PHASE 产出的 `*-verify.py`

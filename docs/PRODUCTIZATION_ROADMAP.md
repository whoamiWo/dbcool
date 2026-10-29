# 产品化路线图（收窄方向：项目展示 + 私有化部署 + 企业支持）

> 创建日期：2026-10-30
> 决策人：用户（项目所有者）
> 前置：`docs/TERMINATION_REPORT.md`（v2.0.0 已发布）、`ROADMAP.md`（研发阶段路线，已终止）
> 状态：**已批准，Wave 0 可开工**

---

## 一、方向定义

**收窄**：不再新增业务功能。把已建成的 v2.0.0 能力包装成「可信、可装、可托付」的产品。

三条线各自的成功标准：

| 线 | 成功标准 |
|---|---|
| 项目展示 | 陌生访客 3 分钟内能答出：这是什么 / 能干什么 / 怎么装 / 找谁 |
| 私有化部署 | 陌生人（不看源码、不联系作者）在干净 4C8G Ubuntu 上 **30 分钟内**完成部署并跑通核心链路 |
| 企业支持 | 任一企业用户遇到问题时有明确路径：渠道 → 响应时限 → 升级路径 |

### 北极星指标

> **陌生人 30 分钟部署成功**（可计时、可复现、不可自证）

所有 Wave 的取舍以此为准：不能让它变快的工作，往后排。

---

## 二、现状缺口（2026-10-30 实测核实，证据到行号）

代码本身已达标：Java 2575 测试 0F/0E、BUNDLE 覆盖率 0.7982、E2E 159/159。缺口全在**交付侧**。

### 2.1 版本与发布

| 问题 | 证据 |
|---|---|
| 版本号三处不一致 | `frontend/package.json:4`=2.1.0 / `backend-java/pom.xml:16`=2.0.0 / `backend-python/pyproject.toml:3`=0.0.1 |
| 无 Release / 镜像推送 / tag 触发流程 | `.github/workflows/` 仅 `backend-ci.yml`、`ci.yml` 两个 |
| CHANGELOG 全手写，无版本条目规范 | `CHANGELOG.md:1-232` 几乎全是 `## Unreleased`，语义化条目仅 `:234 v2.1.0`、`:291 v2.0.0` |

### 2.2 私有化部署

| 问题 | 证据 |
|---|---|
| 4 个应用镜像全是 `build:`，**无 tag 无 registry** | `docker-compose.yml:152,269,360,399` |
| 基础镜像未锁版本 | `docker-compose.yml:82` `minio/minio:latest` |
| **无离线/内网部署能力** | `docker save` / `离线` / `airgap` 全仓 0 命中 |
| 安装器只支持 Ubuntu/Debian | `install.sh:39-41` |
| K8s 为 13 个裸 yaml，Secret 是 `CHANGE_ME`，**无 Helm chart** | `k8s/README.md:24-31`、`k8s/*.yaml`；全仓 `Chart.yaml` 0 命中 |
| **无跨版本升级脚本** | Flyway 已到 `V74__billing_system.sql`，无 upgrade 脚本/演练 |

### 2.3 项目展示

| 问题 | 证据 |
|---|---|
| 无官网 / 文档站 | docusaurus / vitepress / mkdocs / website 全仓 0 命中 |
| `docs/` 222 篇 md **无导航页** | `docs/` 顶层无 index/README |
| 只有 10 张手机截图，**无 GIF / 视频** | `docs/mobile-screenshots/` |
| README 数字自相矛盾 | 测试总数 6 套（`:16` 3455 / `:315` 3154 / `:132` 2493 / `:150` 961 / `:325` 2277+877）；覆盖率 `:147` 77.67% vs `:316` 83% |
| 无演示数据种子 | 仅 `V47__collection_acl_seed.sql` 是权限种子，无业务演示数据 |

### 2.4 企业支持

| 问题 | 证据 |
|---|---|
| 无 SUPPORT / SLA / 支持分级 / 联系方式 / 商业授权 | 全仓 0 命中 |
| `NOTICE` 明确写「不提供商业支持、担保或赔偿」 | `NOTICE:7-9` —— 与本方向直接冲突 |
| 无 issue / PR 模板，无 CONTRIBUTING | `.github/` 仅 workflows + modernize |
| `SECURITY.md` 只有依赖扫描，无漏洞披露流程 | `SECURITY.md` 全篇无披露/联系方式/Supported Versions |

---

## 三、维护模式边界（2026-10-30 修订，用户批准）

原约定（2026-10-29）：只修 Bug 与安全更新，不做新功能/重构/优化。

**新增允许范围（本次批准）**：

| 允许 | 禁止 |
|---|---|
| 版本号一致性、镜像 tag 与分发、Release 自动化 | 新增业务功能 / 新端点 / 新模块 |
| 离线部署包、安装器发行版扩展、Helm chart、升级回滚脚本 | 架构重构、性能优化（除非严重回归） |
| 官网 / 文档站 / 截图 / 演示数据种子（默认关闭） | 为展示改产品行为 |
| SUPPORT / SLA / 授权 / 商标 / 安全披露 / issue 模板 | 技术债清理（除非影响可交付性） |
| 文档数字校准与门禁基线刷新 | — |

**判定规则**：任务若开始触碰业务行为代码 → 停下来确认是否破例。

---

## 四、Wave 划分

总计约 **31–45 人日**（1 人 7–9 周；有外部编码器可并行压缩至 4–5 周）。

```
Wave 0  地基收口      ~3 人日   [下一步开工]
Wave 1  私有化交付包  ~20 人日  [最高优先，最大块]
Wave 2  项目展示      ~12 人日  [可与 Wave1 后段并行]
Wave 3  企业支持      ~10 人日
Wave 4  持续节奏      长期
```

---

### Wave 0 · 地基收口（~3 人日）——让版本和数字可信

不先做完，Wave 1 的镜像 tag 和 Release 全部是沙上建塔。

| # | 任务 | 落点 | 验收 | 人日 |
|---|---|---|---|---|
| T0.1 | 版本单一真相源 | 新增根目录 `VERSION`；三处改为读取它；新增 `scripts/version-consistency-verify.py` 接入 `ci.yml` | 三处一致；人为改一处不同 → exit 1 | 1 |
| T0.2 | 门禁数字基线化 | 全量跑门禁（见 §6），统一 README / `BASELINE.md` / `RISK_REGISTER.md` 数字，全部标注 `实测于 <date> @<commit>` | README 内同一指标只有一个数 | 1 |
| T0.3 | 清红灯 | 修 `frontend/src/components/attachment/AttachmentPreview.test.tsx`、`frontend/src/features/project/BoardView.test.tsx`；清 `config/known-vulnerabilities.json` 的 `CVE-2024-0000-EXAMPLE` 占位 | 前端 0 failed；漏洞库无 EXAMPLE 条目 | 1 |

**DoD**：打出 **v2.1.1**，README 每个数字可当场复现。

---

### Wave 1 · 私有化交付包（~20 人日）——最高优先

| # | 任务 | 落点 | 验收 | 人日 |
|---|---|---|---|---|
| T1.1 | 镜像版本化 + 多架构 | `scripts/build-images.sh`（buildx `linux/amd64,linux/arm64`，tag `ghcr.io/whoamiWo/dbcool-<svc>:<ver>`）；基础镜像锁版本（minio 弃 `latest`） | 双架构构建成功；`docker-compose.yml` 全部 image 带固定 tag | 3 |
| T1.2 | Release 自动化 | tag `vX.Y.Z` → 构建 → 推镜像 → 从 CHANGELOG 抽取 Release Notes → 产出 `checksums.txt`；扩展 `scripts/freeze-demo.sh` 现有 tag 逻辑（`:50`） | push tag 后产物齐全可下载 | 3 |
| T1.3 | **离线部署包**（命门） | `packaging/offline/`：`images.tar`（`docker save`）+ 改 `image:` 不 build 的 compose + `.env.example` + `install-offline.sh` | **断网 VM** 上 `./install-offline.sh` 能起且冒烟通过 | 5 |
| T1.4 | 安装器扩发行版 | `install.sh` 增 RHEL/CentOS/Rocky/麒麟分支；不支持则明确报错 + 输出支持矩阵 | Rocky 9 上跑通 | 3 |
| T1.5 | Helm chart | `charts/dbcool/`（values 参数化、Secret 必填校验、ingress/存储/资源可选） | kind/minikube `helm install` 起来；**无实测环境必须标 beta** | 4 |
| T1.6 | 升级与回滚 | `scripts/upgrade.sh`（备份 → 换镜像 → Flyway → 冒烟 → 失败自动 rollback）+ `docs/upgrade-runbook.md` | 用 v2.0.0 备份恢复到 v2.1.x 成功，冒烟通过 | 2 |

**DoD**：新写 `docs/quickstart-private.md`，找一台干净 VM **实测计时 ≤ 30 分钟**（含登录 + 6 条冒烟链路）。

---

### Wave 2 · 项目展示（~12 人日，可与 Wave1 后段并行）

| # | 任务 | 落点 | 验收 | 人日 |
|---|---|---|---|---|
| T2.1 | README 重写为产品页 | 定位 → 能力矩阵 → 30 秒上手 → 截图 → 部署方式 → 授权与支持 → 路线图；PHASE 流水账移入 `docs/` | 首页不再出现 PHASE 编号 | 2 |
| T2.2 | 文档站 | Vitepress `docs-site/`，挑 8–10 篇 runbook 建导航（部署/运维/升级/备份/权限/API），GitHub Pages 发布 | Pages 可访问、导航无死链 | 3 |
| T2.3 | 视觉素材 | 6–8 张核心界面截图 + 2–3 段 30s GIF（建表→视图→录数／工作流／IM） | 放 `docs-site/public/screenshots/` | 3 |
| T2.4 | 演示数据种子 | `scripts/seed-demo-data.py`（collection + 记录 + 视图 + 一条工作流） | **默认关闭**，仅 `--seed-demo` 或 `DEMO_SEED=true` 生效 | 2 |
| T2.5 | 在线 Demo（可选） | 2C4G 云主机跑 `deploy.sh demo`，只读账号 + 每日重置 | 有成本预算再上 | 2 |

**DoD**：陌生访客 3 分钟内答出「是什么／能干什么／怎么装／找谁」。

---

### Wave 3 · 企业支持（~10 人日）

| # | 任务 | 落点 | 验收 | 人日 |
|---|---|---|---|---|
| T3.1 | 授权分层 | **Apache-2.0 核心 + 支持订阅 / 企业版附加** 双轨；改 `NOTICE:7-9` 去掉「不提供商业支持」绝对表述；新增 `TRADEMARK.md` | 授权表述自洽，不与 Apache-2.0 冲突 | 2 |
| T3.2 | 支持体系 | `SUPPORT.md`（渠道 / L1-L3 分级 / 响应时限）、`docs/sla.md`（含「不含什么」）、`SECURITY.md` 补漏洞披露章节 + Supported Versions 表 | 三份文档存在且承诺可兑现 | 3 |
| T3.3 | 协作模板 | `.github/ISSUE_TEMPLATE/`（bug / 部署求助 / 安全）、PR 模板、对外版 `CONTRIBUTING.md`（从 `COLLABORATION.md` 拆出） | GitHub 新建 issue 出现模板选择 | 2 |
| T3.4 | 客户入职包 | `docs/enterprise-onboarding.md` + `docs/acceptance-checklist.md`（复用 `scripts/launch-final-verify.py` 的 8 道门禁 + 6 条冒烟） | 客户照单能自行验收 | 3 |

**DoD**：企业用户有明确路径：渠道 → 响应时限 → 升级路径；有版本支持政策。

---

### Wave 4 · 持续节奏（长期）

| 周期 | 动作 | 入口 |
|---|---|---|
| 每月 | 依赖安全更新（`scripts/java-dependency-audit.py` + `npm audit --audit-level=high`）+ 全量门禁复跑 | 收敛为 `scripts/monthly-gate.py` 一条命令 |
| 每季 | 离线包重出、升级演练一次、文档站内容校准、路线回顾 | Wave 1 产物回归 |
| 每半年 | 旧版本 EOL 公告 | `docs/sla.md` 的 Supported Versions |

**迭代机制**：每个 Wave 建一个 GitHub Milestone + `WAVE<n>_CHECKLIST.md`；**DoD 未达成不进入下一 Wave**。

---

## 五、红线（踩了前功尽弃）

1. **Apache-2.0 不可撤销**：已发布代码不能改闭源。商业化只能走「支持订阅 + 企业版附加组件」，**不得**把 LICENSE 改成收费许可。
2. **演示数据不得污染生产**：`seed` 必须显式开关，默认关闭。
3. **镜像必须固定 tag**：`minio:latest` 是离线包可复现性的定时炸弹。
4. **不许谎报实测**：Helm / 麒麟 / ARM64 若无可测环境，一律标注 `beta（未实测）`，并在报告中写明「未实测」——沿用既有审计规矩（判定权在用户，证据到 `文件:行号`）。
5. **不新增业务功能**：任务一旦开始改业务行为代码，停下来确认是否破例。
6. **文档数字必须可复现**：任何写入 README / 官网的数字都要带 `实测于 <date> @<commit>`。

---

## 六、门禁清单（每个 Wave 的 DoD 都要跑）

| 门禁 | 命令 | 基线 |
|---|---|---|
| 后端 | `cd backend-java && mvn -o verify` | 2575 run / 0F / 0E，JaCoCo BUNDLE ≥ 0.78 |
| 前端单测 | `cd frontend && npm run test:run` | 0 failed（Wave0 后） |
| 类型检查 | `cd frontend && npx tsc --noEmit` | 0 errors |
| E2E | `cd frontend && npx playwright test` | 159/159 |
| Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 61 passed / 1 skipped |
| 上线门禁 | `python3 scripts/launch-final-verify.py` | 8/8 |
| 备份演练 | `python3 scripts/backup-e2e-verify.py` | 29/29 |
| 冒烟 | `python3 scripts/smoke.py` | 11/11 |
| 租户隔离 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS |
| 契约 | `python3 scripts/api-contract-verify.py` | 0 漂移 |
| 启动检查 | `python3 scripts/startup-checklist.py` | 全 PASS |

---

## 七、Wave 0 立即执行清单

```bash
# T0.1 版本真相源
grep -n '"version"' frontend/package.json
grep -n '<version>' backend-java/pom.xml | head -3
grep -n '^version' backend-python/pyproject.toml

# T0.2 门禁实测基线（逐项记录输出，用于统一文档数字）
cd backend-java && mvn -o verify 2>&1 | tail -5
cd ../frontend && npm run test:run 2>&1 | tail -8
python3 scripts/launch-final-verify.py

# T0.3 红灯
cd frontend && npx vitest run src/components/attachment/AttachmentPreview.test.tsx \
                          src/features/project/BoardView.test.tsx
```

---

## 八、变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-30 | 初版创建，用户批准方向收窄与维护模式边界扩展；Wave 0 开工 |

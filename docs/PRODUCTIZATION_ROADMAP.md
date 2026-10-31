# 产品化路线图 v2 — 收窄方向：项目展示 + 私有化部署 + 企业支持

> **版本**：v2（2026-10-30 修订，取代 v1 同名文件）
> **决策人**：用户（项目所有者）　**状态**：待执行，Wave 0 收尾中
> **本文是唯一权威实施路线图**。其他文档（`docs/PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md`、
> `PHASE140_WAVE0_TASKS.md`）为**输入与证据**，不再是并行计划。
> **前置证据**：`docs/PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md`（七强对标实证，2026-10-30 @02b3a59）
> **维护模式边界**：见 §3（2026-10-30 用户批准版）

---

## 一、方向与北极星

**收窄**：不再新增业务功能。把已建成的 v2.x 能力包装成「可信、可装、可托付」的产品。

| 线 | 成功标准（可判定） |
|---|---|
| 项目展示 | 陌生访客 3 分钟内能答出：这是什么 / 能干什么 / 怎么装 / 找谁 |
| 私有化部署 | 陌生人（不看源码、不联系作者）在干净 4C8G Ubuntu 上 **30 分钟内**部署并跑通核心链路 |
| 企业支持 | 任一企业用户遇到问题有明确路径：渠道 → 响应时限 → 升级路径 |

**北极星指标**：**陌生人 30 分钟部署成功**（可计时、可复现、不可自证）。
所有取舍以此为准：不能让它变快的工作一律后排。

**当前阶段定位**：功能研发已终止、进入**收窄迭代期**——主线是「打通 + 交付」，副线是「持续修补直到可用」。

---

## 二、现状快照（2026-10-30 实测，基线 `02b3a59`）

### 2.1 门禁基线（本次实跑）

> 下表为 Wave 0.5 开工前的实测基线；**Wave 0.5 后复测**（同日）：Java 2604 run / 0F / 0E（BUNDLE LINE 80.16），前端 118 files / 964 passed / 0 failed / 0 unhandled errors，行覆盖率 77.12%；冒烟 13/13、租户隔离 16/16。

| 项 | 命令 | 实测结果 |
|---|---|---|
| 后端 | `cd backend-java && mvn -o verify` | `Tests run: 2585, Failures: 0, Errors: 0, Skipped: 0`；JaCoCo BUNDLE **line 0.8006**（红线 0.79）；BUILD SUCCESS |
| 前端单测 | `cd frontend && npm run test:run` | `118 files / 962 passed / 0 failed`；⚠️ `Errors 5 errors`（unhandled，见 W0-T3） |
| 类型检查 | `npx tsc --noEmit` | 0 errors |
| Python | `backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 68 passed / 1 skipped |
| 版本一致性 | `python3 scripts/version-consistency-verify.py` | 10/10 一致 = 2.1.1，exit 0；自检 A/B/C → exit 1 / 2 / 0 |
| 依赖漏洞 | `python3 scripts/java-dependency-audit.py` | 阻断级 0；1 条 MEDIUM（logback-core 1.5.18 / CVE-2023-6481，1.5.x 已移除 receiver 功能，实际不受影响） |
| E2E | `npx playwright test` | **未实测**（本批次未执行浏览器用例） |

### 2.2 能力侧结论（摘要，详见对标文档）

- ✅ **广度整合目标已基本达成**：数据引擎、Wiki、IM、看板/项目、工作流、AI、钉钉/企微/飞书/微信嵌入、
  外部数据源、计费、多租户、可观测性 **均已真实接线**；旧报告所列 16 条「缺失/差距」已被代码实证推翻。
- 🔴 **但存在 6 项 P0 断链**：能力建好了却接不通（自动保存 401、协同初始化缺失、Pin/阅后即焚/Checklist 路径断链、日历翻月与过滤失效）。
- 🟡 **P1 企业加固 14 项**：凭证托管、配置三处补齐、集成市场可达、混合检索接前端、工作流 webhook 与重试、@提及接真、PITR、限流、钉钉同步分页与审批闭环、移动端、灰度、外部数据源类型扩展。

### 2.3 交付侧缺口

版本与发布（无 Release/镜像推送/tag 流程、CHANGELOG 无版本条目）、私有化部署（镜像无 tag、基础镜像未锁版本、
**无离线包**、安装器仅 Ubuntu/Debian、k8s 裸 yaml 无 Helm、**无跨版本升级脚本**）、项目展示（无官网/文档站、
docs/ 无导航、无 GIF/视频、无演示数据种子、README 数字曾自相矛盾）、企业支持（无 SUPPORT/SLA/授权分层/
issue 模板/SECURITY 披露流程，`NOTICE` 表述与新方向冲突）。

---

## 三、维护模式边界（2026-10-30 修订，用户批准）

原约定（2026-10-29）：只修 Bug 与安全更新。**本次扩展**允许交付化工作。

| 允许 | 禁止 |
|---|---|
| 版本号一致性、镜像 tag 与分发、Release 自动化 | 新增业务功能 / 新端点 / 新模块 |
| 离线部署包、安装器发行版扩展、Helm chart、升级回滚脚本 | 架构重构、性能优化（除非严重回归） |
| 官网 / 文档站 / 截图 / 演示数据种子（默认关闭） | 为展示改产品行为 |
| SUPPORT / SLA / 授权 / 商标 / 安全披露 / issue 模板 | 技术债清理（除非影响可交付性） |
| 文档数字校准与门禁基线刷新 | **新增字段类型等"能力扩容"（转入客户承诺驱动的可选项，见 §7）** |

**判定规则**：任务一旦触碰业务行为代码 → 停下来确认是否破例。
**例外通道（能力扩容白名单）**：仅当①已有客户明确书面需求，②单项工作 ≤2 人日，③不改变既有数据模型语义，
④需用户逐条书面批准。四条缺一不得开工。

---

## 四、总路线（一页速览）

```
Wave 0    地基收口（收尾）        ~1 人日   [进行中]
Wave 0.5  能力断链修复（Wave A）  ~8-12 人日 [最高优先·下一步]
Wave 1    私有化交付包            ~20 人日
Wave 2    项目展示                ~12 人日  [可与 Wave1 后段并行]
Wave 3    企业支持                ~10 人日
Wave 4    持续迭代节奏            长期
────────────────────────────────────────────────
合计 ≈ 51-65 人日（1 人 11-14 周；外部编码器并行可压至 6-8 周）
```

**顺序不可颠倒的理由**：Wave 0.5 在 Wave 1 之前，因为「自动保存 401」这类断链会让**演示和试用直接失败**，
而 Wave 1 的产物（镜像/离线包/文档）都要靠演示与试用兑现价值；Wave 0.5 未完成前不做 Wave 1 打包。

---

## 五、各 Wave 任务表

### Wave 0 · 地基收口（~1 人日，收尾）——让版本和数字可信

| # | 任务 | 落点 | 验收 | 状态 |
|---|---|---|---|---|
| W0-T1 | 版本单一真相源 | 根 `VERSION`；10 处版本号；`scripts/version-consistency-verify.py`（含 `--fix`）；`ci.yml` job `version-consistency` | exit 0 + 三条反向自检（1/2/0） | ✅ 已完成（实测通过） |
| W0-T2 | 门禁数字基线化 | `README.md` / `BASELINE.md` / `RISK_REGISTER.md` / `TECH_DEBT.md` | 同一指标唯一值 + 「实测于 2026-10-30 @02b3a59」 | ✅ 已统一（Java 2588、前端 964/118 files、BUNDLE 0.8007、前端行覆盖 77.12%、Python 68/1、冒烟 13/13、租户隔离 16/16） |
| W0-T3 | 清红灯 | 前端 5 个 unhandled errors（ErDiagram.test.tsx 等） | `npm run test:run` → 0 failed **且 0 errors** | ✅ 已修（Wave 0.5 A-7：产品代码空值保护，根因为 `AlertCenter` quota 字段与 `ErDiagram` stats 缺失） |
| W0-T4 | 版本条目 | `CHANGELOG.md` 顶部 4 段 `## Unreleased` 归入 `## v2.1.1` | CHANGELOG 有唯一版本条目 | ✅ 已归并（v2.1.1 含 Wave 0 + Wave 0.5 摘要与实测数字） |

**DoD**：①README/BASELINE/RISK/TECH_DEBT 四份文档同一指标只有一个值且带实测标注；②前端 0 failed 0 errors；
③CHANGELOG 有 `v2.1.1` 条目；④`version-consistency-verify.py` exit 0。

---

### Wave 0.5 · 能力断链修复（~8–12 人日）——最高优先，先把已建能力打通

> 任务书：[`docs/WAVE_A_CAPABILITY_FIX_TASKS.md`](./docs/WAVE_A_CAPABILITY_FIX_TASKS.md)
> 每项都必须**端到端实测**通过（真实请求 + 真实响应），不得只靠单测 mock。

| # | 任务 | 落点 | 验收 | 人日 |
|---|---|---|---|---|
| A-1 | 块编辑器请求带 JWT | `frontend/src/components/wiki/NotionStyleEditor.tsx:343-346` 改用 `apiClient`（带 Authorization） | 登录态下自动保存返回 200，块树落库 | 1 |
| A-2 | CRDT 初始化通道 | `frontend/src/lib/stompClient.ts` 订阅 `/user/queue/collab-init`；`RealtimeService.java:137-143` 合并分支补 `update` 字段 | 两个浏览器窗口：后加入者拿到初始文档且双向编辑可见 | 2 |
| A-3 | IM Pin 路径 | 前端 `/im/messages/pins` 对齐 `ImMessageController.java:69,86`（或后端加兼容端点，二选一但需契约测试锁定） | 置顶/取消置顶实测 200 | 0.5 |
| A-4 | 阅后即焚回执 | 后端补 `POST /im/messages/{id}/burn/read`（对齐 `api.ts:261`） | 端到端 200 | 0.5 |
| A-5 | Checklist API 路径 | `frontend/src/features/project/api.ts:100-110` 对齐 `/api/project-boards/...` | 勾选清单项实测落库 | 0.5 |
| A-6 | 日历翻月 + 过滤 | `CalendarView.tsx:175-186` 用 state 而非 `new Date()`；过滤改用后端支持的语法或后端 op 白名单补 `gte/lte`（择一，需契约测试） | 翻月正确、服务端过滤生效 | 1.5 |
| A-7 | 前端 unhandled errors | ErDiagram 等 5 处根因修复（禁 skip/删用例） | `npm run test:run` → 0 failed 0 errors | 1 |
| A-8 | 端点契约测试 | 新增前后端路径契约测试（锁 Pin/burn/checklist/calendar 等） | CI 中常驻，路径漂移即红 | 1 |

**DoD**：上述 8 项逐条贴实测输出；新增契约测试常驻；全量门禁（§6）全绿；**一个陌生用户在干净环境完成
「建表→录数→建 Wiki 页面并编辑→IM 发消息与置顶→看板拖拽与清单」全链路无报错**。

---

### Wave 1 · 私有化交付包（~20 人日）

| # | 任务 | 落点 | 验收 | 人日 |
|---|---|---|---|---|
| T1.1 | 镜像版本化 + 多架构 | `scripts/build-images.sh`（buildx `linux/amd64,linux/arm64`，tag `ghcr.io/…/dbcool-<svc>:<ver>`）；`minio/minio:latest` → 固定版本 | 双架构构建成功；compose 全部 image 带固定 tag | 3 |
| T1.2 | Release 自动化 | tag `vX.Y.Z` → 构建 → 推镜像 → 从 CHANGELOG 抽 Release Notes → `checksums.txt` | push tag 后产物齐全可下载 | 3 |
| T1.3 | **离线部署包（命门）** | `packaging/offline/`：`images.tar` + 只用 `image:` 不 build 的 compose + `.env.example` + `install-offline.sh` | **断网 VM** 上 `./install-offline.sh` 起得来且冒烟通过 | 5 |
| T1.4 | 安装器扩发行版 | `install.sh` 增 RHEL/CentOS/Rocky/麒麟分支；不支持则明确报错并输出支持矩阵 | Rocky 9 跑通 | 3 |
| T1.5 | Helm chart | `charts/dbcool/`（values 参数化、Secret 必填校验、ingress/存储/资源可选） | kind/minikube `helm install` 成功；**无实测环境必须标 `beta（未实测）`** | 4 |
| T1.6 | 升级与回滚 | `scripts/upgrade.sh`（备份 → 换镜像 → Flyway → 冒烟 → 失败自动 rollback）+ `docs/upgrade-runbook.md` | 用 v2.0.0 备份恢复到 v2.1.x 成功且冒烟通过 | 2 |

**DoD**：新写 `docs/quickstart-private.md`；**干净 VM 实测计时 ≤ 30 分钟**（含登录 + 6 条冒烟链路），
计时过程写入报告（命令 + 时间戳），未实测不得宣称达成。

---

### Wave 2 · 项目展示（~12 人日，可与 Wave 1 后段并行）

| # | 任务 | 落点 | 验收 | 人日 |
|---|---|---|---|---|
| T2.1 | README 重写为产品页 | 定位 → 能力矩阵 → 30 秒上手 → 截图 → 部署方式 → 授权与支持 → 路线图；PHASE 流水账移入 `docs/` | 首页不出现 PHASE 编号 | ✅ 2（PHASE 流水账 0 命中；原文存档至 `docs/DEVELOPMENT_NOTES.md`；版本一致性 exit 0） |
| T2.2 | 文档站 | VitePress `docs-site/`，8–10 篇核心 runbook 建导航（部署/运维/升级/备份/权限/API） | Pages 可访问、无死链 | ✅ 3（12 篇核心文档白名单同步 + vitepress build 通过；CI job `docs.yml`；**Pages 部署未实测**） |
| T2.3 | 视觉素材 | 6–8 张核心界面截图 + 2–3 段 30s GIF（建表→录数 / 工作流 / IM） | 落 `docs-site/public/screenshots/` | ✅ 3（**10 张截图实测**由 `scripts/screenshot-demo.mjs` 生成，md5 全部唯一；**GIF 未做**——以截图集替代） |
| T2.4 | 演示数据种子 | `scripts/seed-demo-data.py`（collection + 记录 + 视图 + 一条工作流） | **默认关闭**，仅 `--seed-demo` 或 `DEMO_SEED=true` 生效 | ✅ 2（默认关闭 exit 0；显式开启实测：1 集合 + 12 记录 + 看板视图 + 工作流（disabled）全部落库，幂等可重跑） |
| T2.5 | 在线 Demo（可选） | 2C4G 云主机 `deploy.sh demo`，只读账号 + 每日重置 | 有成本预算再上 | 2 |

**DoD**：陌生访客 3 分钟内答出「是什么 / 能干什么 / 怎么装 / 找谁」（可用 3 个真人走查或问卷留痕）。

---

### Wave 3 · 企业支持（~10 人日）

| # | 任务 | 落点 | 验收 | 人日 |
|---|---|---|---|---|
| T3.1 | 授权分层 | **Apache-2.0 核心 + 支持订阅 / 企业版附加** 双轨；修订 `NOTICE:7-9` 绝对表述；新增 `TRADEMARK.md` | 授权表述自洽，不与 Apache-2.0 冲突 | ✅ 2（NOTICE 改为"可选付费服务与授权分离"；TRADEMARK.md 明确指称性使用边界） |
| T3.2 | 支持体系 | `SUPPORT.md`（渠道 / L1-L3 分级 / 响应时限）、`docs/sla.md`（含「不含什么」）、`SECURITY.md` 补漏洞披露 + Supported Versions | 三份文档存在且承诺可兑现 | ✅ 3（SECURITY.md §0 披露流程 48h 确认 / 90 天披露窗口 + §0.1 Supported Versions；SLA 含版本支持政策与不含项） |
| T3.3 | 协作模板 | `.github/ISSUE_TEMPLATE/`（bug / 部署求助 / 安全）、PR 模板、对外版 `CONTRIBUTING.md` | 新建 issue 出现模板选择 | ✅ 3（3 个 issue 模板 + config.yml 私密披露链接 + PR 模板 + CONTRIBUTING.md；模板 YAML 已本地校验） |
| T3.4 | 客户入职包 | `docs/enterprise-onboarding.md` + `docs/acceptance-checklist.md`（复用 `scripts/launch-final-verify.py` 8 道门禁 + 6 条冒烟） | 客户照单可自行验收 | ✅ 3（入职指南 Day0-5 流程 + 验收清单 A-F 六组 20 项，命令与路线图 §6 同源；已并入文档站导航） |

**DoD**：企业用户有明确路径（渠道 → 响应时限 → 升级路径）；有版本支持政策。

---

### Wave 4 · 持续迭代节奏（长期）

| 周期 | 动作 | 入口 |
|---|---|---|
| 每周 | 一个「小批次」（≤3 人日）：从 §7 待办池取 1–3 项，单一主题，含自测与证据 | `docs/WAVE_A_CAPABILITY_FIX_TASKS.md` 追加批次 |
| 每月 | 依赖安全更新（`scripts/java-dependency-audit.py` + `npm audit --audit-level=high`）+ 全量门禁复跑 | 收敛为 `scripts/monthly-gate.py` |
| 每季 | 离线包重出、升级演练一次、文档站校准、路线回顾 | Wave 1 产物回归 |
| 每半年 | 旧版本 EOL 公告 | `docs/sla.md` Supported Versions |

**迭代机制（落地细则）**：
1. 每个 Wave 建 Milestone + `WAVE<n>_CHECKLIST.md`；**DoD 未达成不进入下一 Wave**。
2. 每周批次固定模板：`目标 / 落点文件 / 验收命令 / 自测输出 / 未实测项`；交付时贴命令原文，禁写「已完成」三字。
3. 批次顺序默认取 §7 池中**离北极星最近**的项；同一主题连续占用不超过 2 周。
4. 判定权在用户；代理不得主动 commit（需明确说「先提交」）。

---

## 六、门禁清单（每个 Wave 的 DoD 都要跑）

| 门禁 | 命令 | 基线（实测于 2026-10-30 @02b3a59） |
|---|---|---|
| 后端 | `cd backend-java && mvn -o verify` | 2604 run / 0F / 0E；JaCoCo BUNDLE line 0.8007（红线 ≥ 0.79） |
| 前端单测 | `cd frontend && npm run test:run` | 972 passed / 0 failed / **0 unhandled errors**；行覆盖率 77.12% |
| 类型检查 | `cd frontend && npx tsc --noEmit` | 0 errors |
| E2E | `cd frontend && npx playwright test` | 上次实测 159/159；**本轮未实测，按实际报** |
| Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 68 passed / 1 skipped |
| 上线门禁 | `python3 scripts/launch-final-verify.py` | 7/8（唯一 FAIL=备份可恢复：生产库 WAL 归档 archived_count=0，属环境条件；backup-e2e 29/30） |
| 备份演练 | `python3 scripts/backup-e2e-verify.py` | 29/29 |
| 冒烟 | `python3 scripts/smoke.py` | 13/13 |
| 租户隔离 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS |
| 契约 | `python3 scripts/api-contract-verify.py` | 0 漂移 |
| 启动检查 | `python3 scripts/startup-checklist.py` | 全 PASS |
| 版本一致性 | `python3 scripts/version-consistency-verify.py` | exit 0（含 3 条反向自检） |
| 依赖漏洞 | `python3 scripts/java-dependency-audit.py` | 无 HIGH/CRITICAL |

---

## 七、待办池（Wave 4 每周批次取用；按「离北极星距离」排序）

> 全部为**已存在能力的补齐**，不新增功能模块；每项标注证据与估算。

| 序 | 事项 | 证据 | 估算 |
|---|---|---|---|
| 1 | 集成市场前端可达（`IntegrationsPage` 加路由）+ PluginRegistry 持久化 | `pages/IntegrationsPage.tsx:17`；`PluginRegistry.java:24` | 2d |
| 2 | Slack bot token 迁 `TenantCredentialService` + 加密托管（对齐钉钉/企微/飞书/微信） | `SlackAppService.java:58,146-148` | 1.5d |
| 3 | 平台配置三处补齐（feishu/wecom/wechat/slack 的 application.yml + compose + k8s） | `FeishuAppService.java:39`；`SlackAppService.java:46-51` | 1d |
| 4 | K8s Secret 占位符 → Sealed Secrets 模板 + 必填校验 | `k8s/00-base.yaml:51-59` | 2d |
| 5 | Wiki 混合检索接前端 + FTS 走 tsvector | `WikiSearch.tsx:50`；`WikiSearchService.java:22-42` | 2d |
| 6 | 工作流 webhook 触发 + 节点失败重试（复用现有 DLQ 设施） | `WorkflowEngine.java:127-131`；`AmqpConfig.java:31-93` | 3d |
| 7 | @提及接真（MentionParser 接入消息发送链路，解析真实用户） | `im/MentionParser.java:26-31` | 2d |
| 8 | API Key 专属限流维度 | `GlobalRateLimitFilter.java:81-116` | 1d |
| 9 | 钉钉组织架构同步分页 + sync 历史持久化 | `DingTalkOrgSyncService.java:158-162`；`DingTalkController.java:331` | 1.5d |
| 10 | 钉钉审批回调推进工作流（消除注释空壳） | `DingTalkApprovalService.java:135-139` | 1.5d |
| 11 | 移动端响应式补 5 个关键页面（表格/看板/项目/Wiki/工作流） | `styles.css:404,613,656` | 3d |
| 12 | 备份加 PITR（WAL 归档）或明确声明 RPO 限制 | `backup.py:90-121` | 2d |
| 13 | 灰度/蓝绿发布（k8s rollout + 回滚 runbook） | `k8s/02-backend-java.yaml:112-157` | 2d |
| 14 | 外部数据源扩展类型（MySQL 驱动可选依赖化 → 显式依赖） | `ExternalDataSourceConnectionFactory.java:21` | 1d |
| 15 | 前端路由/菜单补齐：批量操作入口、看板标签管理 UI | `TableView.tsx:49,59`；`BoardColumn.tsx:100` | 1.5d |
| 16 | 文档站与 README 数字随每次发版自动校准 | `scripts/version-consistency-verify.py` 可扩展 | 1d |

**能力扩容白名单候选（默认不做，需 §3 例外通道逐条批准）**：`user`/`button` 字段类型（`FieldDef.java:49-51`）、
筛选 op 扩展 `>=`/`<=`/`in`/`between`（`CollectionController.java:455-471`）、
Wiki 版本对比 diff（当前无端点无 UI）。

---

## 八、风险与止损点

| # | 风险 | 影响 | 止损/对冲 |
|---|---|---|---|
| R1 | 断链修复触碰业务代码，可能触碰「不新增功能」红线 | 越界 | Wave 0.5 只改**调用链与端点对齐**，不新增语义；每项在任务书里标注「改动性质」 |
| R2 | 离线包/Helm/ARM64 无实测环境 | 交付物不可信 | 一律标 `beta（未实测）`；优先保 T1.3 离线包（命门），Helm 可延后 |
| R3 | 30 分钟部署目标依赖真实干净 VM | 北极星无法自证 | 早于 Wave 1 末尾申请一台 VM；若资源不可得，改为交付「一键脚本 + 计时手册」并明说未自证 |
| R4 | 三套计划并存导致返工 | 重复劳动 | 本文为唯一权威；其它文档顶部横幅指向本文 |
| R5 | 商业化表述与 Apache-2.0 冲突 | 法律风险 | Wave 3 只做「支持订阅 + 企业版附加组件」，LICENSE 不可改 |
| R6 | 长期迭代无终止条件 | 无限扩张 | 每周批次上限 3 人日；每季回顾时**允许主动砍掉**池中低价值项并记录理由 |
| R7 | 数字再次失真（README/BASELINE/…） | 展示信任崩塌 | 版本一致性脚本 + 每月门禁复跑；文档数字必须带实测标注 |

---

## 九、变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-30 | v1 初版：收窄方向 + 维护模式边界 + Wave 0–4 |
| 2026-10-30 | **v2**：合并 `docs/PLATFORM_BENCHMARK_ANALYSIS_2026-10-30.md` 的实证差距 → 新增 **Wave 0.5 能力断链修复**（置于交付包之前）；补 §7 待办池与迭代机制；红线第 5 条改为「能力扩容需例外通道」；更新实测门禁基线；本文成为唯一权威路线图 |

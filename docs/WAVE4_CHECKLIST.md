# Wave 4 · 持续迭代节奏 — 执行清单

> 创建：2026-10-30（路线图 §5 Wave 4 落地）。入口脚本：`scripts/monthly-gate.py`。
> 方向不变：**不新增业务功能**；每周批次只从待办池取项（路线图 §7）。

## 一、节奏与责任人

| 周期 | 动作 | 命令 / 产物 | 判定 |
|---|---|---|---|
| **每周**（≤3 人日） | 一个小批次：从待办池取 1–3 项，单一主题 | 本文件 §3 批次模板 | DoD 全过才可合入 |
| **每月** | 依赖安全更新 + 全量门禁复跑 | `python3 scripts/monthly-gate.py` | 12 项全绿 |
| **每季** | 离线包重出 + 升级演练一次 + 文档站校准 + 路线回顾 | `scripts/package-offline.sh` / `scripts/upgrade.sh` | 产物 SHA256 归档 |
| **每半年** | 旧版本 EOL 公告 | `docs/sla.md` §一 更新 | SUPPORT/security 同步 |

## 二、门禁（monthly-gate.py 13 项）

| # | 门禁 | 命令 | 基线（2026-10-30） |
|---|---|---|---|
| 1 | 版本一致性 | `version-consistency-verify.py` | exit 0（含 3 条反向自检） |
| 2 | 契约 0 漂移 | `api-contract-verify.py` | 437×127 → 0 漂移 |
| 3 | 依赖漏洞 | `java-dependency-audit.py` | 无 HIGH/CRITICAL |
| 4 | 后端 | `mvn -o verify` | 2588 / 0F / 0E，BUNDLE ≥ 0.79 |
| 5 | 前端单测 | `npm run test:run` | 964 / 0 failed / 0 errors |
| 6 | tsc | `npx tsc --noEmit` | 0 errors |
| 7 | eslint | `npx eslint src` | 0 errors |
| 8 | Python | `pytest tests/ -q` | 0 failed |
| 9 | 冒烟 | `smoke.py` | 13/13 |
| 10 | 租户隔离 | `tenant-isolation-e2e-verify.py` | 16/16 |
| 11 | 备份/PITR | `backup-e2e-verify.py` | 30/30 |
| 12 | 上线终验 | `launch-final-verify.py` | 8/8 |
| 13 | 灰度蓝绿 | ✅ 完成（2026-10-31：Docker Compose 单机**应用层蓝绿**（docker-compose.blue-green.yml + scripts/blue-green-deploy.sh）：green 副本并行 → 就绪判定 → sed 切 nginx upstream → 切后强校验 → 失败自动回切 blue + 清理 green；**实测三路径**：成功（20s 就绪→upstream 切 green→exit 0）/ 失败（镜像不存在→exit 1，现网保持 blue 且健康）/ 回切（upstream 回 blue→exit 0）；实测中修正 3 个真实缺陷（容器内 render_nginx.py 旧版导致切换假成功 → 改 sed 直改 default.conf + 强校验 upstream；green 带 build 导致任意 tag 都能本地构建"成功" → 移除 build 仅用预构建镜像；重渲染破坏 CSP nonce 回退）；**K8s 侧蓝绿/灰度未做**（当前仅 RollingUpdate replicas=3 + readinessProbe），docs/blue-green-deploy.md 已注明） |

## 三、每周批次模板（复制后填写）

```markdown
## 批次 <YYYY-Www>：<主题>
目标：
待办池项：#N（证据：…）
落点文件：
自测命令与输出：（贴原文）
未实测项：
commit & push：待授权 / 已授权（<hash>）
```

## 四、待办池取件规则（与路线图 §7 对齐）

1. 默认取**离北极星最近**（30 分钟部署 / 交付可信）的项。
2. 同一主题连续占用 ≤ 2 周。
3. 每季回顾允许主动砍掉低价值项（记录理由）。
4. 池当前 16 项（截至 2026-10-30），完成/砍除后从本清单勾除并回写路线图。

### 当前池（状态）

| # | 事项 | 状态 |
|---|---|---|
| 1 | 集成市场前端可达 + PluginRegistry 持久化 | 🟡 进行中（路由已通，持久化待做） |
| 3 | 平台配置三处补齐 | 🟡 进行中 |
| 2 | Slack token 迁 TenantCredentialService | ⏳ 待取 |
| 4 | K8s Secret → Sealed Secrets | ✅ 完成（2026-10-31：scripts/seal-secrets.sh 端到端实测（自签证书模拟 controller 公钥），产物合法 SealedSecret；占位符拦截反向自检 exit 2；docs/secrets-management.md；**controller 端到端解密未实测——无集群环境**） |
| 5 | Wiki 混合检索接前端 | ✅ 完成（2026-10-31：WikiSearch 优先走 /wiki/search/hybrid（未选 status 时），语义不可用自动回退 ILIKE；单测 6/6 + 反向自检通过） |
| 6 | 工作流 webhook 触发 + 节点重试 | ✅ 完成（2026-10-31：POST /api/workflows/webhook/{token}（permitAll + 常量时间 token 比较 + 未命中 404）；webhook 工作流自动生成 32 位 hex token 并落 trigger_json；节点 config.retry 失败重试（上限 3，既有 184 workflow 测试全绿 + 新增 7/7）；设计器暴露 webhook 类型与 token 展示；契约 437→438 |
| 7 | @提及接真 | ✅ 完成（2026-10-31：MessageComposer @提及下拉（频道成员 displayName 候选 + 键盘导航），选中插入后端结构化语法 @{displayName}:userId（此前用户手打 @ 名字后端永远匹配不到 → 通知永不触发）；后端 members 端点补 displayName；单测后端 11/11（含 2 新例）、前端 16/16（含 2 新例）；反向自检 exit 1） |
| 8 | API Key 专属限流 | ✅ 完成（2026-10-31：GlobalRateLimitFilter 新增 apiKey 维度（X-Api-Key 机器调用按 key 隔离，默认 600/60s，ratelimit.api-key.* 可配）；限流 key 用 SHA-256 前 12 位，明文密钥不进日志/内存；单测 4/4 + 反向自检 exit 1） |
| 9 | 钉钉组织同步分页 | ✅ 完成（2026-10-31：syncAllUsers cursor 分页拉全量（>100 人企业不再漏人）、部门树 BFS 递归（原只拉第一层）、**钉钉 errcode != 0 显式失败**（修复假凭证下"同步成功 0 人"的静默假成功）；RestTemplate 可注入；单测 11/11 + 反向自检） |
| 10；**图模式续跑已实现（2026-10-31 收尾）**：approveTask 检测 edges 非空 → engine.executeGraphFrom 续跑（CONDITION/HTTP/DINGTALK_APPROVAL 全能力），最后节点→COMPLETED、非法 JSON 降级数组模式；Closure 6→9/9 + 反向自检（移除图模式分支→1 例 FAIL）；JaCoCo 包级 workflow 0.93<0.94 红线触发 → 补 3 分支用例恢复达标（未弱化断言） | 钉钉审批回调闭环 | ✅ 完成（2026-10-31 收尾：新增 WorkflowTaskService（approve/reject/下游节点推进从 WorkflowController 逐行搬迁，Controller 改为委托，行为不变）；钉钉回调改为**事件解耦**（发布 DingTalkApprovalCallbackEvent —— 直接注入会形成 ApprovalService→TaskService→Engine→Handler→ApprovalService 构造循环依赖，Spring 无法启动，实测）；agree 回调经事件→approveByExternalBinding→approveTask **续跑下游节点**，与平台内审批同一逻辑；测试：Closure 4/4（含会签保持 PENDING）+ 反向自检 exit 1；全量 13/13） |
| 11 | 移动端响应式 5 关键页 | ✅ 完成（2026-10-31：实测发现基础已相当完善（AppLayout 6 入口底导航 44px 触控、IM 布局 768px、表格滚动、PWA 安全区、44px 输入框）；补齐：styles.css 新增 .grid-2（桌面双列→移动单列）与 .page-responsive（页面级横滚兜底），Home/DataSources 内联网格改用类、DataSources 表格包滚动容器；实测：mobile-chromium（iPhone 13）Playwright 13/13 通过，含新增 DataSources 用例（无横滚 + 表格滚动容器 + 网格塌缩单列） |
| 12 | WAL 归档（生产侧） | ✅ 已修（backup-e2e 30/30） |
| 13 | 灰度/蓝绿 | ✅ 部分完成（2026-10-31：Docker Compose 单机**应用层蓝绿**（docker-compose.blue-green.yml + scripts/blue-green-deploy.sh）：green 副本并行 → 就绪判定 → sed 切 nginx upstream → 切后强校验 → 失败自动回切 blue + 清理 green；**实测三路径**：成功（20s 就绪→upstream 切 green→exit 0）/ 失败（镜像不存在→exit 1，现网保持 blue 且健康）/ 回切（upstream 回 blue→exit 0）；实测中修正 3 个真实缺陷（容器内 render_nginx.py 旧版导致切换假成功 → 改 sed 直改 default.conf + 强校验 upstream；green 带 build 导致任意 tag 都能本地构建"成功" → 移除 build 仅用预构建镜像；重渲染破坏 CSP nonce 回退）；**K8s 侧蓝绿/灰度未做**（当前仅 RollingUpdate replicas=3 + readinessProbe）→ 留待后续批次 |
| 14 | 外部数据源类型扩展 | ⏳ |
| 15 | 批量操作入口 + 标签管理 UI | ✅ 完成（2026-10-31：勘发现 TableView 批量编辑已完整（全选/逐行/批量改值/批量删除，用 prompt/confirm 有 UI 质量欠账记入下批可选）；剩余缺口=看板标签管理 → 新增 LabelManager 组件消费既有 /api/project-boards/labels* 端点（新建/重命名/删除/颜色），BoardView 头部入口；单测 4/4 + 反向自检；**卡片↔标签关联需新增关联表与端点，属能力扩容已列入例外通道候选**） |
| 16 | 文档站数字自动校准 | ⏳ |

## 五、每月批次归档

| 月份 | 批次 | 门禁结果 | 备注 |
|---|---|---|---|
| 2026-10 | Wave0/0.5/1/2/3 收口 | 12/12 | v2.1.1 发布 |
| 2026-10-31 | Wave4 第二批：#6 工作流 webhook+重试（待办池 #6 完成） | **12/12** | v2.1.2 已发布；Java 2594 tests |
| 2026-10 | Wave4 首批（#1 集成市场可达 + #3 配置三处）+ 全新部署可用性修复 | **12/12**（monthly-gate 全量实测） | **v2.1.2 发布**：Release + 4 镜像已上 GHCR（amd64）；arm64 独立 job 不阻塞 |

### 已修复的"全新部署"阻断项（v2.1.2，6 项）
1. Flyway `baseline-version: 0` + `out-of-order: true`（V1 从未执行 / 兼容迁移低于主库版本）
2. `V27_5__auth_user_compat.sql`（遗留表兼容）
3. `V27_6__ldap_user_mapping_compat.sql`（V28 漏 tenant_id 列）
4. `V75__collection_version.sql`（表从未创建）
5. `V76__schema_completion.sql`（实体 schema 全量比对缺 2 列）
6. `INTEGRATION_CREDENTIAL_MASTER_KEY` env 补齐（安全红线 fail-fast 需配置）

**离线包断网等价实测通过**：全新库起栈 8 服务全 healthy、Flyway V1→V76 全通过、零 registry 拉取。

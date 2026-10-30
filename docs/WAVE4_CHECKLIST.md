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

## 二、门禁（monthly-gate.py 12 项）

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
| 8 | API Key 专属限流 | ⏳ |
| 9 | 钉钉组织同步分页 | ⏳ |
| 10 | 钉钉审批回调闭环 | ⏳ |
| 11 | 移动端响应式 5 关键页 | ⏳ |
| 12 | WAL 归档（生产侧） | ✅ 已修（backup-e2e 30/30） |
| 13 | 灰度/蓝绿 | ⏳ |
| 14 | 外部数据源类型扩展 | ⏳ |
| 15 | 批量操作入口 + 标签管理 UI | ⏳ |
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

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
| 4 | K8s Secret → Sealed Secrets | ⏳ |
| 5 | Wiki 混合检索接前端 | ⏳ |
| 6 | 工作流 webhook 触发 + 节点重试 | ⏳ |
| 7 | @提及接真 | ⏳ |
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
| 2026-10 | Wave0/0.5/1/2/3 收口 | 12/12（见 monthly-gate 首跑） | v2.1.1 发布 |

# 支持渠道（SUPPORT）

> DBCool 核心以 Apache-2.0 开源。本页说明**社区渠道**与**企业支持订阅**的边界，
> 以及各级别的问题处理路径。SLA 细节见 [`docs/sla.md`](docs/sla.md)。

## 一、先自助（最快）

| 需求 | 入口 |
|---|---|
| 安装 / 启动排错 | [`docs/install-support-matrix.md`](docs/install-support-matrix.md)、[`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) |
| 升级 / 回滚 | [`docs/upgrade-runbook.md`](docs/upgrade-runbook.md) |
| 备份恢复 / 密钥轮换 | [`docs/backup-restore-runbook.md`](docs/backup-restore-runbook.md)、[`docs/key-rotation-runbook.md`](docs/key-rotation-runbook.md) |
| 依赖漏洞与安全基线 | [`SECURITY.md`](SECURITY.md) |
| 自助验收 | [`docs/acceptance-checklist.md`](docs/acceptance-checklist.md) |

## 二、社区渠道（免费，尽力响应）

| 渠道 | 适合 | 响应预期 |
|---|---|---|
| GitHub Issues（按模板） | Bug 报告 / 功能建议 / 部署求助 | 工作日尽力响应，**无时限承诺** |
| GitHub Discussions / PR | 贡献与讨论 | 尽力响应 |

**提问要求**：按 issue 模板填写环境信息（发行版/Docker 版本/日志尾部/复现步骤）。
信息不全的问题可能被标记 `needs-info` 并在 14 天无回复后关闭。

## 三、企业支持订阅（付费，有 SLA）

面向生产使用 DBCool 的企业，提供订阅服务（与 Apache-2.0 授权分离，见 [NOTICE](NOTICE)）：

| 级别 | 覆盖 | 首响时间（工作日） | 处理目标 |
|---|---|---|---|
| **L1 咨询** | 安装、配置、使用问题 | ≤ 1 个工作日 | 3 个工作日内给出方案或规避 |
| **L2 故障** | 非核心功能故障、性能退化 | ≤ 4 小时 | 2 个工作日内修复或 workaround |
| **L3 严重** | 数据丢失风险 / 核心链路不可用 / 安全事件 | ≤ 1 小时（7×24） | 持续响应至恢复，24 小时内根因初判 |

订阅范围与报价通过仓库主页所列渠道联系（标题注明 `[SUPPORT]`）。
SLA 的**不含项**（如自有业务代码缺陷、第三方服务故障、超出支持版本范围的旧版本）
见 [`docs/sla.md`](docs/sla.md)。

## 四、升级路径

1. 社区用户遇到影响生产的严重问题：开 issue 并标注 `severity: critical` → 维护者评估后可升级为专项处理。
2. 订阅用户：直接走订阅渠道，由支持负责人升级至 L2/L3。
3. 安全漏洞：**不要**开公开 issue，走 [`SECURITY.md`](SECURITY.md) 的披露流程。

---

*本文件承诺随产品化 Wave 3 交付建立；服务协议以双方签署文本为准。*

# 3be5ea1 发布门禁证据 — 2026-11-21

## 执行对象与范围
- release revision：`3be5ea12bcf81e0aebecf070ccd0f3fe31ba1159`
- 干净 detached worktree，版本单一真相源 `VERSION=2.1.5`。
- 验收 project：`nocobase-acceptance`；Java `http://127.0.0.1:18082`，Python `http://127.0.0.1:18000`，frontend `http://127.0.0.1:18080`。
- 验收镜像为本地单架构；没有执行 `down -v`、FLUSHDB、audit_log 删除或默认栈清理。
- 真实备份从验收卷恢复：`backup_20261115_061925.tar.gz`，227,081 bytes；未将备份、`.env` 或凭据提交。

## 2026-11-15 历史执行与本次重跑
- 原始 `/tmp/kilo/monthly-gate-release-3be5ea1-final4.log` 曾在 2026-11-15 得到 15 项主门禁 + 文档数字全通过，原始日志因宿主清理不可再读。
- 本次不将该历史结果冒充可复核日志，于 `2026-11-21T15:12Z` 起在相同 revision 重新执行完整门禁。
- 归档 `monthly-gate-release.log` 是完整原始 stdout，含所有 15 项主门禁 PASS；仅文档数字校验 FAIL（80.26 声明 vs 80.25 实测）。
- 原包装命令的 `PIPESTATUS` 未被工具环境保留，末尾有 `monthly_gate_exit=` 和 `exit: numeric argument required`；这不改变脚本自身明确输出的 `文档数字校验 exit=1`，不得当全绿。

## 15 项主门禁（本次实测均 PASS）
| 门禁 | 结果 |
|---|---|
| 版本一致性 | exit 0 |
| 契约 0 漂移 | 460 × 137，exit 0 |
| 依赖漏洞审计 | exit 0；Trivy 使用 `public.ecr.aws/aquasecurity/trivy-db` 离线缓存，跳过 DB/Java DB 更新 |
| Java `mvn -o verify` | 2,855 tests / 0F / 0E；JaCoCo line 18,011/(18,011+4,432)=80.25%，门槛通过 |
| frontend vitest | 121 files / 978 passed |
| frontend tsc / eslint | exit 0 |
| Python pytest | 85 passed / 1 skipped |
| smoke | 14/14 |
| 租户隔离 | 24/24 |
| 归属写入静态门禁 | exit 0 |
| 企业套件 PG IT / schema smoke | exit 0 |
| 备份/PITR | 30/30 |
| 上线终验 | 8/8 |

## 文档数字校验（修复后的终态）
- 使用同一实测 `target/gate-numbers.json`，仅将 README/RISK_REGISTER/PRODUCTIZATION_ROADMAP 的 JaCoCo `80.26` 改为 `80.25`。
- 填入前次已实测的 `playwright.tests=260`（13 files，260 tests）用于既有文档锚点；本次未重新跑 Playwright，不能当作新的 E2E 实跑证据。
- 运行 `python3 scripts/doc-numbers-verify.py --fix` 后，再以只校验模式运行并归档 `doc-numbers-verify.log`：`✅ 文档门禁数字与实测一致（28 个锚点）`，exit 0。
- 因此终态是 15 项主门禁原始通过 + 文档数字校验随后单独通过。没有改写原始 `monthly-gate-release.log` 来伪装一次全绿。

## 观察边界
- 24h 有效观察段在 2026-11-21 14:51:30Z 达成，详见 `observation-review.md`。
- 本次 15:24Z 的租户清理门禁新增 1 次 Java 500；时间、URI、健康与镜像状态均记录在该文件，不能称为“门禁后仍无 5xx”或重新启动一个未完成的观察窗口。

## 尚未完成/外部依赖
- GHCR 多架构镜像、v2.1.5 GitHub Release、外部冷启动与拉取验收不能由本文件推断。
- 原 `release.yml` 的 amd64/arm64 两个 job 都向同一 tag 直接 push，存在互相覆盖且不能形成多架构 manifest 的风险，发布前必须修复并验证。

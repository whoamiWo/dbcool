# v2.1.5-3 离线交付复验

日期：2026-11-28

范围：`fix/offline-installer-fail-closed` 工作区；应用镜像保持已发布的 `2.1.5-3`。

## 本轮改动

- 离线 Compose 项目、数据卷和网络按 `OFFLINE_INSTANCE_ID` 隔离，避免与主栈或其他验收实例串用资源。
- 安装器在 Docker 写操作前校验实例 ID；非法值（包括 `../unsafe`）直接失败。
- 安装失败矩阵补齐实例 ID 反向测试，确认未执行 `docker load` 或 `compose up`。
- 移除仓库中的自签证书和私钥；`certs/*.pem` 加入忽略规则，CI 拒绝提交私钥。
- README 首次启动显式生成本地占位证书，一键部署在 preflight 前生成证书。

## 实测

| 门禁 | 结果 | 证据 |
|---|---|---|
| 部署/离线自检 | 全部通过 | `docs/evidence/offline-selfcheck-2.1.5-3.log` |
- Java：`mvn -o verify`，2856 tests / 0 failures / 0 errors；JaCoCo 红线通过。
- 前端：`npm run test:run`，121 files / 978 passed / 0 failed；`npm run tsc` 通过。
- Python：`PYTHONPATH=src python3 -m pytest tests/ -q`，88 passed / 1 skipped；Ruff 全部通过。
| 版本 / API 契约 | 版本一致；460 × 137，0 漂移 | 本轮执行通过 |
| 最终包断网安装 | 安装与健康检查通过 | `docs/evidence/offline-install-blocked-network-2.1.5-3.log` |
| 断网核心冒烟 | 14/14 | 同上 |

断网验证通过 iptables 拒绝当前用户的出站流量，同时保留 loopback；访问 `ghcr.io` 已确认失败。
最终包 SHA256：`f8dabbffdd38dfa32677f640292f56d27d578c10cd40a83a0a5ab781464f734a`。

## 边界

- 这是 WSL 单环境实测，不替代干净 Ubuntu VM、Rocky、麒麟或 ARM64 验收。
- 旧自签私钥仍存在于 Git 历史，必须按已泄露处理；曾使用该证书的环境需替换。此次仅停止继续分发，未重写远程历史。
- 临时测试实例、卷、凭证和网络拦截规则已清理。

commit & push：待用户授权。

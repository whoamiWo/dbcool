# DBCool 安装支持矩阵

> Wave1 T1.4（2026-10-30）。`install.sh --check` 输出与此文档同源。

| 发行版 | 版本 | 包管理器 | 状态 | 说明 |
|---|---|---|---|---|
| Ubuntu | 22.04+ | apt | ✅ 支持（开发/测试环境实测） | 主路径，含 Adoptium JDK 21 回退 |
| Debian | 12+ | apt | ✅ 支持（开发/测试环境实测） | 同 Ubuntu 路径 |
| RHEL / AlmaLinux | 9+ | dnf | ⚠️ 支持（**未实测**） | JDK 用 `java-21-openjdk(-devel)` |
| Rocky | 9+ | dnf | ⚠️ 支持（**未实测**） | 同上 |
| CentOS Stream | 9+ | dnf | ⚠️ 支持（**未实测**） | 同上 |
| 银河麒麟 Kylin | V10 | yum/dnf | ⚠️ 支持（**未实测**） | 依赖其源内 `java-21-openjdk` |
| openSUSE / SLES | - | - | ❌ 不支持 | 明确报错并打印矩阵 |
| 其他 | - | - | ❌ 不支持 | 明确报错并打印矩阵 |

通用前置（所有发行版）：Docker Engine 20.10+ 与 docker compose 插件（脚本会检测/安装）。

离线安装请使用 Wave 1 T1.3 的 `packaging/offline/install-offline.sh`（无包管理器依赖）。

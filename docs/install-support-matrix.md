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

生产 Compose 必须在 `.env` 显式设置 `APP_VERSION` 为**已发布的镜像 tag**（当前示例 `2.1.5-3`）；
根目录 `VERSION` 是源码版本，不能自动当作镜像 tag。主 compose 不含应用 build，缺镜像或 tag 不可拉取时应失败。
本地源码构建仅用于开发：复制 `.env.dev.example` 为 `.env.dev`，叠加 `docker-compose.dev-build.yml`。

离线安装请使用 Wave 1 T1.3 的 `packaging/offline/install-offline.sh`（无包管理器依赖）；离线包生成器要求显式传入 `APP_VERSION`。

### 离线安装器前置条件

- 目标主机已有 Docker Engine 20.10+ 与 `docker compose` 插件；
- 目标主机已有 **Python 3**（安装器用它解析 `.env`、比对 Compose 插值并执行健康探针；缺失时安装器立即 exit 2，不会做任何 Docker 写操作）；
- 首次运行前由人工准备 `.env`：非交互模式下缺 `.env` 直接 exit 2；交互模式只会生成模板并返回 `CONFIG_REQUIRED`，不会以占位凭证启动；
- 安装器会校验 `image-tags.txt` 中 8 个精确镜像（4 个应用 + 4 个基础镜像）并对每个 `docker image inspect`，任一缺失即在 `compose up` 前失败；
- 私有 CA / 自签证书场景需显式提供 `SMOKE_CA_BUNDLE`（默认使用包内 `certs/fullchain.pem`，若存在）；smoke 不提供关闭证书校验的开关。

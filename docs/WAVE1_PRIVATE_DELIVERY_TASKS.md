# Wave 1 · 私有化交付包 — 任务书（T1.1 ~ T1.6）

> 创建日期：2026-10-30　路线依据：[`docs/PRODUCTIZATION_ROADMAP.md`](./PRODUCTIZATION_ROADMAP.md) §5 Wave 1
> 基线：`7199059`（v2.1.1，2026-10-30）　工作量：约 20 人日
> **北极星**：陌生人 30 分钟部署成功。本批次所有取舍以「能不能让离线部署更快更稳」为准。
> 性质：**交付化工作**（镜像/打包/安装/升级），**不新增业务功能**。

---

## 0. 全局红线（违反即判定不通过）

| # | 红线 | 说明 |
|---|---|---|
| R1 | **镜像必须固定 tag** | 禁止 `latest`；compose 与离线包里的每个镜像都要有确定版本 |
| R2 | **不得谎报实测** | 未实测项必须写「未实测」/「beta（未实测）」，禁写「通过」 |
| R3 | **不新增业务功能** | 只做镜像、打包、安装、升级、发布；不改业务行为 |
| R4 | **离线包不得污染生产** | 演示/种子类内容默认关闭 |
| R5 | **禁止破坏现有门禁** | 改 compose/k8s 后必须复跑 `mvn -o verify`、前端测试、`smoke.py`、`launch-final-verify.py` |
| R6 | **不主动 commit / push** | 除非用户明确说「先提交」；否则报告末尾写「commit & push：待你授权」 |
| R7 | **行号以实测为准** | 漂移自行定位并在报告中说明 |
| R8 | **离线包必须可校验** | 提供 SHA256 校验和；安装脚本校验失败即中止 |

---

## 1. T1.1　镜像版本化 + 多架构（3 人日）

### 现状（2026-10-30 实测）
- 4 个应用服务全是 `build:`，无 tag、无 registry：`docker-compose.yml:152`（backend-java）、`:269`（backend-python）、`:360`（crdt-service）、`:399`（frontend）
- 基础镜像未锁版本：`minio/minio:latest`（`:82`）；其余为 `pgvector/pgvector:pg16`（`:17`）、`redis:7-alpine`（`:55`）、`rabbitmq:3.13-management-alpine`（`:119`）
- 现有 tag 相关逻辑：`scripts/freeze-demo.sh:50`（可复用）

### 改动要求
1. 新增 `scripts/build-images.sh`：
   - 读取根目录 `VERSION` 作为 tag（与 Wave 0 单一真相源一致）；
   - 用 `docker buildx build --platform linux/amd64,linux/arm64 --push` 构建 4 个应用镜像，仓库前缀由环境变量 `IMAGE_REGISTRY`（默认 `ghcr.io/whoamiwo/dbcool`）决定；
   - 产出镜像名：`<registry>/dbcool-<backend-java|backend-python|crdt-service|frontend>:<version>`；
   - 支持 `--load`（本地单架构测试用）与 `--no-push`（离线构建）。
2. `docker-compose.yml`：
   - 4 个应用服务改为 `image: ${IMAGE_REGISTRY:-ghcr.io/whoamiwo/dbcool}/dbcool-<svc>:${APP_VERSION:-2.1.1}`，并保留 `build:` 段作为**本地开发可选项**（用 `profiles: ["dev-build"]` 或注释说明），保证「拉镜像部署」与「本地构建」两条路都可用；
   - `minio/minio:latest` → 固定版本（与 `docker-compose.observability.yml`、k8s 中出现的版本对齐，需实测该 tag 存在）；
   - 其余基础镜像补精确版本（不用 `7-alpine` 这类浮动 tag）。
3. 同步 `k8s/*.yaml` 中若出现的 `latest` 基础镜像。

### 验收（贴真实输出）
```bash
bash scripts/build-images.sh --help            # 期望打印用法
bash -n scripts/build-images.sh                 # 期望 0（语法检查）
grep -n "image:" docker-compose.yml            # 期望：全部带固定 tag，无 latest
docker compose config >/dev/null && echo CONFIG_OK
```

---

## 2. T1.2　Release 自动化（3 人日）

### 改动要求
1. 新增 `.github/workflows/release.yml`：
   - 触发：`push` tag `v*.*.*`（**不**在 PR 上触发）；
   - 步骤：checkout → setup-java（21）→ setup-python（3.12）→ setup-node（20）→ 运行 `scripts/build-images.sh`（推镜像）→ 从 `CHANGELOG.md` 抽取对应版本条目生成 Release Notes → 生成 `checksums.txt`（含镜像 tar 或发布物 SHA256）→ 创建 GitHub Release（上传产物）。
2. 抽取脚本 `scripts/extract-changelog.py`：读 `CHANGELOG.md`，取 `## <version>` 段落直到下一个 `## `；不存在则 exit 2（禁静默产出空 Notes）。
3. 扩展 `scripts/version-consistency-verify.py` 语义保持：Release 前必须 exit 0（CI 里已有 job）。

### 验收
```bash
python3 scripts/extract-changelog.py --version v2.1.1     # 期望打印该版本条目
python3 scripts/extract-changelog.py --version v9.9.9     # 期望 exit 2（反向自检：不存在版本必须失败）
```

---

## 3. T1.3　离线部署包（命门，5 人日）

### 改动要求
1. 目录 `packaging/offline/`：
   - `images.tar`：由 `docker save` 生成的应用镜像集合（backend-java / backend-python / crdt-service / frontend）；
   - `docker-compose.offline.yml`：与主 compose 结构一致，但**只用 `image:`，绝不 `build:`**，且所有镜像指向本地加载的 tag；
   - `.env.example`：版本、端口、存储路径、密码占位；
   - `install-offline.sh`：`docker load -i images.tar` → 校验 SHA256 → 生成 `.env` → `docker compose up -d` → 等待健康 → 调用既有 `scripts/smoke.py` 做冒烟。
2. 打包脚本 `scripts/package-offline.sh`：串起 build → save → 生成 compose/env → 生成 `SHA256SUMS` → 打包为 `dbcool-offline-<version>.tar.gz`。
3. 断网验证：`install-offline.sh` 必须**不联网**可用（除本地镜像）。

### 验收（**必须有实测环境**）
```bash
bash scripts/package-offline.sh --help
# 有干净 VM 时：断网（或 firewall 拦截出网）→ ./install-offline.sh → 冒烟 13/13
# 无环境时：报告写「未实测」，并明确标注 T1.3 = beta（未实测），不得宣称达成
```

---

## 4. T1.4　安装器扩发行版（3 人日）

### 改动要求
1. `install.sh` 当前仅支持 Ubuntu/Debian（`:39-41`）：扩展 RHEL/CentOS/Rocky/Alma/麒麟（Kylin）分支——包管理器分别 `dnf`/`yum`/`tdnf`，服务管理 `systemd`。
2. 不支持的发行版：**明确报错 + 打印支持矩阵**，不得半途失败。
3. 输出 `docs/install-support-matrix.md`：发行版 / 版本 / 包管理器 / 状态（实测/未实测）。

### 验收
```bash
bash -n install.sh
bash install.sh --check        # 期望打印支持矩阵并以 0 退出
# Rocky 9 上跑通 → 贴输出；无环境 → 标 beta（未实测）
```

---

## 5. T1.5　Helm chart（4 人日，beta）

### 改动要求
- `charts/dbcool/`：`Chart.yaml` + `values.yaml` + `templates/`（deployment/statefulset、service、secret（必填校验）、ingress（可选）、configmap、pvc、hpa、pdb）；
- `helm lint` 必须通过；`helm template` 渲染结果可用 `kubectl apply --dry-run=client` 校验。

### 验收
```bash
helm lint charts/dbcool
helm template charts/dbcool > /tmp/rendered.yaml && echo RENDER_OK
# kind/minikube 实测安装 → 贴输出；无环境 → 标 beta（未实测）
```

---

## 6. T1.6　升级与回滚（2 人日）

### 改动要求
1. `scripts/upgrade.sh`：备份（调用 `scripts/backup-e2e-verify.py` 的备份路径或 `backend-python` 备份服务）→ 换镜像 tag → 等待 Flyway 迁移（后端健康检查）→ 冒烟 → 失败自动 rollback（换回旧 tag + 恢复备份）。
2. `docs/upgrade-runbook.md`：前置条件、步骤、回滚触发条件与命令、数据迁移注意事项（当前 Flyway 已到 **V74**）。
3. 备份缺失或冒烟失败时**必须中止并回滚**，禁止半升级状态。

### 验收
```bash
bash -n scripts/upgrade.sh
bash scripts/upgrade.sh --help
# 用 v2.0.0 备份恢复到 v2.1.x → 冒烟通过（实测贴输出；无环境标未实测）
```

---

## 7. 交付前必须全绿（逐条贴输出）

| # | 门禁 | 期望 |
|---|---|---|
| 1 | `cd backend-java && mvn -o verify` | 2588 run / 0F / 0E，覆盖率红线 0.79 达标 |
| 2 | `cd frontend && npm run test:run` | 964 passed / 0 failed / 0 unhandled errors |
| 3 | `cd frontend && npx tsc --noEmit` | 0 errors |
| 4 | `python3 scripts/version-consistency-verify.py` | exit 0（+ 三条反向自检） |
| 5 | `python3 scripts/api-contract-verify.py` | 0 漂移 |
| 6 | `python3 scripts/smoke.py` | 13/13 |
| 7 | `python3 scripts/launch-final-verify.py` | 8/8（含备份可恢复；若 WAL 归档缺口未修则如实写 7/8） |
| 8 | `docker compose config` | 0 退出且无 `latest` |

## 8. 报告要求

1. 逐任务 ✅/❌ + **命令输出原文**；每改动写 `文件:行号`，漂移需说明。
2. 明确列出所有「未实测 / beta」项（Helm、Rocky、麒麟、ARM64、断网 VM）。
3. 结尾写：`commit & push：待你授权`。
4. 任一门禁 FAIL：写清根因与是否已修，**不得**自行标「✅ 已完成」。

# PHASE94 任务书：备份可靠性 —— 恢复演练 + PITR（🔒-7）

> 选题依据：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md:394`（🔒-7）
> 文档原文：**"备份异地与 PITR：主库备份（P0-1）+ 跨区域存储 + 时间点恢复"**。

---

## §1 为什么做这个（三条理由）

### 1.1 备份没演练过恢复 = 没备份 —— 这是最隐蔽的假完成

本项目**已有完整备份实现**（`backend-python/src/nocobase_py/services/backup.py`）：

- `create_backup()`：`pg_dump -Fc`，打包 PostgreSQL / Redis / SQLite / media + `manifest.json`
- `restore_backup()`：`pg_restore` 恢复
- 接口：`POST /api/admin/backup/{id}/restore`

**但备份目录是空的**（实测），意味着这套能力**极可能从未真正跑过、更没恢复过**。

> 状态是"有备份、有接口、看着能用" —— 这比"没有备份"更危险，
> 因为它会给人**虚假的安全感**：真出事时能不能恢复是未知的。

### 1.2 本地完全可验证，不依赖外部基建

Postgres / Redis / MinIO 都在本地。这是它能排在 🔒-3（需 Vault）、
🔒-9（需 K8s）前面的关键 —— 那两项在离线环境只能纸上完成。

（"跨区域存储"这一项本地无法真做异地，但**副本 + 从副本恢复**可以验证。）

### 1.3 与前几批形成可靠性闭环

- PHASE88：知道系统能扛多少（容量）
- PHASE92：防止单租户打爆（配额）
- **本批：真出事能不能恢复**

## §2 现状（实测，不是照抄文档）

| 项 | 实测 |
|---|---|
| 备份实现 | ✅ 完整（`create_backup` / `restore_backup`） |
| 备份文件 | ❌ **备份目录为空** —— 大概率从未执行 |
| 恢复演练 | ❌ 无 |
| **`wal_level`** | ✅ **`replica`**（PITR 前提已具备一半） |
| **`archive_mode`** | ❌ **`off`** —— 归档未开，**无法 PITR** |
| 异地副本 | ❌ 无 |

> 关键发现：`wal_level=replica` 说明 PITR 的一半前提**已经具备**，
> 只需开 `archive_mode=on` + `archive_command` —— 比预想的近。

## §3 五项任务

### T1（P0）真跑一次备份

- 调用 `create_backup()`（或对应接口），确认产出 `backup_<stamp>.tar.gz`
- 校验 `manifest.json`：**关键组件（postgresql、redis）必须存在且 `bytes > 0`**
- 记录：文件大小、各组件字节数、耗时

### T2（P0）恢复演练 —— 本批验收核心

**必须在独立环境做，严禁在正在使用的库上恢复**：

- 起一个**独立的 Postgres 容器**（如 `postgres-restore-test`，独立端口/数据卷）
- 把备份里的 `postgresql/dump.pg` 恢复到它
- 验证"数据真的回来"：
  - 表数量与源库一致
  - 抽查若干表的记录数
  - 抽查一条具体记录的内容
- 演练结束销毁容器

> **验收点不是"命令返回 0"，而是"数据真的可用"**。
> 只检查退出码等于没验证（本项目已多次栽在"只看通过、不看内容"）。

### T3（P0）PITR —— 开启 WAL 归档并验证时间点恢复

- 在 compose 的 postgres 服务上开 `archive_mode=on` + `archive_command`
  （`wal_level` 已是 `replica`，无需改）
- 验证：
  - 造一条数据 → 记录时间 T
  - 再删掉它
  - **恢复到 T 时刻** → 断言数据回来了
- 这一步证明的不只是"配置开了"，而是**真的能回到任意时间点**

### T4（P1）副本与异地

- 备份文件复制到**独立位置**（本地可用第二目录或 MinIO 模拟"异地"）
- 验证：**从副本恢复**同样可用（不是只从原位置恢复）

### T5（P1）调度 + 可重复执行的验证脚本

- 明确定时备份是否已有；没有则补
- 备份失败必须**显式告警**（不能静默）
- 产出可重复执行的脚本（参照 `scripts/quota-e2e-verify.py` 的风格），
  能一键跑完 T1/T2 并输出 PASS/FAIL

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382**（前端无改动就如实写 382） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §5 红线（违反即打回）

1. **严禁在正在使用的数据库上做恢复演练** —— 恢复会重置库，失败即丢数据。
   必须起独立容器/实例
2. **严禁把"能生成备份文件"当作"备份可用"** —— 必须真的恢复出来并校验数据
3. **严禁只检查退出码** —— 必须校验表数、记录数、具体内容
4. **严禁伪造演练结果**（贴真实输出）
5. **严禁破坏现有备份实现** —— 它是可用的，本批是"验证 + 补 PITR"，不是重写
6. 严禁修改已应用的迁移文件

## §6 交付清单

1. 一次真实备份的证据（manifest 各组件字节数、文件大小）
2. **恢复演练证据**：独立容器 + 表数/记录数/具体记录对比
3. **PITR 证据**：恢复到某时间点后数据回来的对比
4. 副本恢复证据（T4）
5. 可重复执行的验证脚本 + 实测输出
6. 门禁五项实测数字 + 提交 hash + `git status`（必须干净且已推送）

## §7 可复用（别重造）

- 备份/恢复实现：`backend-python/src/nocobase_py/services/backup.py`
- 验证脚本风格：`scripts/quota-e2e-verify.py`、`scripts/user-data-e2e-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`
- 容器操作：`docker compose up -d --no-build <service>`
  （**改了 compose 环境变量必须重建容器**，否则不生效）

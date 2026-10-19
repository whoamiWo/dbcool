# PHASE94 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE94：备份可靠性 —— 恢复演练 + PITR（🔒-7）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录

### 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`（**必须加 `PYTHONPATH=src`**） |
| **一键冒烟（真调后端）** | `python3 scripts/smoke.py`（11 条链路，约 0.4 秒） |
| 配额端到端验证 | `python3 scripts/quota-e2e-verify.py`（验证脚本的写法参考） |
| 用户数据合规验证 | `python3 scripts/user-data-e2e-verify.py` |
| 压测 | `docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`（**离线镜像本地已有**，别再说"没装 k6"） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**；不要用在线 Dockerfile，会超时） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 重启后端 | `docker compose up -d --no-build backend-java`（**改了 compose 环境变量必须重建容器**，否则不生效） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| 判断某配置是否配了 | 同时查 ① `application.yml` ② `docker-compose.yml` environment ③ `.env` |

---

## §1 为什么做这一项

来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md:394`：

> 🔒-7 **备份异地与 PITR**：主库备份（P0-1）+ 跨区域存储 + 时间点恢复

**本项目已有完整备份实现** —— `backend-python/src/nocobase_py/services/backup.py`：

- `create_backup()`：`pg_dump -Fc`，打包 PostgreSQL / Redis / SQLite / media + `manifest.json`
- `restore_backup()`：`pg_restore` 恢复
- 接口：`POST /api/admin/backup/{id}/restore`

**但实测备份目录是空的** —— 这套能力**极可能从未真正跑过，更没有恢复过**。

> **备份没演练过恢复 = 没备份。** 现在的状态是"有备份、有接口、看着能用"，
> 这比"没有备份"更危险 —— 它给的是**虚假的安全感**。

## §2 现状（实测确认）

| 项 | 实测 |
|---|---|
| 备份实现 | ✅ 完整 |
| 备份文件 | ❌ **目录为空** —— 大概率从未执行 |
| 恢复演练 | ❌ 无 |
| `wal_level` | ✅ **`replica`**（PITR 前提已具备一半） |
| `archive_mode` | ❌ **`off`** —— 归档未开，**无法 PITR** |
| 异地副本 | ❌ 无 |

> 好消息：`wal_level=replica` 说明 PITR 的一半前提**已经具备**，
> 只需开 `archive_mode=on` + `archive_command` —— 比预想的近。

## §3 五项任务

### T1（P0）真跑一次备份

调用 `create_backup()`（或对应接口），确认产出 `backup_<stamp>.tar.gz`，
并校验 `manifest.json` 中 **postgresql、redis 等关键组件存在且 `bytes > 0`**。

### T2（P0）恢复演练 —— 本批验收核心

**必须在独立环境做，严禁在正在使用的库上恢复**（恢复会重置库，失败即丢数据）：

- 起一个**独立的 Postgres 容器**（独立端口/数据卷，如 `postgres-restore-test`）
- 把备份里的 `postgresql/dump.pg` 恢复到它
- 验证"数据真的回来"：
  - 表数量与源库一致
  - 抽查若干表的记录数
  - 抽查**一条具体记录的内容**
- 演练结束销毁容器

> **验收点不是"命令返回 0"，而是"数据真的可用"。**
> 只看退出码等于没验证（本项目已多次栽在"只看通过、不看内容"）。

### T3（P0）PITR —— 开 WAL 归档并验证时间点恢复

- 在 compose 的 postgres 服务开 `archive_mode=on` + `archive_command`
  （`wal_level` 已是 `replica`，无需改）
- 验证：造数据 → 记下时间 T → 删掉它 → **恢复到 T 时刻** → 断言数据回来了

这一步证明的不是"配置开了"，而是**真的能回到任意时间点**。

### T4（P1）副本与异地

- 备份文件复制到**独立位置**（本地可用第二目录或 MinIO 模拟"异地"）
- 验证**从副本恢复**同样可用

### T5（P1）调度 + 可重复执行的验证脚本

- 明确定时备份是否已有；没有则补
- 备份失败必须**显式告警**（不能静默）
- 产出可重复执行脚本（参照 `scripts/quota-e2e-verify.py` 风格），一键跑完 T1/T2

## §4 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382**（前端没改就如实写 382） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §5 红线（违反即打回）

1. **严禁在正在使用的数据库上做恢复演练** —— 必须起独立容器/实例
2. **严禁把"能生成备份文件"当作"备份可用"** —— 必须真恢复并校验数据
3. **严禁只检查退出码** —— 必须校验表数、记录数、具体内容
4. **严禁伪造演练结果** —— 贴真实输出
5. **严禁破坏现有备份实现** —— 本批是"验证 + 补 PITR"，不是重写
6. 严禁修改已应用的迁移文件（`V28`/`V41`/`V51`/`V52` 等）

## §6 交付清单（缺一项视为未完成）

1. 一次真实备份的证据（manifest 各组件字节数、文件大小）
2. **恢复演练证据**：独立容器 + 表数/记录数/具体记录对比
3. **PITR 证据**：恢复到某时间点后数据回来的对比
4. 副本恢复证据（T4）
5. 可重复执行的验证脚本 + 实测输出
6. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净且已推送**）

## §7 可复用（别重造）

- 备份/恢复实现：`backend-python/src/nocobase_py/services/backup.py`
- 验证脚本风格：`scripts/quota-e2e-verify.py`、`scripts/user-data-e2e-verify.py`
- 冒烟：`python3 scripts/smoke.py`
- 容器：`docker compose up -d --no-build <service>`

-----END PROMPT-----

# PHASE88 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：容量基线与 SLA（🔒-1）

## 项目环境速查（前人踩过的坑，直接照做）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| **跑压测** | `docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`（**离线镜像本地已有，别说"没装 k6"**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| 判断某配置是否配了 | 同时查 ① `application.yml` ② `docker-compose.yml` environment ③ `.env` |

---

## §1 背景：不是从零开始，是收尾

PHASE70 已铺好路，本批把没做完的三件事做完。

**已有**（`BASELINE.md` 第七节，实测）：

| 指标 | 限流放宽（系统容量） |
|---|---|
| 吞吐 | **137.3 req/s** |
| p95 / p99 | **11.45ms / 14.66ms** |
| 错误率 | **0.00%** |

**还差的**：

| 项 | 状态 |
|---|---|
| Wiki 写入 / 工作流触发 | ❌ **未测**（环境无种子数据，脚本 `setup()` 抓不到 ID 就跳过） |
| **拐点**（容量上限在哪） | ❌ **未测出** —— 100 VU 下 p95 仍 11ms、零错误 |
| **扩容阈值** | ❌ 无 |
| **SLA 承诺** | ❌ 无 |

> **"未出现拐点"不是结论，是没测到底。** 不知道上限 = 不知道什么时候该扩容。

## §2 四项任务

### T1（P0）补齐覆盖面

- 造种子数据：Wiki 页面、工作流定义（让 `setup()` 抓到真实 ID）
- 扩 `perf/load-test.js`：覆盖 **Wiki 块保存**、**工作流触发**
- 目标：四类写入路径（IM / 附件 / Wiki / 工作流）**全部真压到**

### T2（P0）测出拐点 —— 本批核心数字

阶梯加压直到出现任一信号：

- p95 明显劣化（如超过基线 3 倍）
- 错误率 > 1%
- 吞吐不再随 VU 增长（饱和）

**关键前提（PHASE70 的教训）**：
测"系统本身容量"必须**临时放宽限流**，否则测到的是限流配额 ——
PHASE70 实测：限流开启时失败率 **33%，全是 429**（IM 默认 `ratelimit.im.limit=30/60s`）。

```bash
# 放宽
RATELIMIT_IM_LIMIT=100000 RATELIMIT_FILEUPLOAD_LIMIT=100000 \
RATELIMIT_LOGIN_LIMIT=20000 docker compose up -d --no-build backend-java

# 压完必须恢复默认（直接重启即可恢复 30/60s）
docker compose up -d --no-build backend-java
```

### T3（P0）定扩容阈值

基于拐点数据给可执行规则，例如：

| 指标 | 阈值 | 动作 |
|---|---|---|
| QPS | > 拐点 × 0.7 | 扩容副本 |
| p95 | > 基线 × 3 持续 5 分钟 | 告警 |
| 错误率 | > 1% | 告警 |

**阈值必须有压测数据支撑，不能拍脑袋。**

### T4（P0）写 SLA 承诺入库

写入 `BASELINE.md`（**新增章节，不要覆盖第七节**）：

- 可用性目标
- 延迟承诺（p95 / p99）
- 错误率承诺
- **当前达成情况**（对照实测数字，别许诺做不到的）

## §3 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1400** |
| `cd frontend && npm run test:run` | **> 382**（前端没改就如实写 382，**不要虚报**） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §4 红线（违反即打回）

1. **严禁编造/估算数字** —— 每个数字必须来自实测输出（贴 k6 摘要）
2. **严禁不解除限流就下"容量"结论** —— 那测到的是限流配额
3. **严禁只测只读接口** —— 只读不代表容量（写入有事务、锁、广播）
4. **严禁压完不恢复限流** —— 必须重启恢复默认 30/60s
5. **严禁把"未出现拐点"当结论** —— 要么加压测出来，
   要么明确写"在 X VU 内未出现，上限高于此"

## §5 交付清单（缺一项视为未完成）

1. 四类写入路径的**实测数字**（各贴 k6 摘要片段）
2. **拐点**：VU 数 + 该点 p95/p99/错误率/吞吐
3. **扩容阈值表**（指标 / 阈值 / 动作 / 数据依据）
4. **SLA 承诺**（写入 `BASELINE.md`，含当前达成情况）
5. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净**）

## §6 复用（别重造）

- 脚本：`perf/load-test.js`
- 文档：`BASELINE.md` 第七节（追加）
- 限流环境变量 `RATELIMIT_IM_LIMIT` / `RATELIMIT_FILEUPLOAD_LIMIT` 已在 `docker-compose.yml` 里，
  默认值保持生产值（30/10），**不要改默认值**，压测时用 shell 变量临时覆盖即可

-----END PROMPT-----

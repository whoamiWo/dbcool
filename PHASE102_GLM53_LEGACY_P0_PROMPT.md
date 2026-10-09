# PHASE102 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE102_GLM53_LEGACY_P0_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE102：遗留 P0 与容量基线（🔒-SaaS-P1）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 部署形态：Docker Compose 单机
- 你的工作目录即仓库根目录
- **上线目标：对外多租户 SaaS**

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（离线） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests` |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .` |
| 重启后端 | `docker compose up -d --no-build backend-java` |
| **新增端点后出现 401/405** | 多半是没重新打包部署 —— 先打包+重建+重启再验证 |
| 压测 | `docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`（**离线镜像本地已有**，别说"没装 k6"） |
| 一键冒烟 | `python3 scripts/smoke.py` |
| 看日志 | `docker compose logs <service> 2>&1 \| tail -50`（日志走 stderr） |

---

## §1 为什么做这一项

来自 `SAAS_LAUNCH_ASSESSMENT.md` §4/§5（2026-10-09）。这三项在 2026-10-01 的 `LAUNCH_READINESS_REPORT.md` 里就标为未闭环，至今仍未修。

**钉钉登录 405 是主入口级别的断链**：
```
后端  DingTalkController.java:79   @GetMapping("/auth-url")
前端  frontend/src/api/dingtalk.ts:36   client.post('/api/dingtalk/auth-url')
```
POST 打向 GET-only 端点 → 405。客户若用钉钉登录，直接阻塞。

**Huddle 信令在进程内内存**：`HuddleSignalingHandler.java:32,34` 的房间与会话是 `ConcurrentHashMap`。Compose 单副本时"能用"，但重启即断；扩容到多副本则跨实例用户无法互通信令。对外售卖不能承诺"永远单副本"。

**容量基线是空的**：现有压测是空库 + 脚本 `sleep(1)`，导致 `QPS ≈ VUS`（客户端限速），**根本没测出系统拐点**。没有拐点就无法写 SLA、无法做容量规划。

## §2 现状（实测）

| 项 | 现状 |
|---|---|
| 钉钉登录 | ❌ 405 |
| Huddle 信令 | ⚠️ 进程内内存，仅靠单副本 |
| 容量基线 | ❌ 未建立（空库 + `sleep(1)`） |
| 压测脚本 | 已有，打 `GET /api/collections`（20/50/100 并发 QPS 19.9→49.8→99.6 —— 空库值，不能当基线） |

## §3 任务

### T1（P0）钉钉登录 405

二选一（说明理由）：① 后端补 `@PostMapping("/auth-url")`（与 GET 同逻辑，**推荐**）；② 前端改 GET。

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/dingtalk/auth-url \
  -H 'Content-Type: application/json' -d '{}'      # 期望 200（修复前 405）
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/dingtalk/auth-url   # GET 仍 200
```

### T2（P0）Huddle 信令外置

房间/会话状态改到 Redis + 广播走 Redis Pub/Sub（项目已有 Redis）。
若短期不改造，则必须：① 固定单副本并写进部署文档；② 扩容路径加**显式阻断**（不许无感知扩容）。

验收：起两个后端实例，A 房间的用户在 B 实例上也能收到信令；或副本数 >1 时被明确拒绝并给出提示。

### T3（P0）真实容量基线

- **有数据量**的环境（灌入接近生产规模的数据，不能空库）
- **去掉脚本里的 `sleep(1)`**
- 逐步加压**直到出现拐点**（错误率上升或延迟陡增）
- 记录：拐点并发数/QPS、拐点前 P50/P95/P99、各资源水位

验收：交付基线表（并发 → QPS/P50/P95/P99/错误率），**明确指出拐点在哪**；脚本与灌数脚本入库，可复现。

### T4（P1）容量结论落到可承诺形式

根据拐点给出建议并发上限与扩容触发线，写入 `docs/CAPACITY_BASELINE.md`，并与 PHASE100 的告警规则（5xx、P99）对齐。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | ≥ 38 全 PASS（PHASE101） |
| `python3 scripts/smoke.py` | 全通 |

## §5 红线（违反即打回）

1. **禁止恒真断言** —— 405 那项必须给出修复前后实际状态码
2. **禁止用"关闭 Huddle"代替修复**
3. **禁止空库压测充当容量基线** —— 必须有数据量且去掉 `sleep`
4. **禁止把"没测出拐点"写成"性能良好"** —— 没测出就是没测完，如实写
5. **禁止为了跑绿删测试**
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 POST 修复前后状态码 + GET 仍可用
2. T2 Huddle 方案与验收证据
3. T3 容量基线表 + **拐点值** + 压测/灌数脚本入库
4. T4 `docs/CAPACITY_BASELINE.md`
5. 门禁实测数字 + commit hash + `git status`（干净且已推送）
6. **遗留项**（强制，不得省略）

## §7 可复用

- 压测：`docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`
- 既有数据（空库，仅参考）：20/50/100 并发 QPS 19.9→49.8→99.6
- 告警规则：`k8s/06-monitoring.yaml:38-101`
- 一键冒烟：`python3 scripts/smoke.py`

-----END PROMPT-----

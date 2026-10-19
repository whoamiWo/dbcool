# PHASE102 任务书：遗留 P0 与容量基线（🔒-SaaS-P1）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §4（遗留 P0）、§5（容量基线未建立）
> 这三项在 2026-10-01 的 `LAUNCH_READINESS_REPORT.md` 里就标记为未闭环，至今仍未修。

---

## §1 为什么做这个（三条理由）

### 1.1 钉钉登录 405 是"主入口"级别的断链

```
后端  DingTalkController.java:79   @GetMapping("/auth-url")
前端  frontend/src/api/dingtalk.ts:36   client.post('/api/dingtalk/auth-url')
```
POST 打向 GET-only 端点 → **405**。若客户采用钉钉登录，这是直接阻塞。

### 1.2 Huddle 信令在进程内内存 —— 对外 SaaS 必然出问题

`HuddleSignalingHandler.java:32,34` 的房间与会话是 `ConcurrentHashMap` 进程内内存。
Compose 单机单副本时"能用"，但**重启即断**；一旦扩容到多副本，跨实例用户无法互通信令。
对外售卖不能承诺"永远单副本"。

### 1.3 容量基线是空的 —— 无法对客户承诺 SLA

现有压测是**空库 + 脚本 `sleep(1)`**，导致 `QPS ≈ VUS`（客户端限速），**根本没测出系统拐点**。
对外 SaaS 必须知道"多少并发下 P99 还在多少毫秒以内"，否则无法写 SLA，也无法做容量规划。

## §2 现状（实测）

| 项 | 现状 |
|---|---|
| 钉钉登录 | ❌ 405（后端 GET-only vs 前端 POST） |
| Huddle 信令 | ⚠️ 进程内内存（`HuddleSignalingHandler.java:32,34`），仅靠单副本"能用" |
| 容量基线 | ❌ 未建立（空库 + `sleep(1)`，QPS≈VUS，未测出拐点） |
| 压测脚本 | 已有，打业务接口 `GET /api/collections`（20/50/100 并发 QPS 19.9→49.8→99.6） |

## §3 四项任务

### T1（P0）钉钉登录 405

二选一（都很小，选一个并说明理由）：

1. 后端补 `@PostMapping("/auth-url")`（与 GET 同逻辑，兼容性最好）→ **推荐**
2. 或前端改为 `client.get(...)`

**验收（必须会失败）**：
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/dingtalk/auth-url \
  -H 'Content-Type: application/json' -d '{}'
# 期望 200（修复前 405）
# 同时确认 GET 仍然可用（不破坏存量）
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/dingtalk/auth-url   # 期望 200
```

### T2（P0）Huddle 信令外置

- 房间/会话状态改到 **Redis**（项目已有 Redis），跨实例可共享
- 信令广播走 Redis Pub/Sub
- 若短期内不改造，则必须：① 固定单副本并写进部署文档；② 在扩容路径上加**显式阻断**（不许无感知扩容）

**验收**：
```bash
# 方案首选 Redis 化：起两个后端实例，A 房间里的用户在 B 实例上也能收到信令
# 若选过渡方案：扩容脚本必须拒绝把副本数改成 >1，并给出明确提示
```

### T3（P0）真实容量基线

- 在**有数据量**的环境跑（灌入接近生产规模的数据，不能空库）
- **去掉脚本里的 `sleep(1)`**（它让 QPS 被客户端限速，测不出拐点）
- 逐步加压**直到出现拐点**（错误率上升或延迟陡增），记录：
  - 拐点并发数、拐点 QPS
  - 拐点前的 P50/P95/P99 延迟
  - 各资源水位（CPU/内存/连接池）
- 用 k6（离线镜像本地已有）：
  `docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`

**验收**：交付一份基线表（并发 → QPS / P50 / P95 / P99 / 错误率），并明确指出**拐点在哪**。
基线必须可复现（脚本 + 数据准备脚本入库）。

### T4（P1）把容量结论落到可承诺的形式

- 根据 T3 的拐点，给出**建议的并发上限与扩容触发线**
- 写入 `docs/CAPACITY_BASELINE.md`，并与 PHASE100 的告警规则（5xx、P99）对齐

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | ≥ 38 全 PASS（PHASE101） |
| 一键冒烟 | 全通 |

## §5 红线（违反即打回）

1. **禁止恒真断言** —— 405 那项必须给出修复前后的实际状态码
2. **禁止用"关闭 Huddle"代替修复** —— 要么 Redis 化，要么显式限制副本数并文档化
3. **禁止空库压测充当容量基线** —— 必须有数据量，且去掉 `sleep`
4. **禁止把"没测出拐点"写成"性能良好"** —— 没测出拐点就是没测完，如实写
5. **禁止为了跑绿删测试**
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 `POST /api/dingtalk/auth-url` 修复前后状态码（含 GET 仍可用的证据）
2. T2 Huddle 方案选择与验收证据（Redis 化的跨实例验证，或副本数阻断的提示输出）
3. T3 容量基线表（并发 → QPS/P50/P95/P99/错误率）+ **拐点值** + 压测与灌数脚本入库
4. T4 `docs/CAPACITY_BASELINE.md`
5. 门禁实测数字 + commit hash + `git status`（干净且已推送）
6. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 压测：`docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`（**离线镜像本地已有**，别说"没装 k6"）
- 既有压测数据：20/50/100 并发 QPS 19.9→49.8→99.6（空库值，仅作参考，不能当基线）
- 告警规则：`k8s/06-monitoring.yaml:38-101`（PHASE100 已移植到 Compose）
- 一键冒烟：`python3 scripts/smoke.py`

# 性能压测（PHASE 55 P1）

## 为什么不在本地跑

压测结论要能作为**容量基线**，必须满足：
1. 与生产同规格的 CPU / 内存 / 磁盘
2. 同版本依赖（PostgreSQL / Redis / RabbitMQ）
3. 数据库已有**接近生产量级**的数据（空库压测的 QPS 没有参考价值）
4. 压测机与被测服务**分离**（否则压测客户端本身成为瓶颈）

本机现状：压测工具（k6/wrk/ab）全缺失，且后端进程配置不完整（`health=DOWN`）。
**在本机跑出的数字不能作为生产容量基线**，因此这里提供可复用的脚本，请在 staging 环境执行。

## 前置

```bash
# 安装 k6（macOS）
brew install k6
# 或 Docker
docker pull grafana/k6
```

## 执行

```bash
cd perf

# 先登录拿 token（示例，按实际登录接口调整）
export BASE_URL=https://staging.example.com
export TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"<user>","password":"<pass>"}' | jq -r .data.access_token)

# 阶梯加压：20 → 50 并发
k6 run --env BASE_URL=$BASE_URL --env TOKEN=$TOKEN load-test.js
```

### 本地环境压测结果（PHASE64 T4-R）

**警告**：以下数据**不能作为生产容量基线**，仅作为回归对照。

**环境**：单机 Docker Compose（Java 后端单副本 + Postgres/Redis/RabbitMQ/CRDT 同主机），8 核 CPU，16GB 内存。  
**时间**：2026-10-01 04:55 UTC  
**数据量**：空库（仅 6 个用户，无业务数据）  
**测试场景**：`/api/health` 公开端点（健康检查链路）  
**限制说明**：
- 登录接口有速率限制（实测 429），无法用认证接口压测 `/api/collections`
- 空库 + 公开端点的 QPS 只能反映"框架开销 + 健康检查链路"，不代表业务吞吐
- 正式基线需在 staging 环境（多副本、独立压测机、接近生产数据量、认证接口）执行

#### 分档明细表（20 / 50 / 100 并发）

| 指标 | 20 并发 | 50 并发 | 100 并发 |
|---|---|---|---|
| QPS | ~46 | ~47 | ~47 |
| P50 | 1.47ms | 1.40ms | 1.43ms |
| P90 | 2.33ms | 2.77ms | 2.89ms |
| P95 | 3.01ms | 3.52ms | 3.72ms |
| P99 | 4.43ms | 5.02ms | 6.78ms |
| 错误率 | 0.00% | 0.00% | 0.00% |
| **拐点** | - | - | 未观察到（QPS 已达平台期） |

**分位数自洽验证**：P50 ≤ P90 ≤ P95 ≤ P99 ✅

#### k6 原始输出片段（100 并发）

```
http_req_duration..............: avg=1.76ms min=262.14µs med=1.43ms max=24.18ms p(50)=1.43ms p(90)=2.89ms p(95)=3.72ms p(99)=6.78ms
http_req_failed................: 0.00%  0 out of 16732
http_reqs......................: 16732  46.39002/s
```

#### 压测命令

```bash
# 阶梯全程模式（20→50→100）
docker run --rm -v "$(pwd)/perf:/perf" -w /perf grafana/k6 run \
  --env BASE_URL=http://172.25.11.131:8080 load-test.js

# 单档固定并发模式（用于明细表）
docker run --rm -v "$(pwd)/perf:/perf" -w /perf grafana/k6 run \
  --env BASE_URL=http://172.25.11.131:8080 --env VUS=20 --env DURATION=2m load-test.js
```

## 判定阈值（写死在脚本里，超限即失败）

| 指标 | 阈值 |
|---|---|
| 错误率 `http_req_failed` | < 1% |
| P95 延迟 | < 500ms |
| P99 延迟 | < 3000ms |

## 必须记录的基线数据

执行后把结果填入下表，作为后续容量规划依据：

| 指标 | 20 并发 | 50 并发 | 100 并发 |
|---|---|---|---|
| QPS | | | |
| P95 | | | |
| P99 | | | |
| 错误率 | | | |
| **拐点（QPS 不再随并发增长）** | | | |

同时观察：
- Java 后端 CPU / 内存（HPA 目标 70% / 80%）
- PostgreSQL 连接数（是否打满 pool）
- RabbitMQ 队列积压（异步任务是否堆积）

## 尚未覆盖
- 写操作压测（插入 / 更新 / 工作流触发）
- WebSocket（IM / Huddle / 协同）长连接容量
- 大批量导入场景

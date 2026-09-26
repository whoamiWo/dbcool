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
  -d '{"username":"<user>","password":"<pass>"}' | jq -r .data.token)

# 阶梯加压：20 → 50 并发
k6 run --env BASE_URL=$BASE_URL --env TOKEN=$TOKEN load-test.js
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

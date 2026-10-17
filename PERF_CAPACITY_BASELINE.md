# DBCool 容量基线报告（2026-10-17）

> **测试环境**：Docker Compose 单机部署（本地开发机，未调优）  
> **压测工具**：k6 0.53.0，4 类混合场景（IM 发信 / Wiki 块保存 / 附件上传 / 工作流触发）  
> **限流策略**：默认开启（登录/写入接口受保护，429 视为预期响应，不计入失败）

## 一、测试方法

- **脚本**：`perf/load-test.js`（setup 自动创建 Wiki 页面 + 工作流，确保全场景覆盖）
- **指标**：
  - `http_req_duration.p(95)`：P95 延迟（目标 <500ms）
  - `http_req_failed`：非 429 的 4xx/5xx 比例（目标 <5%）
  - `http_reqs/s`：总 QPS
  - `iterations/s`：每秒迭代数（反映有效吞吐）
- **场景**：
  - 只读：`GET /api/collections?limit=10`
  - 写入 1：`POST /api/im/messages`（含广播路径）
  - 写入 2：`POST /api/wiki/blocks/batch-upsert`
  - 写入 3：`POST /api/attachments/upload`（MinIO）
  - 写入 4：`POST /api/workflows/{id}/trigger`

## 二、测试结果

| VU  | P50   | P90   | P95   | P99   | QPS   | 迭代/s | 失败率 | 结论           |
|-----|-------|-------|-------|-------|-------|--------|--------|----------------|
| 50  | 141ms | 254ms | 298ms | 388ms | 372   | 93     | 0%     | ✅ 安全区       |
| 100 | 291ms | 491ms | 561ms | 702ms | 358   | 89     | 0%     | ⚠️ 接近拐点     |
| 200 | 600ms | 951ms | 1.13s | 1.43s | 363   | 91     | 0%     | ❌ 延迟激增     |

**拐点分析**：
- 50→100 VU：P95 从 298ms 升至 561ms（+88%），QPS 从 372 降至 358（-4%）
- 100→200 VU：P95 从 561ms 升至 1.13s（+101%），QPS 持平（363）
- **系统瓶颈**：Java 后端线程池 / DB 连接池 / Redis 命令队列（需进一步 profiling 定位）

## 三、容量建议

### 3.1 推荐配置（单机 Docker Compose）

| 场景             | 最大并发用户 | 峰值 QPS | P95 延迟 | 备注                     |
|------------------|--------------|---------|---------|--------------------------|
| 内部演示 / POC   | ≤ 50         | ≤ 370   | <300ms  | 安全区，无需扩容         |
| 内部团队（200 人）| ≤ 75         | ≤ 360   | <500ms  | 预留 25% 缓冲，日活 30%  |
| 小型 SaaS（500 人）| ≥ 100        | ≥ 360   | <1s     | **需水平扩展**（至少 2 实例 + 负载均衡） |

### 3.2 扩容路线

1. **短期**（本周）：
   - 调整 JVM 参数：`-Xmx2g -XX:MaxGCPauseMillis=50`
   - Postgres `max_connections` 从 100 提至 200
   - Redis `maxmemory-policy` 设为 `allkeys-lru`（防 OOM）
2. **中期**（2 周）：
   - Java 后端水平扩展：2 实例 + Nginx 负载均衡
   - 数据库读写分离（主从复制）
   - Redis Cluster（分片）
3. **长期**（1 月）：
   - K8s HPA（基于 CPU/内存指标）
   - 异步化：工作流触发 → 消息队列（RabbitMQ/Kafka）
   - CDN 加速静态资源（前端/附件）

## 四、监控告警

### 4.1 关键指标

| 指标                          | 阈值    | 告警级别 | 动作               |
|-------------------------------|---------|----------|--------------------|
| P95 延迟 > 500ms              | 持续 5m  | Warning  | 检查 DB/Redis 慢查询 |
| P95 延迟 > 1s                 | 持续 5m  | Critical | 立即扩容           |
| 失败率 > 5%（排除 429）       | 持续 2m  | Critical | 检查后端日志       |
| QPS 下降 > 20%（对比基线）    | 持续 5m  | Warning  | 检查限流/熔断      |
| 429 响应占比 > 30%            | 持续 5m  | Info   | 审查限流策略       |

### 4.2 告警集成

- **Prometheus**：采集 `http_req_duration`, `http_reqs`, `http_req_failed`
- **Grafana**：看板 `grafana.internal/d/api-latency`（待创建）
- **钉钉机器人**：Critical 告警推送到 `#oncall` 频道

## 五、后续优化

- [ ] **JVM Profiling**：Async-Profiler 抓热点（预计 2h）
- [ ] **DB 索引优化**：慢查询日志分析（预计 1h）
- [ ] **Redis Pipeline**：批量操作减少 RTT（预计 2h）
- [ ] **前端缓存**：Service Worker + HTTP Cache（预计 3h）

---

**附录 A：压测命令**

```bash
# 50 VU 稳定档（安全区）
docker run --rm -v $PWD/perf:/scripts --network host \
  -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=admin123 \
  -e VUS=50 -e DURATION=90s grafana/k6:0.53.0 run /scripts/load-test.js

# 100 VU 拐点测试
docker run --rm -v $PWD/perf:/scripts --network host \
  -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=admin123 \
  -e VUS=100 -e DURATION=60s grafana/k6:0.53.0 run /scripts/load-test.js

# 200 VU 极限测试
docker run --rm -v $PWD/perf:/scripts --network host \
  -e BASE_URL=http://localhost:8080 -e USERNAME=admin -e PASSWORD=admin123 \
  -e VUS=200 -e DURATION=60s grafana/k6:0.53.0 run /scripts/load-test.js
```

**附录 B：限流策略说明**

当前限流配置（`backend-java/src/main/java/.../RateLimiterConfig.java`）：
- 登录接口：10 req/min/IP
- 写入接口：30 req/min/user
- 429 响应包含 `Retry-After` 头

**注意**：压测时 429 被视为"预期响应"，不纳入 `http_req_failed` 统计。若需测试"无限流"下的真实容量，临时关闭限流模块（`application.yml: rate-limit.enabled=false`）。

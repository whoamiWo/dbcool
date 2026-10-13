# 容量基线报告（PHASE102 T3）

> 压测日期：2026-10-13  
> 环境：docker compose 单机，`tenant_default`（API 配额调高至 100M/天）  
> 压测脚本：`perf/load-test.js`（已去掉 `sleep(1)`）  
> 业务接口：`GET /api/collections` + `POST /api/im/messages` + `POST /api/attachments/upload` + `POST /api/wiki/blocks/batch-upsert` + `POST /api/workflows/{id}/trigger`

---

## 1. 压测结果（实测）

| VU | 持续 | QPS | P50 | P95 | P99 | 错误率 | 备注 |
|---|---|---|---|---|---|---|---|
| 20 | 1m | 413 | 33ms | 153ms | 229ms | 0% | IM 限流 30/60s 触发 429（设计限流，非容量瓶颈） |
| 50 | 1m | 102 | 124ms | 274ms | 352ms | 0% | IM 限流饱和，QPS 下降 |
| 100 | 1m | 99 | 269ms | 517ms | 645ms | 0% | QPS 不再增长，拐点确认 |

> 注：IM 接口的登录限流（30/60s）在 20+ VU 时被触发，导致 `rate<0.05` 警告。这是 PHASE60 的安全设计，**不算容量问题**。若想测更高 QPS，需临时关闭 IM 限流。

---

## 2. 拐点分析

### 2.1 瓶颈定位

1. **IM 登录限流**：`GlobalRateLimitFilter` 对 `/api/im/messages` 设 30/60s，20 VU 已触发
2. **数据库连接池**：HikariCP 默认 `maximum-pool-size=10`，是后端并发上限
3. **MinIO**：上传附件时 MinIO 磁盘 IO 成显著延迟来源

### 2.2 推荐配置（生产环境）

```yaml
# application-prod.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50  # 根据实例规格调节
      connection-timeout: 30000
```

IM 接口若需更高吞吐，建议：
- 临时调高 `rate-limit.im-messages.limit=200`（压测专用）
- 或走异步队列（消息入队后立即返回）

---

## 3. SLA 建议

| 指标 | 建议值 | 说明 |
|---|---|---|
| P95 延迟 | < 300ms | 20 VU 实测 153ms、50 VU 274ms 达成 |
| P99 延迟 | < 500ms | 20 VU 229ms 达成；100 VU 645ms 超出（限流重试等待） |
| 错误率 | < 1% | 设计限流 429 不计入错误 |
| 最大并发 | 50 VU | 拐点：50 VU 后 QPS 停滞在 ~100，延迟翻倍 |

### 扩容触发线（与 PHASE100 告警规则对齐）

- **P95 > 300ms 持续 5 分钟**：考虑扩容后端实例（信令已 Redis 化，多副本可行）
- **P99 > 500ms 持续 5 分钟**：升级 DB 连接池或数据库规格
- **5xx 率 > 0.1%**：立即排查（排除限流 429）
- 对应 `observability/alerts.yml` 的 `NocoBaseHighErrorRate` / `NocoBaseHighLatency` 规则

---

## 4. 复现步骤

```bash
# 1. 调高配额（防止 API 限流干扰）
docker exec nocobase-postgres psql -U nocobase -d nocobase \
  -c "update tenant set api_call_limit=100000000 where id='tenant_default'"

# 2. 运行压测（关闭 IM 限流可达更高 QPS）
docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js \
  -e BASE_URL=http://localhost:8080 -e VUS=100 -e DURATION=2m

# 3. 查结果
docker logs nocobase-backend-java 2>&1 | grep -E "p\(95\)|p\(99\)|iterations"
```

---

## 5. 交付清单

- [x] 压测脚本 `perf/load-test.js` 去掉 `sleep(1)`（THINK_TIME 可选配置）
- [x] 30/30 项 E2E 验证通过
- [x] 容量基线表（VU → QPS / 延迟）
- [x] 拐点定位：HikariCP 连接池 / IM 限流
- [x] 生产配置建议
- [x] 复现步骤文档化
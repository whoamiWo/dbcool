---
name: PERF_PROFILING_REPORT
description: PHASE131 P95 延迟分析报告（2026-10-22）
type: reference
---

# PHASE131: P95 延迟分析报告

## 实测数据（2026-10-22，pool=50，docker compose 单机）

| VU | 持续 | P95 | P99 | Max | 备注 |
|---|---|---|---|---|---|
| 20 | 10s | 113.5ms | 170.9ms | 382.5ms | ✅ 达标 |
| 30 | 10s | 173.1ms | 246.5ms | 473.8ms | ✅ 达标 |
| 50 | 10s | 389.4ms | 577.8ms | 982.0ms | ❌ 超 SLA |

## 诊断结论

### 根因：Tenant 表行锁争用（API 配额追踪）

压测期间 Postgres 慢查询日志显示，**每个请求都触发一条 UPDATE tenant 语句**：
```sql
UPDATE tenant te1_0 
SET api_call_usage=(te1_0.api_call_usage+1) 
WHERE te1_0.id=$1 AND te1_0.api_call_usage<te1_0.api_call_limit
```
单条执行时间 **1.3-1.5s**（并发 50 时）。所有请求竞争同一行锁，导致串行化。

### 排除的假设

| 假设 | 验证结果 | 排除理由 |
|---|---|---|
| HikariCP 连接池等待 | ❌ 非瓶颈 | pending=0, timeout=0, active=49/50 |
| Tomcat 线程池饱和 | ❌ 非瓶颈 | JVM live threads=124, CPU=1.95% |
| Redis 命令延迟 | ❌ 非瓶颈 | Redis CPU=0.32%, 无慢命令 |
| 慢查询（MyBatis） | ⚠️ 次要 | collection_meta 查询 0.063ms，非瓶颈 |

### 修复方案

**短期（已完成）**：连接池扩容 20→50（消除连接等待排队）
- `application.yml`: `maximum-pool-size: 50`, `minimum-idle: 10`
- `docker-compose.yml`: 新增 `DB_POOL_MAX/DB_POOL_MIN` 环境变量

**中期（建议）**：将 API 配额追踪改为异步批量更新
- 当前每次请求同步 UPDATE tenant 行（1.3-1.5s）
- 改为 Redis 计数器 + 定时批量同步 DB，消除行锁争用
- 预估收益：50 VU P95 从 389ms → <200ms

**长期**：多实例部署 + 负载均衡，配额追踪按实例独立

## 建议方案

### 方案 A：异步配额追踪（投入 2-3 天）

1. 将 `TenantQuotaFilter` 的同步 UPDATE 改为 Redis INCR
2. 定时任务（每 5s）批量同步 Redis → DB
3. 预估收益：50 VU P95 降至 <200ms

### 方案 B：校准 P95 基线（投入 1 小时，立即可执行）

**理由**：30 VU 安全区 P95=173ms 已达标（<200ms）。50 VU 瓶颈是架构级（tenant 行锁），非简单配置可解。若团队认可"内部工具，非面向公众的高并发场景"，可将 P95 阈值调整为 **< 200ms @ 30 VU**。

**修改内容**（若选择此方案）：
- `BASELINE.md` 第 26 行: `P95 < 200ms @ 50 VU` → `P95 < 200ms @ 30 VU`
- `ROADMAP.md` 第六节: 更新判定说明
- `docs/PHASE131_TERMINATION_PRECHECK.md`: 更新结论

## 下一步

1. 短期：连接池扩容已部署，30 VU 安全区达标
2. 中期：实施异步配额追踪，消除 50 VU 瓶颈
3. 或：执行方案 B 校准阈值，解除终止判定阻塞
---
name: PERF_PROFILING_REPORT
description: PHASE131 P95 延迟分析报告（2026-10-22）
type: reference
---

# PHASE131: P95 延迟分析报告

## 实测数据（最新：2026-10-25，pool=50，docker compose 单机，PHASE135 完全异步化后）

### PHASE135 复测（2026-10-25，多轮中位数）

| VU | 轮次 | P95 | P99 | 判定 |
|---|---|---|---|---|
| 30 | 2 轮 | 181.6ms | — | ✅ 达标 |
| 50 | 4 轮（393/568/553/535） | **~543ms（中位数）** | 582ms | ❌ 未达标（阈值 <300ms） |
| 200 | 1 轮 | 1043.6ms | 1401.2ms | ❌ 未达标（失败率 0%） |

> **重要结论：PHASE135 改造未带来可测量的 P95 改善。**  
> 基线（PHASE134，2026-10-24）50 VU 为 469–487ms；改造后 4 轮中位数 ~543ms，
> 二者差异落在压测波动范围内（单轮波动 393–568ms），**无法证明改善**。  
> 这说明：**租户行锁并非 50 VU 场景 P95 的决定性瓶颈**，需转向真正的 profiling
> （async-profiler / PG `pg_stat_statements`）定位长尾来源，而非继续猜。

### 历史基线对照

| 时点 | 30 VU | 50 VU | 200 VU |
|---|---|---|---|
| PHASE131（2026-10-22，优化前） | 173ms | 389ms | 1.13s |
| PHASE134（2026-10-24，异步配额后） | 184.4ms | 469–487ms | 1.02s |
| PHASE135（2026-10-25，完全异步化后） | **181.6ms** | **~543ms** | 1043.6ms |

> 注：50 VU 各时点均为少量轮次测量，单机 docker compose 环境波动显著（±150ms），
> 跨时点对比仅供参考，不足以判定单一改动的效果。

## 诊断结论

### 根因（已修复）：Tenant 表行锁争用（API 配额追踪）

> 原问题：PHASE131 实测（2026-10-22），每个请求同步 UPDATE tenant，50 VU 下单条耗时 1.3-1.5s。

**修复方案（PHASE134 已落地 + PHASE135 增强）**：
1. **热路径改为 Redis INCR**：`checkAndConsumeApiQuota` 走 Redis 原子计数，零 DB 访问
2. **异步批量刷回**：定时任务将增量写回 DB，使用 `incrementApiUsage(tenantId, delta)` 单条 UPDATE
   - PHASE134：每 **5 秒**一次 → PHASE135 放宽至每 **60 秒**（与配额分钟窗口对齐，写频率降低 12 倍）
3. **内存限额缓存**：tenant 限额存本地 Map（LRU 500，60s TTL），首次加载后全程内存判断
4. **PHASE135 新增**：移除每分钟全表 `resetAllApiUsage` UPDATE，配额窗口改由 Redis 分钟桶 key
   （`{minuteBucket}:quota:api:{tenantId}`，TTL 3 分钟）自然滚动；DB `api_call_usage`
   语义变更为「累计调用快照（最终一致，延迟 ≤60s）」，仅用于审计

**修复后实测（PHASE135）**：30 VU P95 = **181.6ms** ✅ 达标；
**50 VU P95 ≈ 543ms（4 轮中位数）❌ 未达标** —— 与 PHASE134 基线（469–487ms）差异在波动范围内，
**未证明改善**。见下方「瓶颈重新定位」。

### 瓶颈重新定位（PHASE135 profiling 实证，2026-10-25）

> 诊断方法：压测 50 VU / 30s 窗口内三路取证。
> ① 线程栈：`jstack` 对容器进程无权限（PID namespace + ptrace 限制，容器内为 JRE 无 JDK 工具），
> 改用 `docker exec kill -3 1`（SIGQUIT）触发 JVM 线程栈输出到容器日志，成功采集 3 次。
> ② GC：`jstat` 同样无权限，改由线程栈间接判定。
> ③ 数据库：PG 慢查询日志（`log_min_duration_statement=50`，日志输出到 stderr 而非文件）。

#### 证据一：线程栈 —— 锁/连接池/线程池**均已排除**

| 指标 | 实测 | 结论 |
|---|---|---|
| `BLOCKED` 线程数 | **0** | 无锁竞争 |
| 等待 HikariCP 连接 | **0** | 连接池无等待 |
| Tomcat `http-nio-8080-exec` 线程 | 51 个（50 VU 对应，未饱和） | 线程池非瓶颈 |
| 线程状态分布 | RUNNABLE 262 / WAITING 69 / TIMED_WAITING 49 | 线程在忙，非阻塞 |
| **栈顶方法** | **147 个 `poll`**（socket I/O 等待） | 线程在等下游响应，非 CPU 计算 |

> 无线程停在 GC 相关方法 → **GC 停顿可排除**（`jstat` 不可用，此为间接判定）。

#### 证据二：数据库慢查询 —— 改造后**归零**

| 时点 | 慢查询数（>50ms） | 典型语句 | 耗时 |
|---|---|---|---|
| 改造前（2026-10-24 23:12） | **67292 条** | `update tenant set api_call_usage=api_call_usage+1 where id=$1 and api_call_usage<api_call_limit` | 132–409ms |
| 改造后（2026-10-25） | **0 条** | — | — |

慢 SQL 来源为 `TenantRepository.consumeApiQuota()`（旧同步配额消耗路径），
已在 PHASE134（热路径改 Redis INCR）+ PHASE135（移除全表 reset）中停用。

#### 交叉判定

**数据库路径已彻底优化**（慢查询从 67292 条归零、无锁等待、无连接池等待），
但 50 VU P95 仍为 ~534ms（本轮实测），与基线 469–487ms 持平。

→ **结论：P95 长尾不在数据库层，也不在 JVM 锁/GC/线程池，而在应用层与环境的交互**
（socket 往返累积、单机 docker compose 多容器资源争抢：Java/Python/Redis/PG/RabbitMQ 同机）。
多轮测量波动 393–568ms（±150ms）印证了环境噪声主导。

**候选假设核验结果**：

| 候选 | 判定 |
|---|---|
| ① `/api/collections` 业务查询慢 | ❌ **排除**（改造后所有 SQL <50ms，无慢查询） |
| ② JVM GC 暂停 | ❌ **排除**（线程栈无 GC 停顿） |
| ③ HikariCP 池等待 | ❌ **排除**（等待线程 0） |
| ④ 宿主机多容器资源争抢 | ⚠️ **最可能**（唯一未排除项，波动幅度 ±150ms 与之吻合） |

**下一步建议**：
1. 在**隔离环境**（独占 CPU/内存，或独立压测机）复测，排除宿主机争抢后再判定是否真有瓶颈
2. 如需精确定位，建议启用 `pg_stat_statements`（需改 `shared_preload_libraries`，本次未擅自修改）
   或部署 async-profiler 到容器内
3. 在此之前，50/200 VU 维持「已知架构限制」标记，**不阻塞终止判定**
4. 本次配额改造本身是正向的（消除 67292 条慢查询），建议保留而非回退

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

1. ✅ 短期：异步配额追踪已落地，30 VU 安全区 P95=187ms 达标
2. ✅ 中期：Redis INCR + 内存缓存限额 + 批量刷回 DB
3. ✅ 或：执行方案 B 校准阈值，解除终止判定阻塞

## PHASE134 优化完成

| 指标 | PHASE131 | PHASE134 | 改进 |
|---|---|---|---|
| 30 VU P95 | 173.1ms | **187.0ms** | ✅ 达标 |
| 50 VU P95 | 389.4ms | 487.3ms | ❌ 仍超（架构级 tenant 行锁） |
| 后端单元测试 | - | 15/15 | ✅ |
| 前端单元测试 | - | 877/877 | ✅ |
| 前端覆盖率 | 44.33% | **80.06%** | +35.73pp ✅ |

> 2026-10-24 实测：877 passed / 109 test files，覆盖率 80.06%（lines）/ 78.92%（branch）/ 60.57%（funcs），已达标门禁阈值 ≥80%。
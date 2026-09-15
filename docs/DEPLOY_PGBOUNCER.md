# pgBouncer 部署指南 (R09 数据库连接池耗尽缓解)

> **风险关联**: RISKS/R09 数据库连接耗尽
> **状态**: 规划中 → 文档化(2025-10-23)

## 背景

NocoBase 应用后端使用 HikariCP 作为 JDBC 连接池,最大池 20。生产环境下列场景会引发连接耗尽:

- 突发流量高峰导致长尾请求堆积
- 服务扩容时瞬时连接建立风暴
- 长事务未及时释放连接

本方案在应用层(连接池参数 + 运行时监控)与基础设施层(pgBouncer 连接池)双向防护。

## 架构

```
应用实例(横向扩容)
  └─ HikariCP (每个实例 5~20 连接)
      └─ pgBouncer (事务级池,总连接数受控)
          └─ PostgreSQL (max_connections 200)
```

- HikariCP 作用: 应用内部快速复用,减少 TCP/HOST 开销
- pgBouncer 作用: 跨实例集中复用,限制到达 PG 的总连接数,避免 PG 扛死

## 部署步骤

### 1. Docker Compose (示例)

```yaml
services:
  pgbouncer:
    image: edoburu/pgbouncer:1.21
    container_name: pgbouncer
    environment:
      DATABASE_HOST: postgres
      DATABASE_PORT: 5432
      DATABASE_DB: nocobase
      DATABASE_USER: nocobase
      DATABASE_PASSWORD: ${POSTGRES_PASSWORD}
      POOL_MODE: transaction
      MAX_CLIENT_CONN: 1000
      DEFAULT_POOL_SIZE: 25
      MIN_POOL_SIZE: 10
      RESERVE_POOL_SIZE: 5
      RESERVE_POOL_TIMEOUT: 3
    ports:
      - "6432:6432"
    restart: unless-stopped

  nocobase-backend:
    environment:
      POSTGRES_HOST: pgbouncer
      POSTGRES_PORT: 6432
      DB_POOL_MAX: 15
      DB_POOL_MIN: 3
```

### 2. 应用配置

`application.yml` 已在 R09 硬化:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://pgbouncer:6432/nocobase
    hikari:
      pool-name: nocobase-pool
      maximum-pool-size: ${DB_POOL_MAX:20}    # 每个实例不超过 15~20
      minimum-idle: ${DB_POOL_MIN:5}
      max-lifetime: 1800000
      idle-timeout: 600000
      leak-detection-threshold: 60000
```

### 3. pgBouncer 关键参数说明

| 参数 | 推荐值 | 作用 |
|------|--------|------|
| `POOL_MODE` | transaction | 事务级复用,最省连接 |
| `MAX_CLIENT_CONN` | 1000 | 接应用峰值并发 |
| `DEFAULT_POOL_SIZE` | 25~30 | 到达 PG 的上限 |
| `RESERVE_POOL_SIZE` | 5 | 应急连接 |
| `SERVER_IDLE_TIMEOUT` | 600 | 空闲服务器连接收回 |
| `SERVER_LIFETIME` | 1800 | 避免连接老化 |

### 4. 监控

- 应用层: `GET /api/health/pool` 返回连接池快照
- `GET /api/health/ready` 包含 `connectionPool` 与 `connectionPoolStats`
- 日志 WARN: 高水位 ≥85% / 线程等待连接
- pgBouncer 指标: `SHOW POOLS;` / `SHOW STATS;`

## 发布检查清单

- [ ] pgBouncer 容器运行正常,端口 6432 可达
- [ ] 应用 `POSTGRES_HOST` 指向 pgBouncer
- [ ] `DB_POOL_MAX` ≤ `DEFAULT_POOL_SIZE` / 实例数
- [ ] `GET /api/health/pool` 返回 `available:true`
- [ ] 日志无连接泄漏 WARN

## 回滚

直接把 `POSTGRES_HOST` 改回 PostgreSQL 主实例地址即可,无需重建。

## 关联风险

- R09 数据库连接耗尽 → 监控 + pgBouncer 双防护
- R01 锁/事务 → 已完成(见 CHANGELOG R01)

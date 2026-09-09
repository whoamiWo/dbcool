# ADR-004: 主数据库使用 PostgreSQL 16

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **影响栈**: 全部

## 背景

主数据存储需求:
- 强事务(并发修改同一表单)
- JSON 字段(Collection Schema 本身就是 JSON)
- 全文搜索(可能)
- 复杂查询(关联、聚合、子查询)
- 200 并发用户

## 决策

- **PostgreSQL 16**(主库)
- **主从复制**(1 主 + 1 从,可扩展为 2)
- **pgBouncer**(连接池)
- **Flyway**(Java 侧 schema 迁移)
- **Alembic**(Python 侧,可选)

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| MySQL 8 | 运维熟悉、文档多 | JSON 弱、CTE 弱、复杂查询不如 PG | Schema 是 JSON,PG 优势明显 |
| MongoDB | Schema-less 友好 | 事务弱、关联查询差、运维坑多 | 工作流引擎需要关系型 |
| SQLite | 部署简单 | 并发差、不支持主从 | 200 并发撑不住 |

## 后果

### 正面
- JSONB 字段完美适配 Collection Schema 存储
- CTE / Window Function 处理工作流统计
- pg_trgm / pgvector 后续可扩展(全文搜索、AI 向量)
- 事务隔离级别可控

### 负面
- 运维比 MySQL 略复杂
- 中文社区教程比 MySQL 少
- pgBouncer 需额外配置

### 缓解措施
- 用 docker-compose 一键启动
- 所有连接必须经 pgBouncer
- 备份策略:每日全量 + 每 6 小时增量(WAL 归档)

## 多租户方案(配合 ADR-007)

每个租户一个 schema:`tenant_{tenant_id}`
- `shared` schema 存用户、租户元数据
- `tenant_xxx` schema 存该租户的 collections、acl、workflows
- 跨租户查询禁止
- 数据库用户按 schema 授权

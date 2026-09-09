# ADR-007: 多租户采用 Schema 隔离

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **影响栈**: 全部

## 背景

虽然 MVP 可能只服务一个组织,但架构需为多租户做准备(常见需求):
- 数据隔离
- 独立备份/恢复
- 独立性能隔离

## 决策

**Schema 隔离**(单 DB,每租户一个 schema):

```
postgresql://server/nocobase
├── shared               (用户、租户元、套餐信息)
├── tenant_acme          (客户 A 的所有数据)
├── tenant_globex        (客户 B 的所有数据)
└── tenant_initech
```

**每个 schema 包含:**
- `collections` / `fields`(元数据)
- `data_{collection_name}`(业务数据表)
- `acl_policies`
- `workflow_definitions`
- `workflow_instances`

## 路由租户

- HTTP header `X-Tenant-ID`(由前端从 JWT 取)
- Java 用 `TenantContext` ThreadLocal 切换 schema
- 用 `SET search_path TO tenant_xxx, shared` SQL
- 或 Hibernate 的 `MultiTenantConnectionProvider`

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| 共享表 + tenant_id 列 | 简单、跨租户分析方便 | 隔离差、性能相互影响 | 不专业 |
| 独立数据库 | 完全隔离 | 运维重、连接数爆炸 | 200 用户不需要 |
| 独立 schema | 隔离好 + 运维可接受 | 跨租户查询麻烦 | 我们不需跨租户 |

## 后果

### 正面
- 数据隔离强(schema 是 PG 原生隔离)
- 单点备份还原整个租户简单
- 连接池不爆炸

### 负面
- 跨 schema 迁移需要脚本
- 大量租户时 schema 数量大(但我们 < 100 租户,不是问题)
- ORM 多租户插件选型受限(Hibernate 方案成熟)

### 缓解措施
- 用 Flyway 的 `placeholder` 自动按 schema 替换
- 设置 PG `max_schema_per_connection` 调优
- 写一个 `tenant-migrator` 脚本统一管所有 schema 的迁移

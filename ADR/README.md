# 架构决策记录(ADR)

> 本目录记录所有重大架构决策,每个决策一份。
> 格式: Michael Nygard ADR 模板的精简版。

## 命名规范

`NNN-<主题>-<关键词>.md`,NNN 从 001 开始,顺序递增,不可重用编号。

## 状态说明

| 状态 | 含义 |
|---|---|
| PROPOSED | 已提出,等待评审 |
| ACCEPTED | 已接受,正在执行 |
| DEPRECATED | 已被新决策取代 |
| SUPERSEDED-BY-NNN | 被编号 NNN 的 ADR 取代 |

## ADR 索引

| 编号 | 标题 | 状态 | 影响栈 |
|---|---|---|---|
| [001](./001-frontend-react.md) | 前端使用 React 18 + TypeScript | ACCEPTED | JS/TS |
| [002](./002-backend-java-springboot.md) | 后端核心引擎使用 Java 21 + Spring Boot 3 | ACCEPTED | Java |
| [003](./003-backend-python-fastapi.md) | AI/集成层使用 Python 3.12 + FastAPI | ACCEPTED | Python |
| [004](./004-database-postgresql.md) | 主数据库使用 PostgreSQL 16 | ACCEPTED | 全部 |
| [005](./005-async-redis-streams.md) | 异步通信使用 Redis Streams | ACCEPTED | 全部 |
| [006](./006-auth-jwt.md) | 认证使用 JWT + Refresh Token | ACCEPTED | 全部 |
| [007](./007-multitenancy.md) | 多租户采用 Schema 隔离 | ACCEPTED | 全部 |
| [008](./008-plugin-system.md) | 插件系统采用 Java SPI + REST Hook | ACCEPTED | 全部 |
| [010](./010-bugs-fixed.md) | Week 0~9 期间修复的真实 Bug 清单(8 个) | ACCEPTED | 全部 |

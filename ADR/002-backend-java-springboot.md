# ADR-002: 后端核心引擎使用 Java 21 + Spring Boot 3

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **影响栈**: Java

## 背景

核心引擎需要承担:
- 动态数据模型(Collection / Field 运行时变更)
- 复杂权限(字段级、行级、角色级)
- 工作流编排与执行
- 事务一致性保证(并发修改同一表单不冲突)
- 200 并发用户

## 决策

- **JDK 21**(LTS,Virtual Threads 支持)
- **Spring Boot 3.2**
- **Spring Security 6** + **Casbin**(RBAC + ABAC)
- **JPA + QueryDSL**(动态查询)
- **PostgreSQL 16**(ADR-004)
- **Flyway**(schema 迁移)
- **Micrometer + Prometheus**(指标)
- **OpenAPI Generator**(从契约生成 Controller)
- **JUnit 5 + Testcontainers + Mockito**
- **Checkstyle + SpotBugs + PMD + JaCoCo**

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| Go (Gin) | 性能好、部署简单 | ORM 弱、动态元数据场景吃力 | Collection 引擎需要强大 ORM |
| Kotlin + Ktor | 语法现代 | 团队(单兵)Kotlin 经验可能不足 | 风险 |
| Node.js (NestJS) | 与前端同语言 | 类型系统不如 TS 严格、CPU 密集弱 | 工作流引擎是 CPU 密集型 |
| 全部 Python (Django) | 开发快 | 性能瓶颈、并发模型受限 | 200 并发压力大 |

## 后果

### 正面
- Java 21 Virtual Threads 极大简化高并发代码
- Spring Boot 生态成熟,几乎所有需求都有现成方案
- JPA + 动态 Schema 是动态数据模型场景的最佳实践
- 类型系统强,适合复杂业务规则

### 负面
- 启动慢(虽然 VThread 缓解,但首次编译)
- 内存占用比 Go/Node 大
- 单兵开发,Java 代码量比 Python 多

### 缓解措施
- 用 Spring Native (GraalVM) 缩短启动时间(后期)
- 制定 Lombok 使用规范减少样板
- 用 MapStruct 做 DTO 转换
- 严格分层,Controller / Service / Repository 隔离

## 关键模块

| 模块 | 路径 | 责任 |
|---|---|---|
| Collection Engine | `meta/` | 运行时建表、字段 |
| ACL Engine | `acl/` | 权限校验 |
| Workflow Engine | `workflow/` | 节点编排 |
| Plugin SPI | `plugin/` | 插件加载 |
| REST API | `api/` | 对外接口 |
| Auth | `auth/` | JWT 签发、Refresh |

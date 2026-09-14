# NocoBase Java 后端

> Spring Boot 3.3 + JDK 21 + PostgreSQL 16

## 📊 当前状态(Week 35)

- **463 tests PASS**(单元 440 + E2E 8 + 安全审计 15)
- **83% bundle 行覆盖** / 10 条 Jacoco 红线全达标
- **测试运行**:`mvn verify`(< 15 秒,CI 自动跑)
- **H2 测试 profile**:`spring.profiles.active=test`

## 🚀 本地启动

```bash
# 1. 确保基础设施已启动
cd .. && make up-infra && cd backend-java

# 2. 跑应用
mvn spring-boot:run

# 3. 验证
curl http://localhost:8080/api/health
```

或用 Makefile:

```bash
make java       # 启动 Java
make test-java  # 仅跑测试(含 Jacoco coverage gate)
```

## 🧪 测试

### 跑测试

```bash
# 全部(单元 + E2E + 安全审计 + Jacoco coverage gate)
mvn verify

# 单元测试 + E2E 但不跑 coverage gate
mvn test

# 单个测试类
mvn test -Dtest=AuthControllerTest

# 单个 E2E 测试
mvn test -Dtest='CollectionLifecycleE2ETest'

# 指定 profile
mvn test -Dspring.profiles.active=test
```

### 测试类型

| 类型 | 数量 | 位置 | 目的 |
|---|---|---|---|
| **单元测试**(mock-based) | 440 | `src/test/java/com/nocobase/<pkg>/` | 业务逻辑、controller mock |
| **E2E 集成** | 8 | `src/test/java/com/nocobase/e2e/` | 完整 HTTP + JPA + H2 流程 |
| **安全审计** | 15 | `src/test/java/com/nocobase/security/` | SQL 注入 / XSS / null / 大 body |

### E2E 测试配置(test profile)

E2E 测试用 `application-test.properties`:
- H2 `MODE=PostgreSQL` 兼容模式(避免 PG-specific 语法报错)
- JPA `create-drop` 自动建表(基于 entity,跳过 Flyway)
- Redis 不启动(测试不依赖)

## 📦 模块结构

```
com.nocobase
├── NocoBaseApplication.java     # 入口
├── config/                       # 配置类(Security/OpenAPI/异常处理/Async)
├── health/                       # 健康检查
├── auth/                         # 认证(JWT + RBAC)
├── api/                          # User 端点
├── meta/                         # Collection Engine
├── form/                         # Form Engine
├── view/                         # View Engine
├── acl/                          # 权限(字段/行)
├── workflow/                     # 工作流引擎
├── notification/                 # 多渠道通知
├── audit/                        # 审计日志
└── common/                       # 公共工具
```

### 测试包结构

```
src/test/java/com/nocobase/
├── <pkg>/                        # 与主代码镜像
│   └── *Test.java                # 单元测试
├── security/
│   └── SecurityAuditTest.java    # 安全审计
└── e2e/
    ├── E2ESetupSmokeTest.java    # 上下文加载验证
    └── CollectionLifecycleE2ETest.java  # 完整集成路径
```

## 🔧 Jacoco 红线(Week 33 饱和)

| 包 | 红线 | 实绩 |
|---|---|---|
| BUNDLE | 0.82 | 83% |
| auth | 0.90 | 92% |
| meta | 0.50 | 58% |
| workflow | 0.95 | 96% |
| audit | 0.97 | 99% |
| view | 0.97 | 98% |
| notification | 0.90 | 94% |
| form | 0.95 | 99% |
| api | 0.95 | 100% |
| acl | 0.85 | 89% |

修改任何模块代码若让红线失守,CI 会拒绝合并。

## 🌐 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `POSTGRES_HOST` | localhost | 数据库地址 |
| `POSTGRES_PORT` | 5432 | 数据库端口 |
| `POSTGRES_DB` | nocobase | 数据库名 |
| `POSTGRES_USER` | nocobase | 用户名 |
| `POSTGRES_PASSWORD` | dev_password | 密码 |
| `REDIS_HOST` | localhost | Redis 地址 |
| `REDIS_PORT` | 6379 | Redis 端口 |
| `JWT_SECRET` | (32+字符) | JWT 密钥 |
| `JAVA_PORT` | 8080 | 服务端口 |
| `SPRING_PROFILES_ACTIVE` | dev | profile(dev/test) |

完整配置见 `src/main/resources/application.yml`。

## 📚 相关文档

- 顶层 README: `../README.md`
- 架构: `../ARCHITECTURE.md`
- 架构图: `../ARCHITECTURE_DIAGRAM.md`
- ADR: `../ADR/`
- 周交接: `../WEEK_*_HANDOFF.md`(Week 4-34)

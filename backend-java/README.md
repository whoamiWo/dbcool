# NocoBase Java 后端

> Spring Boot 3.3 + JDK 21 + PostgreSQL 16

## 本地启动

```bash
# 1. 确保基础设施已启动
cd .. && make up && cd backend-java

# 2. 跑应用
mvn spring-boot:run

# 3. 验证
curl http://localhost:8080/api/health
```

或用 Makefile:

```bash
make java   # 启动 Java
```

## 测试

```bash
mvn test                              # 全部
mvn test -Dtest=HealthControllerTest  # 单个
```

## 模块结构(规划中)

```
com.nocobase
├── NocoBaseApplication.java     # 入口
├── config/                       # 配置类
├── health/                       # 健康检查(Week 3)
├── auth/                         # 认证(Week 4)
├── api/                          # REST 控制器
├── meta/                         # Collection Engine(Week 5+)
├── acl/                          # 权限(Phase 4)
├── workflow/                     # 工作流(Phase 4)
└── common/                       # 公共工具
```

## 环境变量

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
| `SPRING_PROFILES_ACTIVE` | dev | profile |

完整配置见 `src/main/resources/application.yml`。

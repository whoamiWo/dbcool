# 架构总览

> 创建日期: 2026-09-09
> 适用项目: NocoBase 级低代码平台
> 维护者: Cline

---

## 一、整体架构图(高层)

```
┌────────────────────────────────────────────────────────────────┐
│                         👥 业务用户(浏览器)                      │
└─────────────────────────────┬──────────────────────────────────┘
                              │ HTTPS
                              ▼
┌────────────────────────────────────────────────────────────────┐
│                🌐 Frontend (React 18 + TypeScript)              │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌──────────┐ │
│  │  Schema    │  │   Form     │  │   View     │  │ Workflow │ │
│  │ Designer   │  │  Builder   │  │  Builder   │  │ Designer │ │
│  └────────────┘  └────────────┘  └────────────┘  └──────────┘ │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │           Runtime (Schema → UI 自动渲染引擎)              │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────┬──────────────────────────────────┘
                              │ REST + WebSocket
              ┌───────────────┴────────────────┐
              ▼                                ▼
┌──────────────────────────────┐   ┌──────────────────────────────┐
│  ☕ Java Engine (Spring Boot)│   │  🐍 Python AI/Integration    │
│                              │   │                              │
│  Collection / ACL / Workflow │   │  AI / Integration / Tasks    │
└──────────┬───────────────────┘   └──────────┬───────────────────┘
           ▼                                  ▼
┌────────────────────────────────────────────────────────────────┐
│              📦 Postgres · Redis · MinIO · Streams              │
└────────────────────────────────────────────────────────────────┘
```

## 二、三栈职责边界(SSOT)

### ☕ Java 后端:核心引擎
- Collection 元数据 / 业务数据 CRUD
- 用户、角色、ACL(字段级/记录级)
- 工作流编排与执行
- 插件 SPI
- 事务一致性
- **契约路径:** `/api/collections/*`、`/api/acl/*`、`/api/workflows/*`

### 🐍 Python 后端:AI 增强 & 集成
- LLM 接入(表单生成、智能分类)
- 第三方数据源连接器 / Webhook
- 报表导出(PDF/Excel)
- 异步耗时任务(Celery / ARQ)
- **契约路径:** `/api/ai/*`、`/api/integration/*`、`/api/tasks/*`
- **不直接连数据库**,只调 Java 的 `/internal/*`

### 🌐 React 前端:可视化搭建 & 运行时
- Schema Designer / Form Builder / View Builder / Workflow Designer
- Runtime(Schema → UI 自动渲染)
- 用户认证 UI
- **不直连数据库**,所有数据经后端 API

## 三、关键数据流

### 数据流 1:用户在设计器建表
```
用户拖拽字段 → React 临时 Schema(本地状态)
   ↓ POST /api/collections
Java Collection Engine: 写入 collection_meta + ALTER 主数据表
   ↓ 返回新 Collection 配置
React 更新 UI
```

### 数据流 2:用户提交表单数据
```
用户填表 → React 本地校验
   ↓ POST /api/collections/{name}/records
Java ACL Engine: 校验权限
   ↓ Collection Engine: INSERT
   ↓ 触发 Workflow 引擎(如有)
   ↓ 事件发到 Redis Stream
Python Worker: 消费事件 → 调 LLM 分类
```

### 数据流 3:工作流执行
```
数据变化触发 → Java Workflow Engine 加载节点
   ↓ 按拓扑执行:
      - "通知"节点 → POST Python /api/tasks/notify
      - "HTTP 调用"节点 → Java 直接发请求
      - "审批"节点 → 等用户操作
   ↓ 状态持久化到 workflow_instance 表
```

## 四、SSOT(单一真相来源)规则

| 数据类型 | SSOT 位置 | 其他栈如何读 |
|---|---|---|
| Collection 元数据 | Java `collection_meta` 表 | 经 Java API |
| 业务数据 | Java 业务表 | 经 Java API |
| 权限规则 | Java `acl_policy` 表 | 经 Java API |
| 工作流定义 | Java `workflow_definition` 表 | 经 Java API |
| 用户会话 | Redis | 三栈共享 |
| 异步任务状态 | Python `task_result` 表 | 经 Python API |

## 五、跨栈通信矩阵

| 调用方 → 被调方 | 方式 | 路径 |
|---|---|---|
| 前端 → Java | REST | `/api/*` |
| 前端 → Python | REST | `/api/ai/*`、`/api/tasks/*` |
| 前端 ← Java | WebSocket | `/ws/notifications` |
| Python → Java | REST(内网) | `/internal/*` |
| Java → Python | 消息队列 | Redis Streams `nocobase.events` |
| Python → 外部 LLM | HTTPS | OpenAI / Anthropic API |

## 六、部署架构

### 单机开发
```yaml
services:
  postgres: { image: postgres:16 }
  redis:    { image: redis:7 }
  minio:    { image: minio/minio }
  backend-java:    { build: ./backend-java }
  backend-python:  { build: ./backend-python }
  frontend:        { build: ./frontend }
  nginx:           { image: nginx }
```

### 生产
- K8s / Docker Swarm
- Java: 2 副本 + LB
- Python: 2 副本 + Celery worker 池
- Postgres: 主从
- Redis: Sentinel
- MinIO / S3
- CDN

## 七、安全架构

| 维度 | 措施 |
|---|---|
| 认证 | JWT(短期)+ Refresh Token |
| 授权 | Casbin(RBAC + ABAC) |
| 传输 | HTTPS only |
| 存储 | bcrypt 密码 / AES-256 敏感字段 |
| 审计 | 全操作日志 |
| SQL 注入 | JPA + 参数化 |
| XSS | React 默认转义 + CSP |
| CSRF | SameSite + JWT in header |

## 八、可扩展性

| 维度 | 当前 | 未来 |
|---|---|---|
| 多租户 | 单 DB 多 schema | 独立 DB(大客户) |
| 水平扩展 | Java stateless | K8s HPA |
| 读写分离 | 主从 | 读写分离 + 报表从库 |
| 缓存 | Redis | 多级 + Caffeine |

## 九、关联文档

- `ARCHITECTURE_DIAGRAM.md` — 详细时序图
- `ARCHITECTURE_TESTING.md` — 测试架构(Week 35,463 tests / 83% bundle / 10 Jacoco 红线)
- `ADR/` — 每个决策的详细理由
- `contracts/` — 跨栈契约
- `RISKS.md` — 架构层风险
- `TEST_STRATEGY.md` — 跨栈测试策略

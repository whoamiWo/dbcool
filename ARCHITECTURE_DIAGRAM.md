# 架构图

> 创建日期: 2026-09-09
> 配套: ARCHITECTURE.md(高层),ADR/(详细)
> 工具: Mermaid(纯文本,Github/GitLab 可直接渲染)

---

## 一、部署架构图

```mermaid
graph TB
    User[👤 业务用户<br/>浏览器]
    CDN[🌍 CDN]
    LB[⚖️ Nginx]

    subgraph Frontend
        FE1[React 1]
        FE2[React 2]
    end

    subgraph Backend-Java
        J1[Java Pod 1]
        J2[Java Pod 2]
    end

    subgraph Backend-Python
        P1[FastAPI 1]
        P2[FastAPI 2]
        W1[Celery 1]
        W2[Celery 2]
    end

    subgraph Data
        PG[(Postgres 主)]
        PG_R[(Postgres 从)]
        Redis[(Redis)]
        MinIO[(MinIO)]
    end

    User --> CDN
    User --> LB
    LB --> FE1
    LB --> FE2
    FE1 --> J1
    FE1 --> P1
    FE2 --> J2
    FE2 --> P2

    J1 --> PG
    J2 --> PG
    PG --> PG_R
    J1 --> Redis
    J2 --> Redis

    P1 --> J1
    P2 --> J2
    P1 --> Redis
    P2 --> Redis
    W1 --> Redis
    W2 --> Redis

    J1 --> MinIO
    P1 --> MinIO
```

## 二、登录鉴权时序图

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant FE as React
    participant J as Java
    participant DB as Postgres
    participant R as Redis

    U->>FE: 输入账号密码
    FE->>J: POST /api/auth/login
    J->>DB: SELECT user
    DB-->>J: User
    J->>J: bcrypt.compare
    J->>R: SET refresh:{id}
    J-->>FE: 200 { access, refresh }
    Note over FE: access 内存<br/>refresh Cookie

    U->>FE: 访问页面
    FE->>J: GET /api/users/me<br/>Bearer
    J->>J: JWT 解析
    J-->>FE: 200 user

    Note over FE: access 即将过期
    FE->>J: POST /api/auth/refresh
    J->>R: GET+DEL old, SET new
    J-->>FE: 200 { new access }
```

## 三、数据提交时序图

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant FE as React
    participant J as Java
    participant DB as PG
    participant R as Redis
    participant P as Python Worker
    participant LLM as OpenAI

    U->>FE: 填表单
    FE->>FE: zod 校验
    FE->>J: POST /records
    J->>J: JWT → tid
    J->>J: ACL 校验
    J->>DB: INSERT
    DB-->>J: OK
    J->>R: XADD events
    J-->>FE: 201
    FE->>U: 提示成功

    P->>R: XREAD
    R-->>P: 事件
    P->>LLM: 分类
    LLM-->>P: 结果
    P->>J: PATCH /internal
    J->>DB: UPDATE
    P->>R: XACK
```

## 四、工作流执行时序图

```mermaid
sequenceDiagram
    autonumber
    participant T as 触发器
    participant E as Engine
    participant DB as PG
    participant N1 as 审批节点
    participant N2 as 通知节点
    participant U as 审批人

    T->>E: 触发
    E->>DB: SELECT workflow_def
    E->>DB: INSERT instance
    E->>N1: 执行
    N1->>DB: INSERT task
    N1-->>E: WAITING
    U->>J: POST /approve
    J->>E: 恢复
    E->>N1: 完成
    E->>N2: 执行
    N2->>DB: INSERT notification
    N2-->>E: OK
    E->>DB: UPDATE status=DONE
```

## 五、跨租户隔离时序图

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户A
    participant FE as React
    participant J as Java
    participant DB as PG

    U->>FE: 查数据
    FE->>J: GET /records<br/>X-Tenant: xxx
    J->>J: 校验 tid 一致
    J->>DB: SET search_path tenant_xxx
    J->>DB: SELECT
    DB-->>J: xxx 数据
    J-->>FE: 返回

    Note over U,DB: ❌ 攻击
    U->>FE: X-Tenant: yyy
    J->>J: tid 不匹配 → 403
    J-->>FE: 403
```

## 六、整体类比(给非技术人)

```
前端(React)        = 餐厅前台
Java 后端          = 厨房主管
Python 后端        = 外卖配送
PostgreSQL         = 食材仓库
Redis              = 备菜台
MinIO              = 冷库
LLM                = 美食顾问
```

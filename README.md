# NocoBase 级低代码平台 · 三栈项目

> ☕ Java + 🐍 Python + 🌐 React · 200 人规模 · 完整复刻 NocoBase
> **当前状态:阶段 3(M3)全部完成,准备进入阶段 4**

---

## 🎯 M3 里程碑达成(阶段 3)

✅ 8 步演示验收全过:
1. `make up-all` 起全栈
2. 浏览器打开 `http://localhost` 看到登录页
3. admin/admin123 登录 → 主页(JWT 状态 ✅)
4. 数据模型 → 新建 Collection
5. 创建"客户"表(2 字段)→ 跳到详情页
6. 详情页列出 0 条
7. 点"添加记录" → 填表 → 提交
8. 列表显示新记录

---

## 🚀 一键启动(两种方式)

### 方式 A:Docker Compose 全栈(推荐)

```bash
cd multistack-project
cp .env.example .env
make up-all
# 前端:http://localhost
# Java:http://localhost:8080(直连)
# Python:http://localhost:8000(直连)
```

### 方式 B:本地开发(三栈独立)

```bash
make up-infra    # 只起 Postgres + Redis + MinIO
# 三个终端:
make java        # 终端 1: Java 8080
make python      # 终端 2: Python 8000
make frontend    # 终端 3: Vite 5173
# 浏览器打开 http://localhost:5173
```

默认账号:**admin / admin123**

---

## 📁 目录结构

```
multistack-project/
│
├── 📋 项目规划
│   ├── IDEA_BRIEF.md          💡 想法简报
│   ├── ARCHITECTURE.md        🏛️ 架构总览
│   ├── ARCHITECTURE_DIAGRAM.md
│   ├── MVP_SCOPE.md
│   ├── USER_STORIES.md        (49 条 P0/P1/P2)
│   ├── ROADMAP.md             7 阶段路线图
│   ├── RISKS.md               15 项风险
│   ├── TEST_STRATEGY.md
│   ├── SCAFFOLDING_PLAN.md    Week 3 脚手架计划
│   ├── WEEK_4_5_HANDOFF.md    Week 4+5 真实代码
│   └── ADR/                   8 份架构决策
│
├── 📜 跨栈契约(SSOT)
│   └── contracts/
│       ├── openapi.yaml       (auth + collections + records + ai)
│       ├── errors.yaml
│       └── schemas/
│
├── ☕ backend-java/           Spring Boot 3.3 + JDK 21
│   ├── src/main/java/com/nocobase/
│   │   ├── NocoBaseApplication.java
│   │   ├── config/           SecurityConfig
│   │   ├── auth/             JWT + User + RefreshToken + Filter
│   │   ├── health/
│   │   ├── api/              UserController
│   │   └── meta/             Collection Engine
│   │       ├── FieldDef
│   │       ├── CollectionMetaEntity
│   │       ├── CollectionRepository
│   │       ├── CollectionService
│   │       ├── CollectionController
│   │       └── DynamicTableManager  # 混合方案 C 实现
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/     Flyway V1, V2
│   ├── src/test/java/
│   ├── Dockerfile
│   └── pom.xml
│
├── 🐍 backend-python/        FastAPI 0.115 + Python 3.12
│   ├── src/nocobase_py/
│   │   ├── main.py
│   │   ├── config.py
│   │   ├── security.py       JWT 校验(共享密钥)
│   │   └── routers/
│   │       ├── health.py
│   │       └── ai.py          /api/ai/echo + /whoami
│   ├── tests/
│   ├── Dockerfile
│   └── pyproject.toml
│
├── 🌐 frontend/              React 18 + Vite 5 + TypeScript
│   ├── src/
│   │   ├── main.tsx + router.tsx
│   │   ├── api/client.ts     axios + JWT 注入
│   │   ├── stores/auth.ts    Zustand
│   │   ├── types/collection.ts
│   │   ├── components/AppLayout.tsx
│   │   └── pages/
│   │       ├── Login.tsx
│   │       ├── Home.tsx       Dashboard
│   │       ├── CollectionsList.tsx
│   │       ├── CollectionDetail.tsx
│   │       └── SchemaDesigner.tsx
│   ├── Dockerfile
│   ├── nginx.conf            反向代理
│   └── package.json
│
├── 🧪 tests/contract/        跨栈契约测试
│   ├── test_openapi_contract.py
│   └── pyproject.toml
│
├── 🛠️ 工具
│   ├── Makefile              make up-all/up-infra/java/python/frontend/test/build
│   ├── docker-compose.yml    全栈编排
│   ├── .env.example
│   ├── .gitignore
│   └── opt-multistack.sh     Prime daemon(阶段 6+ 用)
│
└── 💬 反馈通道(阶段 6+ 才用)
    ├── FEEDBACK.md
    ├── CROSS_STACK_ISSUES.md
    └── CONTRACT_DECISIONS.md
```

---

## 🧪 验证

```bash
make test           # 三栈单元测试
make test-contract  # 契约测试
make lint           # 三栈 lint
make build          # 三栈构建产物
```

---

## 🚦 阶段状态

| 阶段 | 状态 | 关键产出 |
|---|---|---|
| 0 想法澄清 | ✅ | IDEA_BRIEF |
| 1 架构决策 | ✅ | 8 份 ADR + 架构总览 |
| 2 MVP 定义 | ✅ | MVP_SCOPE + 49 条 USER_STORIES |
| 3 脚手架 + Week 3 真实代码 | ✅ | 三栈可运行 + Collection Engine + JWT |
| **4 Epic 1 数据模型(含修改表)** | ✅ **Week 7 跑通** | AsyncMigrationService + SchemaEditor |
| **4 Epic 2 表单设计器** | ✅ **Week 8 跑通** | FormDesigner + FormRuntime |
| **浏览器端到端验证** | ✅ **2026-09-09 跑通** | localhost:5173 全流程演示 |
| 4 Epic 3 视图设计器 | ⏳ **下一步** | US-201~208 |
| 4 Epic 4 权限 | ⏸️ | US-301~308 |
| 4 Epic 5 工作流 | ⏸️ | US-401~410 |
| 4 Epic 6 平台基础 | ⏸️ | US-501~507 |
| 5 基线建立 | ⏸️ | BASELINE + 雷达 |
| 6 迭代优化 | ⏸️ | Prime daemon 协作 |
| 7 终止判定 | ⏸️ | TERMINATION |

---

## 🛠️ 常用命令

```bash
make help           # 所有命令
make up-all         # 起全栈(Docker)
make up-infra       # 仅起基础设施
make down           # 停全栈
make logs           # 看日志
make restart        # 重启

make java           # 本地起 Java
make python         # 本地起 Python
make frontend       # 本地起 React

make test           # 三栈测试
make test-contract  # 契约测试
make lint           # 三栈 lint
make build          # 三栈构建
```

---

## 📞 配套提示词索引

> 完整提示词包在 Cline 会话历史中

| 编号 | 用途 | 何时用 |
|---|---|---|
| 1.0 | 多栈结构识别 | 阶段 5 之后(已识别过) |
| 2.0 | 三栈基线采集 | 阶段 5 |
| 3.0 | Prime 启动 | 阶段 6 |
| 6.0/6.1/6.2 | 质量雷达 | 阶段 6 |
| 7.0 | 终止判定 | 阶段 7 |

---

## ⚠️ 已知限制(阶段 3 范围)

- 多租户架构支持但 UI 未启用
- ACL 字段级/行级权限未实现(Phase 4)
- 工作流引擎未实现(Phase 4)
- 表单/视图设计器只有 Schema Designer(Week 5+ 才有完整 Form/View)

---

**下一步:阶段 4 MVP 实现(33 个 P0 故事)**

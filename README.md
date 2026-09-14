# 🛠️ NocoBase 级低代码平台

> 一个 NocoBase 风格的可视化低代码平台 · Java + Python + React 三栈 · 单机即可演示

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/)
[![Python](https://img.shields.io/badge/Python-3.12-blue)](https://python.org/)
[![React](https://img.shields.io/badge/React-18-61dafb)](https://react.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](./LICENSE)
[![Backend CI](https://img.shields.io/badge/CI-Backend%20CI-blue)](./.github/workflows/backend-ci.yml)
[![Coverage](https://img.shields.io/badge/coverage-9%25-yellow)](./backend-java/target/site/jacoco/index.html)

## ✨ 核心能力

- 🗂️ **数据模型引擎**: 运行时增删改 Collection 和字段,无锁表迁移
- 📝 **表单设计器**: 三栏拖拽,实时预览,7 种校验规则
- 📊 **视图设计器**: 表格 / 看板 / 详情,客户端筛选/排序
- 🔌 **多栈契约**: OpenAPI 单源真相,Java/Python 共享
- 🐳 **一键启动**: Docker Compose + 三个 Makefile 目标
- ✅ **API 验证**: `verify.sh` 16 项断言,build 绿

## 🏗️ 技术栈

| 层 | 技术 | 版本 | 作用 |
|---|---|---|---|
| 前端 | React + TypeScript + Vite | 18 / 5 / 5 | 可视化设计器 + 运行时渲染 |
| 后端核心 | Java + Spring Boot | 21 / 3.3 | 数据模型 / 权限 / 工作流 |
| 后端 AI | Python + FastAPI | 3.12 / 0.115 | LLM 集成 / 异步任务 |
| 数据库 | PostgreSQL | 16 | JSONB 存动态字段 |
| 缓存 / 队列 | Redis | 7 | 缓存 / Streams |
| 对象存储 | MinIO | latest | 附件 |
| 认证 | JWT + Refresh | HS256 | 无状态 |
| 插件 | Java SPI | - | 字段类型/工作流节点扩展 |

## 🚀 一键启动

### 方式 1:本地开发(推荐)

```bash
git clone https://github.com/whoamiWo/dbcool.git
cd dbcool

# 装环境(JDK 21 / Maven / Docker / Node 20+ / pnpm / Python 3.12)
./install.sh java       # 只装 Java + 起 Java + DB
# 或
./install.sh all        # 全栈(Java + Python + 前端)
```

### 方式 2:Docker Compose 全栈

```bash
docker compose up -d --build
# 前端(含反代) http://localhost:80
# Java                   http://localhost:8080
# Python                 http://localhost:8000
```

**默认账号:** `admin` / `admin123`

## 📂 目录结构

```
dbcool/
├── ADR/                        # 8 份架构决策记录
├── contracts/                   # OpenAPI + 错误码(SSOT)
├── backend-java/                # Spring Boot 3.3 + JDK 21
│   └── src/main/java/com/nocobase/
│       ├── auth/                # JWT 认证
│       ├── meta/                # Collection Engine(混合方案 C)
│       ├── form/                # Form Engine(Week 8)
│       └── view/                # View Engine(Week 9)
├── backend-python/              # FastAPI + Pydantic v2
│   └── src/nocobase_py/
│       ├── security.py          # JWT 校验
│       └── routers/ai.py        # AI 端点占位
├── frontend/                    # React 18 + Vite 5
│   └── src/
│       ├── pages/               # Collection/Form/View 设计器 + 运行时
│       ├── components/views/     # FilterBar / FormRuntime
│       └── api/                  # axios + JWT
├── tests/contract/              # 跨栈契约测试
├── docs/                        # 设计文档
├── install.sh                   # 一键安装 + 启动
├── verify.sh                    # API 验证(16 项断言)
├── docker-compose.yml           # 全栈编排
├── Makefile                     # 统一命令
├── TROUBLESHOOTING.md           # 12 个常见问题
└── WEEK_*_HANDOFF.md            # 各周交接文档
```

## 📊 端到端功能演示

| 步骤 | 操作 | 入口 |
|---|---|---|
| 1 | 登录 | http://localhost:5173 |
| 2 | 创建 Collection | 数据模型 → 新建 |
| 3 | 加字段 | 编辑 Schema |
| 4 | 设计表单 | 建表单 |
| 5 | 填表提交 | 关联表单卡片 |
| 6 | 设计视图 | 建视图 |
| 7 | 看数据 | 视图打开 |

## 🛠️ 常用命令

```bash
# 安装 / 启动
./install.sh java        # 最小可跑:Java + DB
./install.sh all         # 全栈
./install.sh frontend    # 只前端
./install.sh stop        # 停所有

# 验证
./verify.sh              # 16 项 API 断言
./verify.sh form         # 只测 Form API

# 开发
make up-infra            # 起基础设施
make java                # 本地起 Java
make test                # 三栈测试
make lint                # lint
make build               # 三栈构建
```

## 🧪 API 验证

`verify.sh` 会跑 16 项断言,覆盖:
- 健康检查 / 认证 / 错误密码拒绝
- Bearer token 鉴权 / 无 token 拒绝
- Collection CRUD / 增删字段 / 重命名字段
- Form 创建/列出/详情/删除
- 记录 CRUD

```bash
$ ./verify.sh
=== 1. 健康检查 ===
✅ Java /api/health 通
=== 2. 认证 API(Week 4 JWT) ===
✅ 登录成功, token 长度=269
✅ 错误密码被拒绝
✅ Bearer token 鉴权通过
✅ 无 token 被拒绝(HTTP 401)
... (共 16 项)
🎉 全部通过!Week 3~8 API 验证完成。
```

## 🏛️ 架构亮点

### 1. 混合方案 C:基础列 + JSONB

```
物理表 data_customer:
  id          UUID PRIMARY KEY
  created_at  TIMESTAMPTZ
  created_by  UUID
  extra       JSONB  -- 用户字段都进这里
```

- ✅ 加字段 → 只改 metadata + JSONB 索引(秒级)
- ✅ 基础查询(按 created_at)走 SQL
- ✅ 扩展字段走 JSONB + GIN 索引

### 2. 跨栈契约优先

```
contracts/openapi.yaml  ←  单一真相源
       ↓
[Java 控制器]   [Python 校验]   [前端类型生成]
```

三栈从同一份 OpenAPI 派生类型,改一处全部生效。

### 3. 异步迁移

```
add field → 同步 ALTER(小表秒过)
         → 失败 → @Async + Redis 状态轮询
         → migration_jobs 表持久化
```

## 📅 里程碑

| 阶段 | 状态 |
|---|---|
| 0 想法澄清 | ✅ |
| 1 架构决策(8 份 ADR) | ✅ |
| 2 MVP 定义(49 user stories) | ✅ |
| 3 脚手架 + Week 3 真实代码 | ✅ |
| 4 Epic 1 数据模型 | ✅ Week 7 跑通 |
| 4 Epic 2 表单设计器 | ✅ Week 8 跑通 |
| 4 Epic 3 视图设计器 | ✅ Week 9 跑通 |
| **4 Epic 4 权限** | ⏳ 下一阶段 |
| 4 Epic 5 工作流 | ⏳ |
| 4 Epic 6 平台基础 | ⏳ |
| 5 基线建立 | ⏳ |
| 6 迭代优化(Prime 协作) | ⏳ |
| 7 终止判定 | ⏳ |

## 📈 进度数据

- **后端 Java 源文件**:30+ (auth + meta + form + view)
- **前端 TS/TSX**:20+ (设计器 + 运行时)
- **Python 源文件**:8 (后端 AI 占位 + JWT 校验)
- **SQL migrations**:5 (V1~V5)
- **测试**:Java 10+ / Python 7+ / 前端 2+
- **文档**:25+ (规划 ADR 设计 WEEK_*_HANDOFF)

## 🐛 排错

遇到问题先看 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md),12 个常见问题的诊断+解决。

## 🤝 贡献

欢迎 PR!建议先看 `WEEK_*_HANDOFF.md` 了解当前进展。

## 📄 许可证

[MIT](./LICENSE)

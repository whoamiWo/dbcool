# 🛠️ NocoBase 级低代码平台

> 一个 NocoBase 风格的可视化低代码平台 · Java + Python + React 三栈 · 单机即可演示

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green)](https://spring.io/)
[![Python](https://img.shields.io/badge/Python-3.12-blue)](https://python.org/)
[![React](https://img.shields.io/badge/React-18-61dafb)](https://react.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](./LICENSE)
[![Backend CI](https://img.shields.io/badge/CI-passing-brightgreen)](./.github/workflows/backend-ci.yml)
[![Coverage](https://img.shields.io/badge/coverage-83%25-brightgreen)](./backend-java/target/site/jacoco/index.html)
[![Tests](https://img.shields.io/badge/tests-2666%20passing-brightgreen)](#-测试)

## ✨ 核心能力

- 🗂️ **数据模型引擎** (meta):运行时增删改 Collection 和字段,JSONB 存动态字段
- 📝 **表单设计器** (form):三栏拖拽,实时预览,7 种校验规则
- 📊 **视图设计器** (view):表格 / 看板 / 详情,客户端筛选/排序
- 🔐 **认证 & 权限** (auth + acl):JWT 认证 + RBAC + 行级权限
- ⚡ **工作流引擎** (workflow):节点执行 + 审批 + 条件分支 + HTTP 回调
- 🔔 **多渠道通知** (notification):邮件 / 钉钉 / 企业微信 / Webhook
- 📊 **审计日志** (audit):全操作留痕 + IP/UA 捕获
- 🔌 **跨栈契约**:OpenAPI 单源真相,Java/Python 共享
- 🐳 **一键启动**:Docker Compose + Makefile 统一命令

## 🏗️ 技术栈

| 层 | 技术 | 版本 | 作用 |
|---|---|---|---|
| 前端 | React + TypeScript + Vite | 18 / 5 / 5 | 可视化设计器 + 运行时渲染 |
| 后端核心 | Java + Spring Boot | 21 / 3.4 | 数据模型 / 权限 / 工作流 |
| 后端 AI | Python + FastAPI | 3.12 / 0.115 | LLM 集成 / 异步任务 |
| 数据库 | PostgreSQL | 16 | JSONB 存动态字段 |
| 缓存 | Redis | 7 | 缓存 / Streams / Refresh Token |
| 对象存储 | MinIO | latest | 附件 |
| 认证 | JWT + Refresh | HS256 | 无状态 |
| 插件 | Java SPI | - | 字段类型/工作流节点扩展 |

## 🚀 一键启动

### 方式 1:本地开发(推荐)

```bash
git clone https://github.com/whoamiWo/dbcool.git
cd dbcool

# 装环境(JDK 21 / Maven / Docker / Node 20+ / pnpm / Python 3.12)
./install.sh java       # 只装 Java + DB
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
├── backend-java/                # Spring Boot 3.4 + JDK 21
│   ├── src/main/java/com/nocobase/
│   │   ├── auth/                # JWT 认证 + RBAC
│   │   ├── meta/                # Collection Engine
│   │   ├── form/                # Form Engine
│   │   ├── view/                # View Engine
│   │   ├── acl/                 # 权限策略
│   │   ├── workflow/            # 工作流引擎
│   │   ├── notification/        # 多渠道通知
│   │   ├── audit/               # 审计日志
│   │   ├── health/              # 健康检查
│   │   ├── api/                 # User 端点
│   │   ├── config/              # 配置 + 异常处理
│   │   └── NocoBaseApplication.java
│   ├── src/test/java/           # 单元 + E2E + 安全审计
│   ├── pom.xml                  # Maven 配置 + Jacoco 红线
│   └── README.md
├── backend-python/              # FastAPI + Pydantic v2
├── frontend/                    # React 18 + Vite 5
├── tests/contract/              # 跨栈契约测试
├── docs/                        # 设计文档
├── install.sh                   # 一键安装 + 启动
├── verify.sh                    # API 验证(16 项断言)
├── docker-compose.yml           # 全栈编排
├── Makefile                     # 统一命令
├── TROUBLESHOOTING.md           # 12 个常见问题
└── WEEK_*_HANDOFF.md            # 各周交接文档(Week 4-34)
```

## 📊 后端模块结构(Week 34 末)

| 包 | 职责 | 端点数 | 测试覆盖 |
|---|---|---|---|
| `auth` | JWT 认证 / RBAC | 4 controllers | 92% |
| `meta` | Collection Engine | 2 controllers | 58% |
| `form` | Form Engine | 1 controller | 99% |
| `view` | View Engine | 1 controller | 98% |
| `acl` | 字段/行级权限 | 1 controller | 89% |
| `workflow` | 工作流引擎 | 3 controllers | 96% |
| `notification` | 多渠道通知 | 1 controller | 94% |
| `audit` | 审计日志 | 1 controller | 99% |
| `api` | User 端点 | 1 controller | 100% |
| `health` | 健康检查 | 1 controller | 100% |
| `config` | Security/OpenAPI/异常处理 | - | 100%(handler) |
| **BUNDLE** | **总覆盖** | **15 controllers / 78 endpoints** | **83%** |

## 🧪 测试

### 测试规模(PHASE117-121 最新实测)

#### Java 后端（2263 tests）

```bash
cd backend-java && mvn test   # ✅ 2263/2263 PASS
```

| 类型 | 数量 | 说明 |
|------|------|------|
| 单元测试 | 2225 | mock-based，覆盖核心业务逻辑 |
| E2E 集成测试 | 8 | SpringBootTest + H2（绕过 Flyway PG-specific） |
| 安全审计测试 | 15 | SQL注入 / XSS / null / 大 body 拒绝用例 |
| 门禁回归 | 94 | 鉴权棘轮（0缺口）、权限矩阵、SpEL 语法 |

- **Jacoco 覆盖率**: 83% BUNDLE（10 条红线全达标，见下表）
- **测试金字塔**: 单元 2225 / 安全 15 / E2E 8，顶层 E2E 抓集成层 bug，底层单元抓业务逻辑

#### 前端 React（403 tests）

```bash
cd frontend && npx vitest run   # ✅ 403/403 PASS
cd frontend && npx tsc --noEmit # ✅ 0 errors
cd frontend && npx eslint src   # ✅ 0 warnings
```

| 类型 | 数量 | 说明 |
|------|------|------|
| vitest 单测 | 403 | DOM 交互、逻辑分支、i18n key 完整性 |
| TypeScript 类型检查 | 0 error | 全量源码 tsc --noEmit 通过 |
| ESLint 静态检查 | 0 warning | 遵循项目 ESLint 配置 |

#### Playwright E2E（159 tests）

```bash
cd frontend && npx playwright test   # ✅ 159/159 PASS
```

覆盖登录流程、CRUD 操作、权限控制、i18n 切换等核心用户路径。

### 跑测试

```bash
make test-java             # Java 全测(单元 + E2E + Jacoco coverage gate)
cd backend-java && mvn test -Dtest=AuthControllerTest  # 单个
cd backend-java && mvn test -Dtest='CollectionLifecycleE2ETest'  # 仅 E2E
```

### CI 自动跑

Push 到 main / 任何 PR → GitHub Actions 自动跑 `mvn verify` → 检查 Jacoco 红线。

配置文件: `.github/workflows/backend-ci.yml`

### Jacoco 红线(Week 33 饱和上限)

| 模块 | 红线 | 实绩 |
|---|---|---|
| BUNDLE | 0.82 | 83% |
| auth | 0.90 | 92% |
| workflow | 0.95 | 96% |
| audit | 0.97 | 99% |
| view | 0.97 | 98% |
| notification | 0.90 | 94% |
| form | 0.95 | 99% |
| api | 0.95 | 100% |
| acl | 0.85 | 89% |
| meta | 0.50 | 58% |

修改任何模块代码若让红线失守,CI 会拒绝合并。

### H2 测试基础设施(Week 35)

E2E 测试用 H2 `MODE=PostgreSQL` + JPA `create-drop`(绕过 Flyway PG-specific migrations)。

profile:`test` — 配置文件 `backend-java/src/test/resources/application-test.properties`

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

### 4. 测试金字塔(PHASE117 实测)

```
        /\
       /E \        E2E 集成(SpringBootTest + H2)
      / 8  \       8 tests
     /----\
    /SA \          安全审计(SQL 注入 / XSS / 越权)
   / 15  \         15 tests
  /------\
 / UNIT   \       单元测试(mock-based)
/ 2225    \      2225 tests
----------
```

每个测试类型抓不同层级的 bug。单元测试抓业务逻辑,E2E 抓集成层 bug,安全审计抓输入验证 bug。

## 📅 里程碑

| 阶段 | 状态 |
|---|---|
| 0 想法澄清 | ✅ |
| 1 架构决策(8 份 ADR) | ✅ |
| 2 MVP 定义(49 user stories) | ✅ |
| 3 脚手架 + Week 3 真实代码 | ✅ |
| 4 Epic 1-6 全功能 | ✅ Week 26 跑通 |
| 4 测试饱和阶段(Week 25-33) | ✅ 83% bundle |
| 4 方向变更阶段(Week 34-35) | ✅ E2E + 安全审计 + CI + 文档 |
| 5 基线建立(PHASE110-116) | ✅ 上线终验 + 独立审计 |
| 5 依赖升级(PHASE117) | ✅ Spring Boot 3.4.7 + SpEL 回归修复 |
| 5 i18n 接入(PHASE118) | ✅ 5 批次 / 53 文件 / 250+ key |
| 5 文档同步(PHASE119-123) | ✅ 门禁刷新 + 版本对齐 |
| 6 迭代优化(Prime 协作) | ⏳ |
| 6 CRDT 身份归属补全(PHASE127) | ⏳ 版本快照 user_id/tenant_id 写入修复 |
| 7 终止判定 | ⏳ |

## 📈 进度数据

- **后端 Java 源文件**:90+(12 个包)
- **测试**:2666 全 PASS（Java 2263 + 前端 403）
- **覆盖率**:83% bundle / 10 条 Jacoco 红线全达标
- **文档**:30+（规划 ADR 设计 WEEK_*_HANDOFF）
- **总 commits**:120+（本会话累计）

## 🛡️ 门禁状态（2026-10-21 实测）

| 门禁 | 结果 |
|------|------|
| Java `mvn test` | ✅ 2263/2263 PASS（0F/0E） |
| 前端 `npx vitest run` | ✅ 403/403 PASS |
| 前端 `npx tsc --noEmit` | ✅ 0 errors |
| 前端 `npx eslint src` | ✅ 0 warnings |
| Playwright E2E | ✅ 159/159 PASS |
| 冒烟测试 | ✅ 11/11 PASS |
| 编排测试 | ✅ 8/8 PASS |
| 依赖漏洞扫描 | ✅ 0 高危（1 MEDIUM，已登记可忽略） |
| 跨栈契约 | ✅ 0 漂移 |
| SaaS 就绪 | ✅ 11/11 |
| 启动检查 | ✅ 0 问题 |
| 密钥轮换 | ✅ 3/3 |

## 🐛 排错

遇到问题先看 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md),12 个常见问题的诊断+解决。

## 🤝 贡献

欢迎 PR!建议先看 `WEEK_*_HANDOFF.md` 了解当前进展。

## 📄 许可证

[MIT](./LICENSE)

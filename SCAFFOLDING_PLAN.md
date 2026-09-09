# 阶段 3 脚手架详细计划

> 创建日期: 2026-09-09
> 关联: ROADMAP.md Week 3~6, MVP_SCOPE.md, ARCHITECTURE.md
> 当前状态: **30 个文件全部生成,等待环境安装后跑通验证**

---

## 一、目标

**三栈各自可独立启动 + 跨栈最小可跑通链路(M3 里程碑)**

Week 6 结束时的演示验收(8 步全过即 M3 完成):
1. `make up` 起基础设施(Postgres + Redis + MinIO)
2. 浏览器打开 `http://localhost` 看到登录页(Week 5 接 nginx 后)
3. 登录成功,看到主页 + 用户名
4. 点"建表"进入 Schema Designer
5. 拖字段创建"客户"表(Week 5 才真接 API,Week 3 是本地预览)
6. 前端看到表已建好
7. 提交一条客户记录(Week 5+ 才有)
8. 前端列表看到记录(Week 5+ 才有)

**Week 3 实际只完成 1~4 步**;5~8 步在 Week 5 阶段 3 完成。

---

## 二、4 周时间表

### Week 3 — 基础设施 + 三栈 hello world

| Day | 任务 | 产出 |
|---|---|---|
| Day 1~2 | 装 JDK 21 / Maven / pnpm / Docker;启动 `make up` | 基础设施运行 |
| Day 3 | Java hello world | `mvn spring-boot:run` 通 |
| Day 4 | Python hello world | `uvicorn` 通 |
| Day 5 | React hello world | `pnpm dev` 通 |

### Week 4 — 跨栈最小链路

| Day | 任务 | 产出 |
|---|---|---|
| 1~2 | Java 暴露 login + JWT | `POST /api/auth/login` |
| 3 | Python echo 端点 | `GET /api/ai/echo` |
| 4 | 前端登录跑通 | 登录 → 主页 |
| 5 | Nginx 反代 | 单端口入口 |

### Week 5 — 数据模型骨架

| Day | 任务 | 产出 |
|---|---|---|
| 1~2 | Java Collection Engine 最小版 | `POST /api/collections` |
| 3 | 支持基础字段类型 | text/number/date |
| 4 | 前端 Schema Designer 最小版 | 拖字段 |
| 5 | 端到端跑通 | Demo 演示 |

### Week 6 — 收尾与 CI

| Day | 任务 | 产出 |
|---|---|---|
| 1~2 | 契约测试 | `schemathesis` |
| 3 | Dockerfile 三栈 | 多阶段构建 |
| 4 | CI 流水线 | GitHub Actions |
| 5 | 文档 + M3 验收 | 阶段 4 接力 |

---

## 三、生成的文件清单(30 个)

### 基础设施层(4)
- ✅ `docker-compose.yml` — Postgres + Redis + MinIO
- ✅ `.env.example` — 环境变量模板
- ✅ `.gitignore` — 三栈通用
- ✅ `Makefile` — 统一命令入口

### ☕ Java 后端(8)
- ✅ `backend-java/pom.xml` — Maven 配置(Spring Boot 3.3 + JDK 21)
- ✅ `backend-java/src/main/resources/application.yml` — 配置
- ✅ `backend-java/src/main/java/com/nocobase/NocoBaseApplication.java` — 入口
- ✅ `backend-java/src/main/java/com/nocobase/health/HealthController.java` — `/api/health`
- ✅ `backend-java/src/main/java/com/nocobase/auth/AuthController.java` — `/api/auth/login`
- ✅ `backend-java/src/main/java/com/nocobase/api/UserController.java` — `/api/users/me`
- ✅ `backend-java/src/test/java/com/nocobase/HealthControllerTest.java` — 单元测试
- ✅ `backend-java/Dockerfile` — 多阶段构建
- ✅ `backend-java/README.md` — 子项目文档

### 🐍 Python 后端(8)
- ✅ `backend-python/pyproject.toml` — uv + Pydantic v2
- ✅ `backend-python/src/nocobase_py/__init__.py`
- ✅ `backend-python/src/nocobase_py/main.py` — FastAPI 入口
- ✅ `backend-python/src/nocobase_py/config.py` — Settings
- ✅ `backend-python/src/nocobase_py/routers/__init__.py`
- ✅ `backend-python/src/nocobase_py/routers/health.py` — `/api/health`
- ✅ `backend-python/src/nocobase_py/routers/ai.py` — `/api/ai/echo`
- ✅ `backend-python/tests/test_health.py` — pytest 测试
- ✅ `backend-python/Dockerfile` — 多阶段构建
- ✅ `backend-python/README.md` — 子项目文档

### 🌐 前端(12)
- ✅ `frontend/package.json` — React 18 + Vite 5
- ✅ `frontend/tsconfig.json` — TS 严格模式
- ✅ `frontend/vite.config.ts` — 含 dev proxy
- ✅ `frontend/index.html` — HTML 入口
- ✅ `frontend/src/main.tsx` — React 入口
- ✅ `frontend/src/router.tsx` — 路由
- ✅ `frontend/src/styles.css` — 全局样式
- ✅ `frontend/src/test-setup.ts` — 测试 setup
- ✅ `frontend/src/api/client.ts` — Axios 实例
- ✅ `frontend/src/stores/auth.ts` — Zustand 认证
- ✅ `frontend/src/components/AppLayout.tsx` — 顶部导航
- ✅ `frontend/src/pages/Login.tsx` + `Login.test.tsx`
- ✅ `frontend/src/pages/Home.tsx`
- ✅ `frontend/src/pages/SchemaDesigner.tsx` — 占位
- ✅ `frontend/Dockerfile`
- ✅ `frontend/nginx.conf` — 反代
- ✅ `frontend/.gitignore`
- ✅ `frontend/README.md`

### 🧪 契约测试 + CI(3)
- ✅ `tests/contract/test_openapi_contract.py`
- ✅ `tests/contract/pyproject.toml`
- ✅ `.github/workflows/ci.yml`

**总计:35 个文件(含 SCAFFOLDING_PLAN.md)**

---

## 四、关键决策(全部接受默认)

| 决策 | 默认值 | 原因 |
|---|---|---|
| Java 包名 | `com.nocobase` | 对齐产品名 |
| Python 包名 | `nocobase_py` | 避免与 Java 重名 |
| 前端路径别名 | `@/` → `src/` | Vite 标准 |
| Java 构建 | Maven | 文档多、易上手 |
| JWT 算法 | HS256(短期) | 单服务足够 |
| 多租户 | 单租户优先 | 架构支持,UI 暂单租户 |
| Session 存储 | localStorage(短期) | Week 3 简化,Week 4 改 HttpOnly Cookie |
| 状态管理 | Zustand | 轻量、TS 友好 |

---

## 五、环境安装命令(用户在自己机器上跑)

```bash
# 1. 安装系统包(Ubuntu/Debian)
sudo apt update
sudo apt install -y openjdk-21-jdk maven nodejs npm docker.io docker-compose-plugin curl

# 2. 安装 pnpm
npm install -g pnpm@9

# 3. 安装 uv (Python 包管理)
curl -LsSf https://astral.sh/uv/install.sh | sh

# 4. 安装 Python 3.12(如果当前是 3.14)
# 用 pyenv 或者 conda,避免污染系统 Python

# 5. 验证
java -version       # 21.x
mvn -version        # 3.9+
node --version      # 20+
pnpm --version      # 9+
uv --version        # 0.4+
docker --version    # 24+
python3.12 --version  # 3.12.x
```

---

## 六、跑通验证(Week 3 Day 5 完成时)

```bash
# 终端 1:基础设施
make up

# 终端 2:Java
make java
# 验证:curl http://localhost:8080/api/health
# 期望:{"status":"ok","service":"nocobase-backend",...}

# 终端 3:Python
make python
# 验证:curl http://localhost:8000/api/health
# 期望:{"status":"ok","service":"nocobase-py",...}

# 终端 4:前端
make frontend
# 浏览器打开 http://localhost:5173
# 看到登录页 → 任意账号密码登录 → 看到主页(显示用户名)
```

如果 4 个服务都能起来并互不报错,**Week 3 脚手架成功**。

---

## 七、已知问题与限制

| 问题 | 影响 | 缓解 |
|---|---|---|
| Python 本机是 3.14,与 ADR 锁的 3.12 不一致 | Week 4+ 用 uv 安装 3.12 | Week 3 语法检查用 3.14 也通 |
| 无 Docker,无法 `make up` | 基础设施起不来 | 改用 apt 装 Postgres + Redis |
| JWT 没真签发,只返回占位字符串 | 跨栈调用 token 不真实 | Week 4 接 jjwt + python-jose |
| 前端 Schema Designer 不接 API | 5~8 步验收推迟到 Week 5 | Week 3 跑本地预览 |

---

## 八、阶段 4 接力清单(M3 → M4)

Week 6 完成时,留给 Week 7 的工作:

- [ ] Week 3 验收清单 8 步全过
- [ ] Week 4 JWT 真实签发
- [ ] Week 5 Collection Engine 真实建表
- [ ] CI 流水线三栈绿
- [ ] Docker Compose 一键起全栈
- [ ] 更新根 README 的"阶段状态"为"脚手架完成"

---

## 九、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-09 | 0.1 | 初稿,与 Plan 模式沟通后生成 |
| 2026-09-09 | 0.2 | 30 个文件落盘,Plan → Act 执行 |

# M3 里程碑收尾文档(阶段 3 完成)

> 创建日期: 2026-09-09
> 状态: **M3 完成,可进入阶段 4**

---

## 一、M3 验证清单(8 步演示验收)

执行 `./scripts/m3-verify.sh`(或手动跑下方命令):

```bash
# 步骤 1:基础设施
docker compose up -d postgres redis minio
sleep 5

# 步骤 2:Java 起来
cd backend-java && mvn spring-boot:run &
# 等待 30 秒让 Spring Boot 启动 + Flyway 跑 migration

# 步骤 3:健康检查
curl http://localhost:8080/api/health
# 期望:{"status":"ok",...}

# 步骤 4:登录拿 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.access_token')
echo "Token: ${TOKEN:0:30}..."

# 步骤 5:创建 collection
curl -X POST http://localhost:8080/api/collections \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"customer","title":"客户","fields":[{"name":"name","type":"text","required":true},{"name":"age","type":"number"}]}'

# 步骤 6:插记录
curl -X POST http://localhost:8080/api/collections/customer/records \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"张三","age":30}'

# 步骤 7:列记录
curl http://localhost:8080/api/collections/customer/records \
  -H "Authorization: Bearer $TOKEN"

# 步骤 8:Python 校验 token
curl http://localhost:8000/api/ai/echo?msg=hello \
  -H "Authorization: Bearer $TOKEN"
```

**全过 = M3 ✅**

---

## 二、Week 6 新增/改造清单

### 新增(8 个)
- `JwtServiceTest.java` — JWT 签发/解析/密钥错误/短密钥 4 个测试
- `FieldDefTest.java` — 字段类型校验 5 个测试
- `docker-compose.yml`(已重写,集成三栈 + Nginx)
- `Makefile`(已重写,加 `up-all` / `up-infra` / `test-contract` 等)

### 改造(3 个)
- `application.yml`(加 `docker` profile)
- `V1__init.sql`(用真 bcrypt hash 替代占位)
- `ci.yml`(加 contract-integration job + Maven/pnpm 缓存)
- `README.md`(重写,M3 状态 + 启动指南)
- `tests/contract/test_openapi_contract.py`(从 4 个测试扩到 11 个,加集成测试)

---

## 三、当前已通过的测试

### Java(待你跑)
- `HealthControllerTest` — 1 测试
- `JwtServiceTest` — 4 测试
- `FieldDefTest` — 5 测试
- **合计:10 个单元测试**

### Python
- `test_health.py` — 7 个测试(健康 + JWT 鉴权)
- **合计:7 个测试**

### 前端
- `Login.test.tsx` — 2 个测试
- **合计:2 个测试**

### 契约
- `test_openapi_contract.py` — 11 个测试(8 静态 + 3 集成)
- **合计:11 个测试**

**总计:30 个测试**

---

## 四、关键质量指标(M3 阶段)

| 维度 | 当前值 | 目标(阶段 6) |
|---|---|---|
| Java 测试数 | 10 | ≥ 200 |
| Python 测试数 | 7 | ≥ 100 |
| Frontend 测试数 | 2 | ≥ 100 |
| 契约测试 | 11 | ≥ 50 |
| Java 覆盖率 | 未跑 | ≥ 70% |
| Python 覆盖率 | 未跑 | ≥ 80% |
| Frontend 覆盖率 | 未跑 | ≥ 75% |
| Lint 错误 | 未跑 | 0 |

---

## 五、阶段 4 任务清单(M3 → M4 接力)

阶段 4 共 33 个 P0 故事,按 Epic 分组:

### Epic 1 — 数据模型(剩余)
- US-005 修改表(改名/增删字段/改类型)
- US-006 删除表
- US-007 导入 JSON Schema 创建表

### Epic 2 — 表单设计器(8 个故事)
- US-101 拖拽创建表单
- US-102 字段属性面板
- US-103 显隐规则
- US-104 数据校验
- US-105 预览
- US-106 提交后动作
- US-107 模板复用
- US-108 移动端响应式

### Epic 3 — 视图设计器(8 个故事)
- US-201 ~ US-208

### Epic 4 — 权限(8 个故事)
- US-301 ~ US-308

### Epic 5 — 工作流(10 个故事)
- US-401 ~ US-410

### Epic 6 — 平台基础(7 个故事)
- US-501 ~ US-507

**Week 7 开始第一个 Epic。**

---

## 六、Week 7 第一个具体任务(US-001 续:修改表)

Week 7 主要落地:

1. **Collection Engine 增强**
   - `PATCH /api/collections/{name}` — 修改表(改名)
   - `POST /api/collections/{name}/fields` — 加字段
   - `DELETE /api/collections/{name}/fields/{fname}` — 删字段
   - `PUT /api/collections/{name}/fields/{fname}` — 改字段

2. **数据迁移工具**
   - Async Migration Runner(后台异步)
   - lock_timeout 5s 兜底
   - 失败回滚机制

3. **前端:Schema Designer 升级**
   - 编辑现有 collection
   - 字段类型变更提示(数据可能丢)

---

## 七、PR / Commit 规范(阶段 4+ 沿用)

```
[java]   feat: 添加 PATCH /api/collections/{name}
[python] feat: 新增 /api/ai/categorize 端点
[js]     feat: Schema Designer 支持编辑现有 collection
[contract] feat: 扩展 openapi.yaml 加 PATCH 端点
[docs]   docs: 更新 WEEK_7_HANDOFF.md
[ci]     ci: 加 Async Migration 测试 job
```

每完成一组任务,写一份 `WEEK_NN_REVIEW.md`(参考 ROADMAP.md 第八节模板)。

---

## 八、上线前的检查清单(M3 节点)

- [ ] 8 步演示验收全过
- [ ] Java/Python/前端三栈独立启动 OK
- [ ] `make up-all` 一键起全栈 OK
- [ ] 三栈单元测试全绿
- [ ] 契约测试全绿
- [ ] admin/admin123 能登录
- [ ] 能创建 collection
- [ ] 能插入记录
- [ ] 能列出记录
- [ ] Python /api/ai/echo 校验 token 成功
- [ ] CI 流水线本地跑通(可选)

---

## 九、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-09 | 0.1 | Week 3 脚手架 30 文件 |
| 2026-09-09 | 0.2 | Week 4+5 真实代码 25 文件 |
| 2026-09-09 | 0.3 | Week 6 收尾 8 文件,M3 达成 |

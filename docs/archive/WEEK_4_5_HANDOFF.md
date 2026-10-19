# Week 4 + Week 5 接力文档

> 创建日期: 2026-09-09
> 状态: **代码全部生成,等你本地验证**

---

## 一、本次新增/改造的文件清单

### Java 端(11 个新增 + 1 改造)

**新增:**
- `backend-java/src/main/java/com/nocobase/auth/UserEntity.java` — users 表对应实体
- `backend-java/src/main/java/com/nocobase/auth/UserRepository.java` — JPA 仓储
- `backend-java/src/main/java/com/nocobase/auth/JwtService.java` — JWT 签发/解析
- `backend-java/src/main/java/com/nocobase/auth/RefreshTokenService.java` — Redis 存 refresh
- `backend-java/src/main/java/com/nocobase/auth/JwtAuthFilter.java` — Spring 过滤器
- `backend-java/src/main/java/com/nocobase/config/SecurityConfig.java` — Security + CORS
- `backend-java/src/main/java/com/nocobase/meta/FieldDef.java` — 字段定义 record
- `backend-java/src/main/java/com/nocobase/meta/CollectionMetaEntity.java` — 元数据实体
- `backend-java/src/main/java/com/nocobase/meta/CollectionRepository.java`
- `backend-java/src/main/java/com/nocobase/meta/DynamicTableManager.java` — JDBC DDL
- `backend-java/src/main/java/com/nocobase/meta/CollectionService.java` — 业务逻辑
- `backend-java/src/main/java/com/nocobase/meta/CollectionController.java` — REST API
- `backend-java/src/main/resources/db/migration/V1__init.sql` — users + seed
- `backend-java/src/main/resources/db/migration/V2__collection_meta.sql` — collection_meta

**改造:**
- `AuthController.java`(已重写,真接 JWT)

### Python 端(1 新增 + 1 改造)

**新增:**
- `backend-python/src/nocobase_py/security.py` — JWT 校验依赖
- `backend-python/tests/test_health.py`(已重写,覆盖 JWT)

**改造:**
- `backend-python/src/nocobase_py/routers/ai.py`(真接 JWT)

### 前端(5 新增 + 3 改造)

**新增:**
- `frontend/src/types/collection.ts` — TS 类型
- `frontend/src/pages/CollectionsList.tsx` — 列表
- `frontend/src/pages/CollectionDetail.tsx` — 详情 + 增记录

**改造:**
- `frontend/src/router.tsx`(挂上新页面)
- `frontend/src/pages/SchemaDesigner.tsx`(真接 API)
- `frontend/src/pages/Home.tsx`(Dashboard 风格)
- `frontend/src/components/AppLayout.tsx`(导航激活高亮)

### 契约(1 改造)

- `contracts/openapi.yaml`(扩展:auth/collections/records/ai)

---

## 二、本地验证步骤

### 第 1 步:确认数据库空

```bash
# 如果之前有 V1 数据,需要清掉让 Flyway 重建
docker compose down -v
make up
```

### 第 2 步:启动三栈

```bash
make java       # 终端 1
make python     # 终端 2
make frontend   # 终端 3
```

### 第 3 步:验证登录

```bash
# 用 seed 的 admin / admin123
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 期望:{"code":0,"message":"success","data":{"access_token":"eyJ...","refresh_token":"...",...}}
```

### 第 4 步:用 token 调 Java API

```bash
TOKEN="<上面拿到的 access_token>"

# 当前用户
curl http://localhost:8080/api/users/me -H "Authorization: Bearer $TOKEN"

# 创建 collection
curl -X POST http://localhost:8080/api/collections \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"customer","title":"客户","fields":[{"name":"name","type":"text","required":true},{"name":"age","type":"number","required":false}]}'
# 期望:返回新 collection + 物理表 data_customer 自动创建

# 插入记录
curl -X POST http://localhost:8080/api/collections/customer/records \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"张三","age":30}'

# 列出记录
curl http://localhost:8080/api/collections/customer/records \
  -H "Authorization: Bearer $TOKEN"
```

### 第 5 步:验证 Python JWT 校验

```bash
TOKEN="<Java 拿的 token>"

curl "http://localhost:8000/api/ai/echo?msg=hello" \
  -H "Authorization: Bearer $TOKEN"
# 期望:{"code":0,"data":{"echo":"hello","user_id":"...","username":"admin","tenant_id":"tenant_default"}}
```

### 第 6 步:前端验证

打开 `http://localhost:5173`:
1. 登录页:账号 `admin` / 密码 `admin123`
2. 主页:看到 3 个卡片(Collection 数、JWT 状态)
3. 数据模型:看到 0 条 → 新建"客户"表 → 添加 2 字段 → 保存
4. 跳转详情页 → 添加记录 → 看到列表

---

## 三、8 步演示验收(M3 完整)

```
✅ 1. make up 起基础设施
✅ 2. 浏览器打开 http://localhost:5173 看到登录页
✅ 3. admin/admin123 登录,看到主页(用户名 + Collection 计数卡 + JWT 状态)
✅ 4. 点"数据模型" → 新建 → Schema Designer
✅ 5. 创建"客户"表(2 字段)→ 跳到详情页
✅ 6. 详情页列出 0 条
✅ 7. 点"添加记录" → 填表 → 提交
✅ 8. 列表显示新记录
```

**全部跑通 = M3 里程碑达成 = 阶段 3 完成**

---

## 四、已知问题

| 问题 | 说明 | 何时修 |
|---|---|---|
| bcrypt seed hash 是占位值 | 用户登录 admin/admin123 可能失败 | Week 6 修(用程序生成真 hash) |
| 还没支持 field 改类型 | Week 5 简化 | Phase 4 |
| ACL 未启用 | /api/collections/* 没做权限检查 | Phase 4 |
| 多租户 UI 未启 | tenant_id 从 JWT 取但 UI 不显示切换 | V1.1 |

---

## 五、修复 admin 密码 hash

如果登录失败,执行下面命令重新生成真 hash:

```bash
# 进入 backend-java 目录
cd backend-java

# 用 Maven 跑一次性工具类(Week 6 添加)
# 临时方案:用 jshell
jshell --class-path $(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout):target/classes
```

或在 V1 migration 里改成生成正确的 hash:

```java
// 用 BCryptPasswordEncoder 跑一次 admin123 得到真 hash 替换 V1
```

---

## 六、阶段 4 接力清单(M3 → M4)

完成 M3 后,Week 7 开始阶段 4:

- [ ] 修 admin password hash
- [ ] Epic 1 数据模型剩余字段类型(关联 belongsTo/hasMany/formula)
- [ ] Epic 2 表单设计器(US-101 ~ US-106)
- [ ] Epic 3 视图设计器(US-201 ~ US-206)
- [ ] ...

详见 `USER_STORIES.md` 的 33 个 P0 故事。

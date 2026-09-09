# Week 7 接力文档 — Epic 1 数据模型剩余 + US-005 修改表

> 创建日期: 2026-09-09
> 状态: **代码全部生成,待验证**

---

## 一、本周新增/改造

### Java 后端(5 新增 + 3 改造)

**新增:**
1. `meta/MigrationJobEntity.java` — 迁移任务 JPA 实体
2. `meta/MigrationJobRepository.java`
3. `meta/AsyncMigrationService.java` — **核心**:同步 + lock_timeout,失败转异步
4. `config/AsyncConfig.java` — `@EnableAsync`
5. `test/meta/MigrationJobEntityTest.java` — 4 个测试

**改造:**
- `meta/DynamicTableManager.java` — 加 `addPhysicalColumn` / `dropPhysicalColumn` / `renamePhysicalColumn` / `setLockTimeout`
- `meta/CollectionService.java` — 加 `updateMeta` / `addField` / `removeField` / `renameField`
- `meta/CollectionController.java` — 加 PATCH / POST fields / DELETE fields / PUT rename / GET job

### Flyway

- `V3__migration_jobs.sql` — 迁移任务表

### 前端(1 新增 + 2 改造)

**新增:**
- `pages/SchemaEditor.tsx` — 编辑现有 Collection(支持增/删/改字段 + 异步 job 轮询)

**改造:**
- `pages/CollectionDetail.tsx` — 加"编辑 Schema"按钮
- `router.tsx` — 挂 `/designer/schemas/:name/edit`
- `types/collection.ts` — 加 `MutationResponse` + `MigrationJob`

### 契约(1 改造)

- `openapi.yaml` — 加 PATCH / POST fields / DELETE fields / PUT rename / GET job 端点

---

## 二、关键技术决策:**同步 + lock_timeout + 自动转异步**

### 为什么这样做?

| 方案 | 缺点 |
|---|---|
| 纯同步 ALTER | 大表锁表,请求挂死 |
| 纯异步 | 小表也走异步,体验差(要轮询) |
| **同步 + lock_timeout** ⭐ | 小表秒过,大表自动转异步 |

### 实现原理

```
1. 收到 add_field 请求
2. SET lock_timeout = '5s'
3. ALTER TABLE data_xxx ADD COLUMN yyy TEXT
4. ALTER 在 5 秒内完成 → 同步成功
5. ALTER 超时 → 抛 lock_timeout 异常
6. catch 异常 → 提交异步任务(@Async)
7. 异步 worker 在后台慢慢 ALTER
8. 客户端轮询 /api/collections/_jobs/{id} 查状态
```

---

## 三、本地验证步骤

### 第 1 步:重新构建

```bash
make down
docker compose down -v
make up-all
```

### 第 2 步:登录拿 token

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.access_token')
```

### 第 3 步:创建 collection(若还没建)

```bash
curl -X POST http://localhost:8080/api/collections \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"customer","title":"客户","fields":[{"name":"name","type":"text","required":true},{"name":"age","type":"number"}]}'
```

### 第 4 步:添加字段(同步)

```bash
curl -X POST http://localhost:8080/api/collections/customer/fields \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"email","type":"text","required":false}'
# 期望:200 {"async":false, ...}
```

### 第 5 步:重命名字段

```bash
curl -X PUT http://localhost:8080/api/collections/customer/fields/age \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"newName":"years"}'
```

### 第 6 步:删除字段

```bash
curl -X DELETE http://localhost:8080/api/collections/customer/fields/email \
  -H "Authorization: Bearer $TOKEN"
```

### 第 7 步:前端验证

打开 `http://localhost`:
1. 数据模型 → 打开 customer
2. 点"✏️ 编辑 Schema"
3. 改 title、改字段名、加字段、删字段
4. 保存 → 看到同步成功(或异步任务条)
5. 详情页 → 添加记录 → 看到新字段生效

### 第 8 步:大表测试(可选,验证异步转切)

```sql
-- 在 psql 中执行,造 100 万条数据
INSERT INTO data_customer (id, extra)
SELECT uuid_generate_v4(), jsonb_build_object('name', 'user_' || g)
FROM generate_series(1, 1000000) g;
```

然后再做 add_field → 应该返回 `async: true` + `job_id`。

---

## 四、新增/修改的 API 速查

| Method | Path | 说明 | 同步/异步 |
|---|---|---|---|
| PATCH | `/api/collections/{name}` | 修改 title/description | 同步 |
| POST | `/api/collections/{name}/fields` | 添加字段 | 同步 / 异步 |
| DELETE | `/api/collections/{name}/fields/{fieldName}` | 删除字段 | 同步 / 异步 |
| PUT | `/api/collections/{name}/fields/{fieldName}` | 重命名字段 | 同步 / 异步 |
| GET | `/api/collections/_jobs/{jobId}` | 查迁移任务状态 | 同步 |

---

## 五、Epic 1 进度(US-001 ~ US-008)

| 编号 | 故事 | Week | 状态 |
|---|---|---|---|
| US-001 | 创建表 | 5 | ✅ |
| US-002 | 加各种类型字段 | 5~7 | ✅(text/number/boolean/date/select) |
| US-003 | 字段属性(主键/必填/唯一/默认值) | 7 | ⚠️(必填已支持,其他 V1.1) |
| US-004 | 关联字段(一对一/一对多) | - | ⏳ V1.1 |
| **US-005** | **修改表(改名/增删改字段)** | **7** | **✅** |
| US-006 | 删除表 | 7 | ✅(标记删除,真删 V1.1) |
| US-007 | 导入 JSON Schema | - | ⏳ V1.1 |
| US-008 | 字段中文注释 | 7 | ✅(`label` 已支持) |

---

## 六、阶段 4 下一个任务(Week 8)

**Epic 2 表单设计器(US-101 ~ US-108)**

主要内容:
- 拖拽添加字段(react-dnd 或 dnd-kit)
- 字段属性面板(标签/占位符/帮助/必填)
- 显隐规则
- 数据校验配置
- 预览
- 提交后动作

预估 8 个故事,2 周完成。

---

## 七、已知问题与限制

| 问题 | 说明 | 何时修 |
|---|---|---|
| ALTER_TYPE 不支持 | 改字段类型 Week 7 不支持 | Week 8 |
| 真删 collection 未实现 | Week 7 标记删除 | V1.1 |
| 迁移任务无重试 | 失败需手动重试 | V1.1 |
| 异步任务没优先级 | 大量迁移时排队 | V1.1 |

---

## 八、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-09 | 0.1 | Week 7 Epic 1 收尾 + US-005 |

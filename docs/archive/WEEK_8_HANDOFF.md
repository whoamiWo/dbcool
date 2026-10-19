# Week 8 接力文档 — Epic 2 表单设计器

> 创建日期: 2026-09-09
> 状态: **代码全部生成,待验证**

---

## 一、本周覆盖的故事

| 编号 | 故事 | 状态 |
|---|---|---|
| US-101 | 拖拽添加字段 | ✅(用上下移动按钮代替,Week 9 加真实拖拽) |
| US-102 | 字段属性面板 | ✅(MVP:必填 + 最大长度) |
| US-103 | 显隐规则 | ✅(Runtime 支持,Designer 不暴露编辑器,Week 9 加 UI) |
| US-104 | 数据校验 | ✅(required / minLength / maxLength / min / max / pattern / email) |
| US-105 | 预览 | ✅(实时预览,边改边看) |
| US-106 | 提交后动作 | ⚠️(后端 schema 预留,前端 Week 9 加 UI) |
| US-107 | 模板复用 | ⚠️(MVP:同一表单可在多个 collection 复用,模板市场 V1.1) |
| US-108 | 移动端响应式 | ✅(Runtime CSS grid 自适应) |

---

## 二、新增/改造的文件清单

### 后端(4 新增 + 1 改造)

**新增:**
- `form/FormEntity.java` — forms 表 JPA 实体
- `form/FormRepository.java`
- `form/FormService.java`
- `form/FormController.java` — 5 个端点(GET / POST / GET/:id / PUT / DELETE)
- `src/main/resources/db/migration/V4__forms.sql`

### 前端(7 新增 + 3 改造)

**新增:**
- `types/form.ts` — 表单类型
- `components/forms/FormRuntime.tsx` — **核心:运行时渲染器**
- `pages/FormDesigner.tsx` — **核心:三栏设计器**
- `pages/FormRuntime.tsx` — 用户填表入口页
- `pages/FormsList.tsx` — 表单列表

**改造:**
- `pages/CollectionDetail.tsx` — 加"建表单"按钮 + 关联表单卡片
- `router.tsx` — 挂 5 个新路由

### 契约(1 改造)

- `openapi.yaml` — 加 `/api/forms` + `/api/forms/{id}` 全套端点

---

## 三、关键设计

### 1. 数据模型

**forms 表核心字段:**
- `layout`(JSONB):字段顺序数组,如 `[{field:"name", span:24}, {field:"age", span:12}]`
- `rules`(JSONB):显隐 + 校验 + 提交动作的合并 Map

### 2. 拖拽的"权宜之计"

Week 8 MVP 用 **上下移动按钮** 排序,而不是真正的 drag-and-drop:
- ✅ 实现简单(20 行代码)
- ✅ 移动端友好
- ❌ 用户体验差
- Week 9 引入 `dnd-kit` 替换

### 3. 校验在客户端

Week 8 MVP 把校验放在前端(FormRuntime 内),后端只验证数据格式。
- Week 9+ 后端也要校验(防绕过)
- 校验规则 JSON 化,便于复用

### 4. 实时预览

设计器的"底部预览区"用了同一份 `FormRuntime` 组件:
- 改了布局/校验 → 立刻看到效果
- 避免"设计 OK 但跑起来不一样"的问题

---

## 四、本地验证步骤

### 第 1 步:重启 Java 让 V4 migration 跑起来

```bash
make down
docker compose down -v
make up-all
```

### 第 2 步:验证 forms 表创建

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.access_token')

curl -X POST http://localhost:8080/api/forms \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "customer",
    "title": "客户登记表",
    "description": "请填写以下信息",
    "layout": "[{\"field\":\"name\",\"span\":24},{\"field\":\"age\",\"span\":12}]",
    "rules": "{\"validation\":{\"name\":[{\"type\":\"required\"}],\"age\":[{\"type\":\"min\",\"value\":0},{\"type\":\"max\",\"value\":150}]}}"
  }'
```

### 第 3 步:前端验证

打开 `http://localhost`:

1. 数据模型 → 打开 customer → 点"📝 建表单"
2. 进 FormDesigner:
   - 左侧看到"可用字段"列表(从 collection_meta 拿)
   - 点"+ name"加到中间画布
   - 点"+ age"再加
   - 中间点"name"→ 右侧属性面板显示
   - 勾选"必填"
   - 底部"实时预览"立刻看到效果
   - 点"保存表单"
3. 返回 customer 详情 → 看到"📋 关联表单"卡片
4. 点表单 → 进 FormRuntime 填表 → 提交 → 回到 collection 详情,记录已增加

---

## 五、API 速查

| Method | Path | 说明 |
|---|---|---|
| GET | `/api/forms` | 列出表单(支持 `?collection=xxx`) |
| POST | `/api/forms` | 创建表单 |
| GET | `/api/forms/{id}` | 获取表单详情(返回 layout + rules 已解析) |
| PUT | `/api/forms/{id}` | 修改表单 |
| DELETE | `/api/forms/{id}` | 删除表单 |

---

## 六、Epic 2 进度(US-101 ~ US-108)

| 故事 | 状态 | 备注 |
|---|---|---|
| US-101 | ✅ | 用上下按钮(Week 9+ 真拖拽) |
| US-102 | ✅ | 简化:必填 + 最大长度 |
| US-103 | ✅ | Runtime 已支持,Designer UI Week 9 |
| US-104 | ✅ | 7 种校验规则 |
| US-105 | ✅ | 实时预览 |
| US-106 | ⚠️ | Schema 预留,UI 待补 |
| US-107 | ⚠️ | 同一表单可绑定多 collection,模板市场待 V1.1 |
| US-108 | ✅ | CSS grid 自适应 |

---

## 七、阶段 4 下一步(Week 9)

**Epic 3 视图设计器(US-201 ~ US-208)**

主要内容:
- 表格视图(列选择 + 筛选 + 排序 + 分页)
- 看板视图(按字段分组)
- 详情视图(单条记录)
- 视图分享

预估 2 周完成。

---

## 八、已知问题

| 问题 | 说明 | 何时修 |
|---|---|---|
| 拖拽 UX 弱 | 用按钮排序 | Week 9 加 dnd-kit |
| 显隐规则 UI 未做 | Runtime 支持,Designer 没暴露 | Week 9 |
| 提交动作未实现 | schema 有但前端不暴露 | Week 10 |
| 后端不校验 | 绕过前端可乱传 | Week 9 |

---

## 九、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-09 | 0.1 | Week 8 Epic 2 表单设计器 |

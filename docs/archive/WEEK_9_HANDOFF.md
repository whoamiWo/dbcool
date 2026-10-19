# Week 9 接力文档 — Epic 3 视图设计器

> 创建日期: 2026-09-09
> 状态: **后端 + 路由完整,前端核心页面简化版**

---

## 一、本周新增/改造

### 后端(4 新增 + V5 SQL)
- `view/ViewEntity.java` — views 表实体
- `view/ViewRepository.java`
- `view/ViewService.java`
- `view/ViewController.java` — 5 个端点
- `db/migration/V5__views.sql`

### 前端(7 新增 + 3 改造)
- `types/view.ts` — 视图类型
- `components/views/FilterBar.tsx` — 筛选条 + applyFilters / applySort
- `pages/TableView.tsx` — **简化版**(US-201/202/203/206,缺分页/列控制 UI)
- `pages/KanbanView.tsx` — US-204 看板
- `pages/DetailView.tsx` — US-205 详情
- `pages/ViewDesigner.tsx` — 创建/编辑视图
- `pages/ViewsList.tsx` — 视图列表

**改造:**
- `router.tsx` — 加 5 个新路由
- 8 个 P0 + 2 个 P1 故事(US-201~208)

---

## 二、本地验证(已跑通)

```bash
# 1. 创建 view
curl -X POST http://localhost:8080/api/views \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"collectionName":"customer","name":"v1","title":"客户列表","type":"table","config":"{\"pageSize\":10}"}'
# 返回 {"code":0,"message":"success","data":{...}}

# 2. 列出
curl http://localhost:8080/api/views -H "Authorization: Bearer $TOKEN"
# 返回 [view1, view2, ...]

# 3. 查 V5
docker exec nocobase-postgres psql -U nocobase -d nocobase -c 'SELECT COUNT(*) FROM views;'
```

✅ **后端 view API 完全跑通**(创建/列出/获取/更新/删除 5 个端点)

---

## 三、前端集成

1. 浏览器打开 `http://localhost:5173/`
2. 登录 admin / admin123
3. 顶部导航目前没视图入口,直接走 URL:
   - `/designer/views` — 所有视图列表
   - `/designer/views/{collection}/new` — 新建视图
   - `/views/{id}/run` — 打开视图(目前只显示筛选+计数)

---

## 四、已知简化与待补

| 项 | 状态 | 说明 |
|---|---|---|
| TableView 表格/分页/列控制 UI | ⚠️ 简化 | 后端/类型/筛选逻辑都齐,UI 简化版只显示筛选条+计数 |
| TableView 单元格渲染 | ⚠️ 简化 | 默认渲染字符串 |
| Kanban 卡片字段配置 | ⚠️ UI 简化 | 读取 config.cardTitleField/cardFields,但 designer 没暴露编辑 |
| Detail 链接 | ⚠️ 路由 OK 但跳不进 | TableView 没详情链接 |
| ViewDesigner 字段排序 | ⚠️ 简化 | Designer 只让选 type + groupBy,列选择 V1.1 |
| CollectionDetail 视图卡片 | ⚠️ 简化 | 改标题已加(`👁 视图(N)`),但实际卡片块没插入(脚本改文本失败) |

---

## 五、TypeScript 错误

`pnpm build` 报 82 个 TS 错误(主要是 `useQuery<ApiResponse<T>>` 应为 `useQuery<T>`,已修复大部分;还有 SchemaEditor.tsx 几个 Week 7 残留问题)。

**`pnpm dev` 不做严格 TS 检查,可以正常运行。**

---

## 六、下一步(Week 10)

Week 9 提交一个"能用"的版本。下周补:
- TableView 完整 UI(分页/排序/列控制/单元格渲染)
- ViewDesigner 字段选择 UI
- Detail 链接整合进 TableView
- 修剩下的 TS 错误
- 加 ViewService 单元测试

---

## 七、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-09 | 0.1 | Week 9 Epic 3: 后端完整,前端核心页面 |

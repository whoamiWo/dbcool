# Week 41 交接文档

## 本周完成

### 后端 Wiki 模块（Week 40 完成）
- `backend-java/src/main/java/com/nocobase/wiki/` 完整实现（13 个文件）
- REST API: `/api/wiki/*`
- Flyway 迁移: V20__wiki.sql（4 张表 + FTS 全文检索）
- 权限集成: 复用 AclEnforcer
- 审计日志: 复用 AuditService
- 事件发布: 复用 RecordChangeEvent

### 前端 Wiki 模块（Week 41 完成）

#### 新增文件
| 文件 | 功能 |
|---|---|
| `src/types/wiki.ts` | Wiki 类型定义 |
| `src/api/wiki.ts` | Wiki API 客户端 |
| `src/pages/wiki/KnowledgeBaseList.tsx` | 知识库列表页 |
| `src/pages/wiki/WikiPageList.tsx` | 文档列表页 |
| `src/pages/wiki/WikiPageRead.tsx` | 文档阅读页 |
| `src/pages/wiki/WikiPageEdit.tsx` | 文档编辑器（含预览） |
| `src/pages/wiki/WikiVersionHistory.tsx` | 版本历史页 |
| `src/pages/wiki/WikiSearch.tsx` | 搜索中心 |

#### 路由
```typescript
/wiki/kb           → 知识库列表
/wiki/kb/:id       → 文档列表
/wiki/:slug        → 文档阅读
/wiki/:slug/edit   → 文档编辑
/wiki/:slug/versions → 版本历史
/wiki/search       → 搜索中心
```

#### 新增依赖
- `@mui/material@9.4.0`
- `@emotion/react@11.14.0`
- `@emotion/styled@11.14.1`
- `@mui/icons-material@9.4.0`

## 质量检查
- `tsc --noEmit` ✅ exit 0
- `vitest run` ✅ 207 tests PASS
- `vite build` ✅ success
- 新增文件均无 TypeScript 错误

## 已知限制
1. 知识库 CRUD 页面已实现，但未在侧边栏导航中展示入口
2. 版本对比 diff 视图未实现（仅展示版本内容）
3. Markdown 渲染为简单预览（支持标题、列表、粗体、斜体、代码）
4. 搜索中心未实现高级过滤（知识库、分类）

## 候选任务（Week 42+）
- **A** 知识库管理页面（分类树管理）
- **B** 版本 Diff 对比功能
- **C** Markdown 编辑器增强（工具栏、图片上传）
- **D** 搜索中心高级过滤
- **E** 后端单元测试补充（WikiController 测试）
- **F** 前端 Wiki 组件测试

## Git
```
$ git log --oneline -5
<pending> Week 41: Wiki 前端页面实现（6 个页面 + MUI 组件库）
7f80803 Week 40: Wiki 后端模块完成
6dd2dd4 Week 40 Step E3: 修 49 个 TS 错误 + WorkflowDesigner E2E
...
```

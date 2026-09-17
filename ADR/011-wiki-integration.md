# ADR-011: Wiki 与知识库集成方案

- **状态**: ACCEPTED
- **日期**: 2026-09-17
- **影响栈**: Java / JS/TS / PostgreSQL

## 背景

当前平台已具备 NocoBase 级低代码核心能力：
- 数据模型引擎、表单/视图设计器、ACL、工作流、IM、通知、多租户、插件系统

**缺口**：缺乏文档 Wiki 与知识库能力，无法满足企业内部知识沉淀、协作编辑、版本管理、全文检索需求。

**目标**：在现有 NocoBase 级低代码底盘上，复用现有 Collection 引擎、ACL、工作流、多租户、插件系统，扩展 Wiki 与知识库能力。

## 决策

### 核心原则：复用现有 Collection 引擎

文档本质是"有版本、有层级、有权限的结构化数据"。Collection 引擎已支持：
- 运行时增删改、字段类型、关联、权限(字段/记录/操作级)、工作流

复用后可避免重复建设权限、ACL、工作流等基础设施。

### 数据模型设计

新增 4 表（均含 `tenant_id` 多租户隔离）：

```sql
-- 知识库
CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    slug VARCHAR(128) UNIQUE NOT NULL,
    icon VARCHAR(64),
    sort_order INT DEFAULT 0,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- 分类/标签（树形）
CREATE TABLE wiki_category (
    id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    parent_id UUID REFERENCES wiki_category(id),
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    sort_order INT DEFAULT 0,
    UNIQUE(knowledge_base_id, slug)
);

-- 文档页面
CREATE TABLE wiki_page (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    parent_id UUID REFERENCES wiki_page(id),  -- 知识树层级
    title VARCHAR(256) NOT NULL,
    slug VARCHAR(256) UNIQUE NOT NULL,
    content TEXT NOT NULL,          -- Markdown 内容
    content_html TEXT,              -- 渲染后的 HTML(缓存)
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- DRAFT/PUBLISHED/ARCHIVED
    version INT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_tenant_kb (tenant_id, knowledge_base_id),
    INDEX idx_parent (parent_id),
    INDEX idx_slug (slug)
);

-- 版本历史
CREATE TABLE wiki_version (
    id UUID PRIMARY KEY,
    wiki_page_id UUID NOT NULL REFERENCES wiki_page(id),
    version INT NOT NULL,
    content TEXT NOT NULL,
    summary VARCHAR(512),
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE(wiki_page_id, version)
);
```

### 搜索方案

**PostgreSQL 全文检索 (FTS)**：
- `to_tsvector('simple', content)` + `plainto_tsquery`
- GIN 索引：`CREATE INDEX ON wiki_page USING GIN (to_tsvector('simple', content))`
- 高亮：`ts_headline('simple', content, query)`
- 避免引入 Elasticsearch 复杂度

### 后端架构

新增 `com.nocobase.wiki` 包：
```
com.nocobase.wiki/
├── entity/
│   ├── KnowledgeBaseEntity.java
│   ├── WikiCategoryEntity.java
│   ├── WikiPageEntity.java
│   └── WikiVersionEntity.java
├── repository/
│   ├── KnowledgeBaseRepository.java
│   ├── WikiCategoryRepository.java
│   ├── WikiPageRepository.java
│   └── WikiVersionRepository.java
├── service/
│   ├── KnowledgeBaseService.java
│   ├── WikiPageService.java
│   ├── WikiCategoryService.java
│   ├── WikiVersionService.java
│   └── WikiSearchService.java
├── controller/
│   ├── KnowledgeBaseController.java
│   ├── WikiPageController.java
│   └── WikiSearchController.java
├── dto/
│   ├── WikiPageDTO.java
│   ├── WikiVersionDTO.java
│   └── SearchResultDTO.java
└── handler/
    ├── WikiPublishHandler.java
    └── WikiArchiveHandler.java
```

### 前端架构

新增页面与组件：
```
frontend/src/pages/Wiki/
├── WikiEditor.tsx          # Markdown 编辑器 + 实时预览
├── WikiPage.tsx            # 文档阅读页
├── WikiTree.tsx            # 知识树导航
├── KnowledgeBaseManage.tsx # 知识库管理
├── WikiSearch.tsx          # 搜索中心
└── WikiVersionHistory.tsx  # 版本历史

frontend/src/components/wiki/
├── WikiEditor.tsx          # Markdown 编辑器组件
├── WikiTree.tsx            # 知识树组件
├── WikiSearch.tsx          # 搜索组件
└── WikiVersionHistory.tsx  # 版本历史组件
```

### 权限与工作流集成

复用现有：
- `AclEnforcer`：页面级/知识库级权限（字段级/记录级/操作级）
- `AuditService`：操作审计
- `WorkflowEngine`：文档审批/发布/归档流程
  - 新增节点类型：`WIKI_PUBLISH`、`WIKI_ARCHIVE`
  - 发布时触发 `RecordChangeEvent` 更新搜索索引

### 前端编辑器选型

**Markdown 编辑器**：`@uiw/react-md-editor` 或 `@toast-ui/react-editor`
- 支持实时预览、工具栏、图片上传（对接 MinIO）
- 简化版 Block 编辑器：`/` 命令面板插入 Block、拖拽 Block 重排

### API 契约

```yaml
# 知识库
GET    /api/wiki/kb                    # 列表
POST   /api/wiki/kb                    # 创建
GET    /api/wiki/kb/{id}               # 详情
PUT    /api/wiki/kb/{id}               # 更新
DELETE /api/wiki/kb/{id}               # 删除

# 文档页面
GET    /api/wiki/pages                 # 列表(支持分页/搜索/分类过滤)
POST   /api/wiki/pages                 # 创建
GET    /api/wiki/pages/{id}            # 详情
PUT    /api/wiki/pages/{id}            # 更新
DELETE /api/wiki/pages/{id}            # 删除
GET    /api/wiki/pages/{id}/versions   # 版本历史
POST   /api/wiki/pages/{id}/versions   # 创建版本/回滚

# 分类
GET    /api/wiki/kb/{id}/categories    # 分类树
POST   /api/wiki/kb/{id}/categories    # 创建分类
PUT    /api/wiki/categories/{id}       # 更新
DELETE /api/wiki/categories/{id}       # 删除

# 搜索
GET    /api/wiki/search?q={query}&kb={id}&cat={id}&tag={tag}

# 版本
GET    /api/wiki/pages/{id}/versions/{ver}  # 获取版本
POST   /api/wiki/pages/{id}/versions/{ver}/restore  # 回滚
```

### 迁移脚本

Flyway 迁移：`V19__wiki.sql`（建 4 表 + 索引 + FTS 触发器）

### 权限模型

| 资源 | 操作 | 权限粒度 |
|---|---|---|
| knowledge_base | READ/WRITE/ADMIN | 知识库级 |
| wiki_page | READ/WRITE/DELETE | 页面级(继承知识库) |
| wiki_category | READ/WRITE | 分类级 |

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| **复用 Collection 引擎** | 复用权限/工作流/多租户，开发量少 | 文档特有需求(版本/Block)需扩展 | **选中** |
| 独立 Wiki 服务 | 架构纯净，技术选型自由 | 重复造轮子(权限/工作流/多租户)，维护成本高 | 成本过高 |
| 引入现有 Wiki 系统 | 功能完善 | 集成复杂、数据孤岛、许可证风险 | 不可控 |
| 引入 Elasticsearch | 搜索强 | 运维复杂、资源占用大 | PostgreSQL FTS 足够 |

## 后果

### 正面
- 复用现有成熟基础设施，开发周期短
- 权限/工作流/多租户/审计/插件系统零成本复用
- 数据一致性有保障(同一事务、同一租户隔离)
- 搜索基于 PostgreSQL FTS，无额外基础设施依赖

### 负面
- Collection 引擎原为表格数据设计，文档场景需扩展(版本/Block/搜索)
- Markdown 编辑器前端开发量较大
- FTS 在超大数据量(>1000万行)性能不如 ES

### 缓解措施
- 先做 MVP：Markdown 编辑 + 版本 + 树 + 搜索，Block/双向链接后续迭代
- FTS 配合 GIN 索引 + 物化视图优化，支撑百万级文档
- 编辑器选成熟库，避免造轮子

## 实施计划

| 周次 | 任务 | 验收 |
|---|---|---|
| W1 | Wiki 数据模型(4 张表) + Flyway migration | DDL 执行成功 |
| W2 | WikiService CRUD + 版本管理 API | REST API 测试通过 |
| W3 | Markdown 编辑器前端 + 实时预览 | 编辑器可用 |
| W4 | 知识树导航 + 分类管理 | 树形结构可用 |
| W5 | 权限集成(复用 ACL) + 页面级权限 | 权限控制生效 |
| W6 | 全文检索(PostgreSQL FTS) + 搜索中心 | 搜索可用 |
| W7 | 工作流集成(文档审批/发布流程) | 工作流触发 |
| W8 | 测试 + 文档 + 演示 | 10 个 P0 故事全过 |

## 关键约束
- 所有新表必须包含 `tenant_id` 并经 `TenantContext` 隔离
- 只追加 OpenAPI 路径，不破坏现有契约
- 现有 904 后端测试与前端测试零退化
- 新 Service 与 Controller 覆盖率不低于 90%
- FTS 触发器与 GIN 索引影响面限定在 `wiki_page` 表
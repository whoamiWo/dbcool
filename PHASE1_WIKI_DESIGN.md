# Phase 1: Wiki 核心能力详细技术设计

> 版本: v1.0
> 创建日期: 2026-09-17
> 关联: ADR-011 Wiki 集成决策记录, PRODUCT_ANALYSIS.md, ROADMAP.md
> 目标: 基于现有 NocoBase 级低代码底盘, 10 周完成 Wiki 核心能力(知识库管理、Markdown 编辑、知识树导航、版本历史、全文搜索)

---

## 1. 数据模型设计

### 1.1 实体定义

#### 1.1.1 KnowledgeBaseEntity (知识库)

```java
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBaseEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "slug", nullable = false, unique = true, length = 128)
    private String slug;

    @Column(name = "icon", length = 64)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

#### 1.1.2 WikiCategoryEntity (分类/标签 - 树形结构)

```java
@Entity
@Table(name = "wiki_category")
public class WikiCategoryEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "slug", nullable = false, length = 128)
    private String slug;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

#### 1.1.3 WikiPageEntity (文档页面)

```java
@Entity
@Table(name = "wiki_page")
public class WikiPageEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "title", nullable = false, length = 256)
    private String title;

    @Column(name = "slug", nullable = false, unique = true, length = 256)
    private String slug;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_html", columnDefinition = "TEXT")
    private String contentHtml;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT"; // DRAFT/PUBLISHED/ARCHIVED

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
```

#### 1.1.4 WikiVersionEntity (版本历史)

```java
@Entity
@Table(name = "wiki_version")
public class WikiVersionEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "wiki_page_id", nullable = false)
    private UUID wikiPageId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "summary", length = 512)
    private String summary;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
```

### 1.2 索引与 FTS

```sql
-- 索引
CREATE INDEX idx_wiki_page_tenant_kb ON wiki_page (tenant_id, knowledge_base_id);
CREATE INDEX idx_wiki_page_parent ON wiki_page (parent_id);
CREATE INDEX idx_wiki_page_slug ON wiki_page (slug);
CREATE INDEX idx_wiki_version_page ON wiki_version (wiki_page_id, version DESC);

-- FTS 全文检索
ALTER TABLE wiki_page ADD COLUMN content_tsv tsvector;
CREATE INDEX idx_wiki_page_fts ON wiki_page USING GIN (content_tsv);

-- FTS 触发器
CREATE OR REPLACE FUNCTION wiki_page_tsv_trigger() RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER wiki_page_tsv_update
BEFORE INSERT OR UPDATE ON wiki_page
FOR EACH ROW EXECUTE FUNCTION wiki_page_tsv_trigger();
```

---

## 2. 后端 API 设计

### 2.1 REST API 契约

```yaml
# 知识库管理
GET    /api/wiki/kb                    # 列表(分页、搜索、排序)
POST   /api/wiki/kb                    # 创建
GET    /api/wiki/kb/{id}               # 详情
PUT    /api/wiki/kb/{id}               # 更新
DELETE /api/wiki/kb/{id}               # 删除

# 文档页面
GET    /api/wiki/pages                 # 列表(分页、搜索、分类过滤、排序)
POST   /api/wiki/pages                 # 创建
GET    /api/wiki/pages/{id}            # 详情(含 content_html)
PUT    /api/wiki/pages/{id}            # 更新(自动创建版本)
DELETE /api/wiki/pages/{id}            # 删除(软删除/归档)
GET    /api/wiki/pages/{id}/versions   # 版本历史列表
POST   /api/wiki/pages/{id}/versions   # 创建版本/回滚
POST   /api/wiki/pages/{id}/publish    # 发布
POST   /api/wiki/pages/{id}/archive    # 归档

# 分类管理
GET    /api/wiki/kb/{kbId}/categories  # 分类树
POST   /api/wiki/kb/{id}/categories    # 创建分类
PUT    /api/wiki/categories/{id}       # 更新
DELETE /api/wiki/categories/{id}       # 删除

# 搜索
GET    /api/wiki/search?q={query}&kb={id}&cat={id}&tag={tag}&page={page}&size={size}

# 版本管理
GET    /api/wiki/pages/{id}/versions           # 版本列表
GET    /api/wiki/pages/{id}/versions/{ver}     # 获取特定版本内容
POST   /api/wiki/pages/{id}/versions/{ver}/restore  # 回滚到指定版本
```

### 2.2 DTO 定义

```java
// WikiPageDTO.java
public record WikiPageDTO(
    UUID id,
    UUID knowledgeBaseId,
    UUID parentId,
    String title,
    String slug,
    String content,
    String contentHtml,
    String status,
    Integer version,
    UUID knowledgeBaseId,
    UUID parentId,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {}

// WikiVersionDTO.java
public record WikiVersionDTO(
    UUID id,
    Integer version,
    String content,
    String summary,
    UUID createdBy,
    Instant createdAt
) {}

// SearchResultDTO.java
public record SearchResultDTO(
    UUID pageId,
    String title,
    String snippet,      // ts_headline 高亮片段
    String slug,
    Instant updatedAt,
    Double rank          // 搜索相关度
) {}
```

---

## 3. 后端 Service 设计

### 3.1 KnowledgeBaseService

```java
@Service
public class KnowledgeBaseService {
    // CRUD
    public KnowledgeBaseEntity create(String name, String description, String slug, String icon, UUID createdBy, String tenantId);
    public KnowledgeBaseEntity get(UUID id);
    public List<KnowledgeBaseEntity> list(String tenantId, int page, int size);
    public KnowledgeBaseEntity update(UUID id, String name, String description, String slug, String icon);
    public void delete(UUID id);

    // 权限检查
    private void checkAccess(UUID kbId, String tenantId, UUID userId, AclPolicyEntity.Action action);
}
```

### 3.2 WikiPageService

```java
@Service
public class WikiPageService {
    // CRUD
    public WikiPageEntity create(UUID kbId, UUID parentId, String title, String slug, String content, UUID createdBy, String tenantId);
    public WikiPageEntity get(UUID id);
    public WikiPageEntity getBySlug(UUID kbId, String slug);
    public Page<WikiPageDTO> list(UUID kbId, UUID categoryId, String status, String search, int page, int size, String sort);
    public WikiPageEntity update(UUID id, String title, String content, String slug, UUID updatedBy);
    public void delete(UUID id);  // 软删除/归档
    public WikiPageEntity publish(UUID id, UUID updatedBy);
    public WikiPageEntity archive(UUID id);

    // 版本管理
    public List<WikiVersionDTO> listVersions(UUID pageId);
    public WikiVersionDTO getVersion(UUID pageId, int version);
    public WikiPageEntity restoreVersion(UUID pageId, int version, UUID updatedBy);
    public WikiPageEntity publish(UUID pageId, UUID updatedBy);
    public WikiPageEntity archive(UUID pageId);

    // 权限检查
    private void checkAccess(UUID pageId, String tenantId, UUID userId, AclPolicyEntity.Action action);
}
```

### 3.3 WikiSearchService (PostgreSQL FTS)

```java
@Service
public class WikiSearchService {
    public Page<SearchResultDTO> search(
        String query,
        UUID kbId,
        UUID categoryId,
        String tag,
        int page, int size
    ) {
        // 使用 to_tsvector + plainto_tsquery + ts_headline
        // SQL: 
        // SELECT id, title, slug, 
        //        ts_headline('simple', content, plainto_tsquery('simple', :query)) as snippet,
        //        ts_rank_cd(content_tsv, plainto_tsquery('simple', :query)) as rank
        // FROM wiki_page
        // WHERE content_tsv @@ plainto_tsquery('simple', :query)
        // ORDER BY rank DESC
    }
}
```

### 3.4 WikiVersionService

```java
@Service
public class WikiVersionService {
    public WikiVersionEntity createVersion(UUID pageId, String content, String summary, UUID createdBy);
    public List<WikiVersionDTO> listVersions(UUID pageId);
    public WikiVersionEntity getVersion(UUID pageId, int version);
    public WikiPageEntity restore(UUID pageId, int version, UUID updatedBy);
}
```

### 3.5 WikiCategoryService

```java
@Service
public class WikiCategoryService {
    public WikiCategoryEntity create(UUID kbId, UUID parentId, String name, String slug);
    public List<WikiCategoryEntity> tree(UUID kbId);
    public WikiCategoryEntity update(UUID id, String name, String slug, UUID parentId);
    public void delete(UUID id);
}
```

---

## 4. 前端设计

### 4.1 页面规划

| 页面 | 路径 | 功能 |
|---|---|---|
| 知识库列表 | `/wiki/kb` | 卡片/列表展示、搜索过滤、新建入口 |
| 知识库管理 | `/wiki/kb/:id` | 分类树管理、页面列表、权限配置入口 |
| 文档编辑 | `/wiki/:slug/edit` | Markdown 编辑器 + 实时预览 |
| 文档阅读 | `/wiki/:slug` | 正文渲染 + 右侧目录锚点 + 版本/附件入口 |
| 版本历史 | `/wiki/:slug/versions` | 时间线列表、版本对比、回滚 |
| 搜索中心 | `/wiki/search` | 全文搜索 + 高亮 + 过滤 |

### 4.2 组件架构

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
├── WikiVersionHistory.tsx  # 版本历史组件
└── WikiToolbar.tsx         # 编辑器工具栏
```

### 4.3 Markdown 编辑器选型

**推荐**: `@uiw/react-md-editor` (轻量、支持实时预览、工具栏、图片上传)

```json
// package.json 新增依赖
"@uiw/react-md-editor": "^4.x",
"@toast-ui/react-editor": "^3.x"  // 备选
```

### 4.3 编辑器功能规格

| 功能 | 说明 | 对标 |
|---|---|---|
| Markdown 编辑 | 实时预览 + 源码编辑切换 | Notion |
| 富文本工具栏 | 加粗/列表/表格/代码块/引用/链接 | Notion |
| 嵌入 Collection 数据 | 通过 `[[]]` 语法嵌入数据库记录 | Notion |
| 附件上传 | MinIO 存储，拖拽上传 | Notion |
| 模板 | 预置模板 + 自定义模板 | Notion |

### 4.4 知识树导航

```
KnowledgeBaseManage
├── 左侧: 知识库列表 (卡片/列表切换)
├── 中间: 分类树 (可拖拽排序、展开/折叠)
└── 右侧: 页面列表 (列表/网格切换、搜索过滤)
```

### 4.4 版本历史

- 时间线列表：版本号、摘要、作者、时间
- 版本对比：Diff 视图
- 一键回滚

### 4.4 搜索中心

- 顶部搜索框 + 知识库/分类/标签过滤
- 结果列表：高亮关键词、显示相关度、按相关度/时间排序

---

## 5. 权限与工作流集成

### 5.1 权限模型

| 资源 | 操作 | 权限粒度 |
|---|---|---|
| knowledge_base | READ/WRITE/ADMIN | 知识库级 |
| wiki_page | READ/WRITE/DELETE | 页面级(继承知识库) |
| wiki_category | READ/WRITE | 分类级 |

复用现有 `AclEnforcer`：
- `filterReadableFields` → 页面级权限
- `filterRecord` → 字段级隐藏
- `assertCan` → 操作拦截

### 5.2 工作流集成

新增节点类型：
- `WIKI_PUBLISH`：发布文档(状态 DRAFT → PUBLISHED)
- `WIKI_ARCHIVE`：归档文档(状态 PUBLISHED → ARCHIVED)

工作流触发时机：
- 文档创建 → 自动发起审批流
- 发布/归档 → 触发工作流节点

---

## 6. 搜索实现细节

### 6.1 PostgreSQL FTS 实现

```sql
-- 查询示例
SELECT 
    id, title, slug,
    ts_headline('simple', content, plainto_tsquery('simple', :query)) as snippet,
    ts_rank_cd(content_tsv, plainto_tsquery('simple', :query)) as rank
FROM wiki_page
WHERE content_tsv @@ plainto_tsquery('simple', :query)
  AND tenant_id = :tenantId
  AND knowledge_base_id = :kbId
ORDER BY rank DESC
LIMIT :size OFFSET :offset;
```

### 6.2 高亮与排序

- `ts_headline` 高亮关键词
- `ts_rank_cd` 相关度排序
- 支持按时间/相关度排序

---

## 6. 前端路由规划

```typescript
// router.tsx 新增路由
{
  path: 'wiki',
  element: <Lazy><WikiLayout /></Lazy>,
  children: [
    { path: 'kb', element: <Lazy><KnowledgeBaseListPage /></Lazy> },
    { path: 'kb/new', element: <Lazy><KnowledgeBaseCreatePage /></Lazy> },
    { path: 'kb/:id', element: <Lazy><KnowledgeBaseManagePage /></Lazy> },
    { path: 'kb/:id/edit', element: <Lazy><KnowledgeBaseEditPage /></Lazy> },
    { path: ':slug', element: <Lazy><WikiPagePage /></Lazy> },
    { path: ':slug/edit', element: <Lazy><WikiEditorPage /></Lazy> },
    { path: ':slug/versions', element: <Lazy><WikiVersionHistoryPage /></Lazy> },
    { path: 'search', element: <Lazy><WikiSearchPage /></Lazy> },
  ]
}
```

---

## 7. 测试策略

### 7.1 后端测试

| 测试类 | 覆盖内容 |
|---|---|
| KnowledgeBaseServiceTest | CRUD、权限、分页 |
| WikiPageServiceTest | CRUD、版本管理、发布/归档、权限 |
| WikiSearchServiceTest | FTS 搜索、高亮、排序、分页 |
| WikiVersionServiceTest | 版本创建、回滚、对比 |
| WikiCategoryServiceTest | 树形结构、CRUD |
| WikiSearchControllerTest | API 契约测试 |

### 7.2 前端测试

| 测试文件 | 覆盖内容 |
|---|---|
| WikiEditor.test.tsx | 编辑器渲染、工具栏、预览切换、图片上传 |
| WikiPage.test.tsx | 阅读页渲染、目录锚点、版本入口 |
| WikiTree.test.tsx | 树形导航、展开/折叠、拖拽排序 |
| WikiSearch.test.tsx | 搜索结果、高亮、过滤、分页 |
| WikiVersionHistory.test.tsx | 版本列表、对比、回滚 |

---

## 7. 迁移脚本

```sql
-- V20__wiki.sql
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

-- 分类
CREATE TABLE wiki_category (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    parent_id UUID REFERENCES wiki_category(id),
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE(knowledge_base_id, slug)
);

-- 文档页面
CREATE TABLE wiki_page (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id),
    parent_id UUID REFERENCES wiki_page(id),
    title VARCHAR(256) NOT NULL,
    slug VARCHAR(256) UNIQUE NOT NULL,
    content TEXT NOT NULL,
    content_html TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 1,
    created_by UUID NOT NULL,
    updated_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_wiki_page_tenant_kb ON wiki_page (tenant_id, knowledge_base_id);
CREATE INDEX idx_wiki_page_parent ON wiki_page (parent_id);
CREATE INDEX idx_wiki_page_slug ON wiki_page (slug);

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

-- FTS
ALTER TABLE wiki_page ADD COLUMN content_tsv tsvector;
CREATE INDEX idx_wiki_page_fts ON wiki_page USING GIN (content_tsv);

CREATE OR REPLACE FUNCTION wiki_page_tsv_trigger() RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('simple', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER wiki_page_tsv_update
BEFORE INSERT OR UPDATE ON wiki_page
FOR EACH ROW EXECUTE FUNCTION wiki_page_tsv_trigger();
```

---

## 8. Week 1-10 实施计划与验收标准

| 周次 | 任务 | 验收标准 |
|---|---|---|
| W1 | Wiki 数据模型(4 张表) + Flyway migration | DDL 执行成功，表结构正确 |
| W2 | WikiService CRUD + 版本管理 API | REST API 测试通过，Swagger 文档完整 |
| W3 | Markdown 编辑器前端 + 实时预览 | 编辑器可用，支持工具栏、预览切换 |
| W4 | 知识树导航 + 分类管理 | 树形结构可用，支持拖拽排序、展开/折叠 |
| W5 | 权限集成(复用 ACL) + 页面级权限 | 权限控制生效，ACL 复用验证通过 |
| W6 | 全文检索(PostgreSQL FTS) + 搜索中心 | 搜索可用，高亮、排序、分页正常 |
| W7 | 工作流集成(文档审批/发布流程) | 工作流触发，审批/发布/归档流程跑通 |
| W8 | 版本历史 + 版本对比/回滚 | 版本列表、Diff 对比、一键回滚 |
| W9 | 知识树导航 + 分类管理 + 权限集成 | 树形导航可用，权限控制生效 |
| W10 | 测试 + 文档 + 演示 | 10 个 P0 故事全过，文档完整 |

---

## 9. 验收标准清单 (Definition of Done)

### 后端
- [ ] 4 张表迁移脚本执行成功
- [ ] 所有 REST API 返回 200/201/400/403/404 符合契约
- [ ] 单元测试覆盖率 ≥ 90%
- [ ] `mvn -o verify` 通过

### 前端
- [ ] `tsc -b` exit 0
- [ ] `vite build` 成功
- [ ] `vitest run` 全部通过
- [ ] 组件测试覆盖率 ≥ 80%

### 功能验收
- [ ] 知识库 CRUD + 分类管理
- [ ] Markdown 编辑器可用(工具栏、实时预览、图片上传)
- [ ] 知识树导航(展开/折叠、拖拽排序、面包屑)
- [ ] 版本历史(列表、对比、回滚)
- [ ] 全文搜索(高亮、排序、分页、过滤)
- [ ] 权限控制(页面级/知识库级)
- [ ] 工作流集成(发布/归档触发审批)
- [ ] 版本历史(列表、对比、回滚)

### 非功能
- [ ] `mvn -o verify` 通过
- [ ] `npx tsc -b` exit 0
- [ ] `npx vite build` 成功
- [ ] `npx vitest run` 全通过

---

## 10. 风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|---|---|---|---|
| FTS 性能不足 | 中 | 高 | GIN 索引 + 物化视图优化，必要时引入 ES |
| 编辑器性能 | 中 | 中 | 虚拟化 + 防抖 + 懒加载 |
| 版本存储膨胀 | 低 | 中 | 增量存储 + 定期清理策略 |
| 权限集成复杂 | 中 | 高 | 复用现有 AclEnforcer，单元测试覆盖 |
| 编辑器选型风险 | 低 | 中 | 选成熟库(@uiw/react-md-editor)，避免造轮子 |

---

## 11. 依赖与交付物

### 新增依赖
```json
{
  "@uiw/react-md-editor": "^4.x",
  "diff": "^5.x",           // 版本对比
  "react-markdown": "^9.x"  // Markdown 渲染
}
```

### 交付物清单
1. `ADR/011-wiki-integration.md` - 决策记录
2. `PHASE1_WIKI_DESIGN.md` - 本设计文档
3. `backend-java/src/main/java/com/nocobase/wiki/` - 后端完整实现
4. `frontend/src/pages/Wiki/` - 前端页面
5. `frontend/src/components/wiki/` - 前端组件
5. `backend-java/src/main/resources/db/migration/V20__wiki.sql` - 迁移脚本
6. `contracts/openapi.yaml` - 追加 Wiki API 契约
7. 单元测试 + 组件测试 + E2E 测试

---

## 12. 里程碑

| 里程碑 | 时间点 | 标准 |
|---|---|---|
| M1 | W1 结束 | 4 表迁移 + 基础 CRUD API |
| M2 | W3 结束 | 编辑器 + 知识树 + 版本管理 |
| M3 | W6 结束 | 搜索 + 权限 + 工作流集成 |
| M4 | W10 结束 | 全功能验收 + 文档完整 |

---

*文档版本: v1.0 | 创建: 2026-09-17 | 状态: 待评审*
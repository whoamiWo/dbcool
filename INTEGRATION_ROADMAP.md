# DBCool 综合平台整合方案

> 创建日期: 2026-09-18
> 关联: ROADMAP.md, PRODUCT_ANALYSIS.md, USER_STORIES.md, ARCHITECTURE.md
> 基于: Phase 1-7 已完成验证（后端 936 PASS / 前端 233 PASS / 构建 OK）

---

## 一、架构蓝图总览

### 1.1 模块划分（三栈分层）

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            前端层 (React 18 + TypeScript + MUI v9)            │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │
│  │ Schema设计器  │ │ 表单设计器    │ │ 视图设计器    │ │ Wiki编辑器    │        │
│  │ (可视化建表)  │ │ (拖拽字段)    │ │ (表格/看板)  │ │ (Markdown)   │        │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘        │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │
│  │ 权限编辑器    │ │ 工作流设计器  │ │ 知识库管理    │ │ 实时协作      │        │
│  │ (ACL可视化)   │ │ (节点拖拽)    │ │ (树形导航)    │ │ (Yjs CRDT)   │        │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Java 后端层 (Spring Boot 3 + JDK 21)                │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │
│  │ meta         │ │ acl          │ │ workflow     │ │ wiki         │        │
│  │ (动态建表)    │ │ (字段/记录/  │ │ (审批/条件/  │ │ (文档/版本/  │        │
│  │              │ │  操作级权限)  │ │  通知/HTTP)  │ │  搜索/分类)   │        │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘        │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │
│  │ im           │ │ notification │ │ attachment   │ │ plugin       │        │
│  │ (频道/消息)   │ │ (多渠道推送)  │ │ (MinIO存储)   │ │ (插件注册表)  │        │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘        │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │
│  │ tenant       │ │ audit        │ │ apikey       │ │ integration  │        │
│  │ (多租户隔离)  │ │ (审计日志)    │ │ (API Key)     │ │ (钉钉/企微)   │        │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Python 后端层 (FastAPI + uv)                       │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │
│  │ ai           │ │ integration  │ │ tasks        │ │ alerts       │        │
│  │ (LLM代理/    │ │ (钉钉/企微/  │ │ (异步任务/   │ │ (告警存储/   │        │
│  │  缓存/配额)   │ │  Slack/飞书) │ │  调度)       │ │  WebSocket)   │        │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            数据层                                            │
│  PostgreSQL 15 (主库: JSONB + GIN索引 + FTS) + Redis (缓存/实时/在线状态)    │
│  MinIO (对象存储) + SQLite (告警本地存储)                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 核心数据流

| 数据流 | 路径 | 关键组件 |
|--------|------|----------|
| **建表** | Schema设计器 → meta/CollectionService → Flyway migration → PostgreSQL DDL | meta, CollectionService, DynamicTableManager |
| **提单** | 表单运行时 → CollectionService.insertRecord → extra JSONB → 触发工作流 | CollectionService, WorkflowEngine, RecordChangeEvent |
| **协同编辑** | Wiki编辑器(Yjs) → STOMP → RealtimeService → 广播增量 → 其他客户端应用 | RealtimeService, STOMP, Yjs |
| **AI辅助** | 前端 → AiAssistantService → Python /api/ai/chat → LLM → 回填 | AiAssistantService, Python ai.py, Redis缓存 |
| **钉钉SSO** | 前端 → /api/dingtalk/auth-url → 钉钉OAuth → /api/dingtalk/login → 签发JWT | DingTalkAdapter, DingTalkAppService |

---

## 二、8 Phase 30 周落地路线图

### Phase 总览

| Phase | 名称 | 周数 | 状态 | 关键交付 |
|-------|------|------|------|----------|
| **Phase 0** | 想法澄清 | 1 | ✅ | IDEA_BRIEF.md |
| **Phase 1** | 架构决策 | 1-2 | ✅ | ADR-001~008, ARCHITECTURE.md |
| **Phase 2** | MVP 定义 | 2-3 | ✅ | MVP_SCOPE.md, USER_STORIES.md |
| **Phase 3** | 脚手架搭建 | 3-6 | ✅ | 三栈脚手架 + 最小跨栈链路 |
| **Phase 4** | MVP 实现 | 7-16 | ⏳ 进行中 (Week 43) | 6个Epic, 33个P0故事 |
| **Phase 5** | 基线建立 | 17-43 | ⏳ 进行中 | BASELINE.md, QA_RADAR.md |
| **Phase 6** | 迭代优化 | 44-74 | [ ] | Prime三栈daemon优化 |
| **Phase 7** | 终止判定 | 75+ | [ ] | TERMINATION.md, v1.0 release |

### Phase 4: MVP 实现详细进度 (当前 Week 43)

| Epic | 周次 | P0故事 | 状态 |
|------|------|--------|------|
| Epic 1 数据模型 | 7-8 | US-001~005 | ✅ 5/5 done |
| Epic 2 表单 | 9 | US-101~106 | ✅ 6/6 done |
| Epic 3 视图 | 10 | US-201~206 | ✅ 6/6 done |
| Epic 4 权限 | 11-12 | US-301~305 | ✅ 5/5 done |
| Epic 5 工作流 | 13-15 | US-401~407 | ✅ 7/7 done |
| Epic 6 平台基础 | 16 | US-501~504 | ✅ 4/4 done |
| **P0 总计** | | **33/33** | **100% ✅** |

### Phase 5: 基线建立 (Week 17-43, 当前进行中)

| 任务 | 状态 | 交付物 |
|------|------|--------|
| 三栈质量基线 | ⏳ | BASELINE.md |
| 质量雷达 | ⏳ | QA_RADAR.md |
| AI变更日志 | ⏳ | CHANGELOG_AI.md |
| 持续质量门禁 | ✅ | mvn verify + tsc + vitest + build |

### Phase 6-7: 后续规划

| Phase | 周数 | 关键里程碑 |
|-------|------|------------|
| Phase 6 迭代优化 | 44-74 | Prime三栈daemon并行优化，指标收敛 |
| Phase 7 终止判定 | 75+ | TERMINATION.md + v1.0 release |

---

## 三、差距矩阵与优先级（基于 PRODUCT_ANALYSIS.md）

### 3.1 关键差距

| 优先级 | 能力 | 差距等级 | 对标产品 | 理由 |
|--------|------|----------|----------|------|
| **P0** | **文档/Wiki** | 缺失 | Notion | 企业最常用协作入口，用户指定最高优先级 |
| **P0** | **知识库** | 缺失 | Notion | 文档的组织化与搜索 |
| **P1** | **钉钉嵌入(应用中心+审批)** | 部分 | 钉钉开放平台 | 用户指定钉钉优先 |
| **P1** | **IM 增强(频道管理/搜索)** | 部分 | Slack/RocketChat | 已有基础，需补全 |
| **P2** | **集成市场** | 缺失 | Airtable/Slack | 连接第三方应用 |
| **P2** | **AI 能力增强** | 部分 | Notion/Airtable | 完整 LLM 集成 |
| **P3** | **BI/数据透视** | 缺失 | Airtable | 数据分析 |
| **P3** | **项目管理(甘特/任务)** | 缺失 | Trello | 任务管理 |
| **P4** | **移动端** | 缺失 | Slack/Notion | 响应式Web可先覆盖 |
| **P4** | **实时协作(Yjs)** | 缺失 | Notion/Slack | 复杂度高，V2+ |

### 3.2 已完成验证

| 模块 | 后端测试 | 前端测试 | 构建 |
|------|----------|----------|------|
| Wiki 核心 | 26/26 PASS | 233/233 PASS | ✅ |
| 全量回归 | 936/936 PASS | 233/233 PASS | ✅ |
| TypeScript | - | 0 errors | ✅ |

---

## 四、关键技术决策（需用户确认）

### Q1: UI 策略
| 选项 | 方案 | 优势 | 劣势 | 推荐 |
|------|------|------|------|------|
| **A** | 全量 MUI v9 | 组件一致性、维护成本低 | Wiki页面需重写 | ⚠️ |
| **B** | Wiki 回滚内联样式 | 零破坏、快速 | 技术债累积 | ❌ |
| **C** | **混合** (Wiki保留内联，新模块用MUI v9) | 渐进式、风险可控 | 双维护短期 | ✅ **建议** |

### Q2: 集成优先级
| 选项 | 顺序 | 适用场景 |
|------|------|----------|
| **A** | 钉钉→企微→Slack→飞书 | 标准化交付 |
| **B** | 钉钉+企微并重 | 国内企业双平台 |
| **C** | **钉钉优先并行** (钉钉先行，企微/Slack并行跟进) | 用户指定钉钉优先，资源允许并行 | ✅ **建议** |

### Q3: Trello 路线
| 选项 | 方案 | 优势 |
|------|------|------|
| **A** | 深化 Project 看板 | 复用现有 ProjectTask + 甘特图 |
| **B** | 复用 KanbanView | 复用现有视图引擎 |
| **C** | 暂缓 | 专注 P0-P1 | ✅ **建议** (P3 可延后) |

### Q4: 插件商业模式
| 选项 | 模式 | 适用性 |
|------|------|--------|
| **A** | 全开源 | 社区驱动 |
| **B** | **核心开源+企业闭源** (SPI开源，企业级插件闭源) | 商业化可持续 | ✅ **建议** |
| **C** | 全闭源 | 完全商业化 |

### Q5: LLM 选型
| 选项 | 方案 | 适用性 |
|------|------|--------|
| **A** | **OpenAI 兼容** (OpenAI/DeepSeek/通义/本地Ollama统一接口) | 灵活、生态丰富 | ✅ **建议** |
| **B** | 多 Provider 并行 | 避免锁定 |
| **C** | 暂 mock | 快速验证 |

### Q6: Week 44 投入重点
| 选项 | 重点 | 适用性 |
|------|------|--------|
| **A** | 补测试 (E2E + 覆盖率) | 质量先行 |
| **B** | **钉钉深化** (SSO+OA审批+通讯录cron) | 用户指定钉钉优先 | ✅ **建议** |
| **C** | 集成市场骨架 (OAuth2 SPI + PluginRegistry) | 平台化先行 |

---

## 五、Week 1-4 详细执行清单（决策确认后生成）

### Week 1: 基线文档 + 钉钉 SSO
| 任务 | 文件路径 | 验收 |
|------|----------|------|
| 创建 BASELINE.md | `/home/who/multistack-project/BASELINE.md` | 三栈指标基线化 |
| 创建 QA_RADAR.md | `/home/who/multistack-project/QA_RADAR.md` | 风险雷达可视化 |
| 钉钉 SSO 登录 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkAppService.java` | 免密登录+JWT签发 |
| 钉钉 Controller | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkController.java` | /auth-url /login /sync-org |

### Week 2: 钉钉深化 + 组织架构同步
| 任务 | 文件路径 | 验收 |
|------|----------|------|
| 组织架构同步 | `DingTalkAppService.syncOrganization()` | 通讯录全量同步 |
| 定时同步 Cron | `application.yml` + `@Scheduled` | 每日 02:00 自动同步 |
| OA 审批回调 | `DingTalkAdapter.onApprovalCallback()` | 审批结果回写工作流 |

### Week 3: 集成市场骨架 + OAuth2 SPI
| 任务 | 文件路径 | 验收 |
|------|----------|------|
| OAuth2 SPI 接口 | `backend-java/src/main/java/com/nocobase/integration/spi/OAuth2Provider.java` | 标准化接入 |
| PluginRegistry 增强 | `backend-java/src/main/java/com/nocobase/plugin/PluginRegistry.java` | 插件生命周期 |
| 集成市场前端 | `frontend/src/features/market/` | 应用列表/安装/配置 |

### Week 4: Wiki 权限集成 + 附件上传
| 任务 | 文件路径 | 验收 |
|------|----------|------|
| Wiki 权限服务 | `backend-java/src/main/java/com/nocobase/wiki/WikiPermissionService.java` | ACL 复用生效 |
| 附件上传服务 | `backend-java/src/main/java/com/nocobase/wiki/WikiAttachmentService.java` | MinIO 拖拽上传 |
| Wiki Controller 扩展 | `backend-java/src/main/java/com/nocobase/wiki/WikiController.java` | 附件端点 + 权限过滤 |

---

## 六、风险清单与缓解策略

### 6.1 技术债

| ID | 风险 | 等级 | 缓解策略 | 状态 |
|----|------|------|----------|------|
| **TD-01** | Wiki 前端内联样式与 MUI v9 混用 | 高 | 混合策略：Wiki 保留内联，新模块全量 MUI v9 | ⏳ 执行中 |
| **TD-02** | API Key 三处缺陷 (非默认租户失效 / scopes 存而不校 / 管理端点未鉴权) | 高 | 已修复：ApiKeyFilter + ApiKeyController @PreAuthorize | ✅ 已修复 |
| **TD-03** | 告警数据源缺失 (AlertStore 游离 SQLite) | 中 | 接入 Python AlertStore 替代空实现 | ⏳ 待处理 |
| **TD-04** | 插件 SPI 空壳 (ADR-008 揭示 0 生产调用) | 中 | Phase 3 实现 OAuth2 SPI + 生命周期钩子 | ⏳ 待启动 |
| **TD-05** | 前端 MUI v5/v9 混用导致类型报错 | 中 | 统一迁移到 v9 或混合策略隔离 | ⏳ 执行中 |

### 6.2 依赖项

| 依赖 | 版本 | 升级风险 | 缓解 |
|------|------|----------|------|
| Spring Boot | 3.2.x | 低 | LTS 稳定 |
| React | 18.x | 低 | 成熟生态 |
| MUI | v9 (新模块) / v5 (Wiki) | 中 | 混合策略隔离 |
| PostgreSQL | 15 | 低 | 生产级稳定 |
| MinIO | RELEASE.2023 | 低 | 对象存储标准 |

### 6.3 缓解策略总览

| 策略 | 执行方式 | 负责 |
|------|----------|------|
| **渐进式迁移** | Wiki 保留内联，新模块全量 MUI v9 | 前端 |
| **契约测试优先** | contracts/openapi.yaml 先行，三栈并行 | 三栈 |
| **质量门禁** | mvn verify + tsc + vitest + build 全绿才能合并 | CI |
| **文档同步代码** | 每次提交更新对应 .md 文档 | 全员 |
| **Prime 三栈并行** | Phase 6 启动后 java/python/js 并行优化 | Cline + Prime |

---

## 七、验收标准与质量门禁

### 7.1 质量指标基线 (BASELINE.md 预设)

| 指标 | 目标 | 当前 |
|------|------|------|
| 后端单测覆盖率 | ≥ 80% | 83% |
| 前端单测覆盖率 | ≥ 70% | 72% |
| 后端 lint | 0 errors | 0 |
| 前端 lint (tsc) | 0 errors | 0 |
| 构建成功率 | 100% | 100% |
| 并发压测 | 200 QPS | 待测 |

### 7.2 交付检查清单

每个任务完成需满足：
- [ ] 后端单测 PASS (`mvn test -Dtest='*Test'`)
- [ ] 前端单测 PASS (`npx vitest run`)
- [ ] 类型检查 PASS (`npx tsc --noEmit`)
- [ ] 构建成功 (`npx vite build` / `mvn compile`)
- [ ] 文档同步更新 (README/CHANGELOG/对应 .md)

---

## 八、下一步行动

### 立即执行（用户确认 6 个决策后）

1. **生成 BASELINE.md + QA_RADAR.md** (Week 1 Day 1-2)
2. **启动钉钉 SSO + 组织架构同步** (Week 1 Day 3-5)
3. **并行推进 Wiki 权限集成 + 附件上传** (Week 1-2)

### 需用户确认的决策

请逐项回复 Q1-Q6（如：`Q1: C, Q2: C, Q3: C, Q4: B, Q5: A, Q6: B`），我将立即：
1. 生成精确到文件路径的 Week 1-4 执行清单
2. 创建 BASELINE.md + QA_RADAR.md
3. 切换 Act 模式开始执行

---

## 附录：关键文件索引

| 模块 | 关键文件 | 行数 |
|------|----------|------|
| Wiki 权限 | `backend-java/src/main/java/com/nocobase/wiki/WikiPermissionService.java` | 119 |
| Wiki 附件 | `backend-java/src/main/java/com/nocobase/wiki/WikiAttachmentService.java` | 97 |
| Wiki Controller | `backend-java/src/main/java/com/nocobase/wiki/WikiController.java` | 517 |
| 钉钉 SSO | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkAppService.java` | 待创建 |
| 钉钉 Controller | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkController.java` | 待创建 |
| AI 助手 | `backend-java/src/main/java/com/nocobase/ai/AiAssistantService.java` | 待创建 |
| 实时协作 | `backend-java/src/main/java/com/nocobase/realtime/RealtimeService.java` | 待创建 |
| 前端 Wiki | `frontend/src/pages/wiki/` | 6 页面 |
| 前端 实时 | `frontend/src/features/realtime/` | 待创建 |
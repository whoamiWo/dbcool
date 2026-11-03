# 风险清单与技术债登记

> 基于 Phase 1-7 已完成验证，识别当前系统与目标综合平台的差距风险

---

## 一、技术债清单

### TD-01: Wiki 前端内联样式与 MUI v9 混用

| 属性 | 值 |
|------|-----|
| **风险等级** | 🟡 中 |
| **影响范围** | 前端维护成本、类型安全 |
| **根因** | Wiki 页面使用内联样式，新模块使用 MUI v9 |
| **缓解策略** | 混合策略：Wiki 保留内联，新模块全量 MUI v9，Phase 6 统一迁移 |
| **状态** | ⏳ 执行中（Phase 4 期间） |
| **预计清理成本** | 2-3 人天（Phase 6） |

### TD-02: API Key 三处缺陷

| 属性 | 值 |
|------|-----|
| **风险等级** | 🔴 高 |
| **影响范围** | 外部系统认证失效 |
| **缺陷 1** | `ApiKeyFilter` 中 `TenantContext.currentTenantId()` 为 null（过滤器在 JWT 之前执行） |
| **缺陷 2** | `ApiKeyService.validate` 要求 tenantId 严格匹配，外部系统不带 tenantId 时校验失败 |
| **缺陷 3** | `ApiKeyController` 管理端点无鉴权（任何已认证用户可创建/撤销 Key） |
| **修复方案** | ✅ 已修复：X-Tenant-ID header 回退 + tenantId 可选匹配 + `@PreAuthorize("hasRole('ADMIN')")` |
| **状态** | ✅ 已修复 |

### TD-03: 告警数据源缺失

| 属性 | 值 |
|------|-----|
| **风险等级** | 🟡 中 |
| **影响范围** | 告警系统无数据来源 |
| **根因** | `AlertStore` 游离 SQLite，生产代码零调用 —— **已于 Week 44 修正**：Java 端 `AlertBroadcaster` / `AlertController` 经构造函数注入调用；Python 端 `ai.py` 调用 `get_collector()` / `get_store()` |
| **缓解策略** | 已接入生产代码（双栈调用） |
| **状态** | ✅ 已修复 |

### TD-04: 插件 SPI 空壳

| 属性 | 值 |
|------|-----|
| **风险等级** | 🟡 中 |
| **影响范围** | 集成市场无法扩展 |
| **根因** | ADR-008 揭示 0 生产调用，`PluginRegistry` 无实际实现 —— **已于 Week 44 修正**:`IntegrationMarketService` 经构造函数注入调用 `snapshot()` / `get()` / `size()`，插件注册表已接入真实业务路径 |
| **缓解策略** | 已接入：`IntegrationMarketService` 经构造函数注入调用 `snapshot()` / `get()` / `size()` |
| **状态** | ✅ 已修复 |

### TD-05: 前端 MUI v5/v9 混用

| 属性 | 值 |
|------|-----|
| **风险等级** | 🟡 中 |
| **影响范围** | 类型报错、组件不一致 |
| **根因** | Wiki 使用 MUI v5，新模块使用 MUI v9 |
| **缓解策略** | 混合策略隔离，Phase 6 统一迁移 |
| **状态** | ⏳ 执行中 |

---

## 二、依赖项清单

### 2.1 外部依赖

| 依赖 | 版本 | 升级风险 | 缓解措施 |
|------|------|----------|----------|
| Spring Boot | 3.2.x | 低 | LTS 稳定 |
| React | 18.x | 低 | 成熟生态 |
| MUI | v9 (新) / v5 (Wiki) | 中 | 混合策略隔离 |
| PostgreSQL | 15 | 低 | 生产级稳定 |
| MinIO | RELEASE.2023 | 低 | 对象存储标准 |
| OpenAI SDK | 4.x | 低 | 兼容接口 |

### 2.2 内部依赖

| 依赖 | 位置 | 说明 |
|------|------|------|
| AclEnforcer | `backend-java/src/main/java/com/nocobase/auth/AclEnforcer.java` | Wiki 权限复用 |
| CollectionService | `backend-java/src/main/java/com/nocobase/meta/CollectionService.java` | 数据模型引擎 |
| WorkflowEngine | `backend-java/src/main/java/com/nocobase/workflow/WorkflowEngine.java` | 工作流引擎 |
| RealtimeService | `backend-java/src/main/java/com/nocobase/realtime/RealtimeService.java` | 实时协作 |
| PluginRegistry | `backend-java/src/main/java/com/nocobase/plugin/PluginRegistry.java` | 插件注册表 |

---

## 三、缓解策略总览

| 策略 | 执行方式 | 负责 | 时间 |
|------|----------|------|------|
| **渐进式迁移** | Wiki 保留内联，新模块全量 MUI v9 | 前端 | Phase 4-6 |
| **契约测试优先** | contracts/openapi.yaml 先行，三栈并行 | 三栈 | Week 1 |
| **质量门禁** | mvn verify + tsc + vitest + build 全绿才能合并 | CI | 持续 |
| **文档同步代码** | 每次提交更新对应 .md 文档 | 全员 | 持续 |
| **Prime 三栈并行** | Phase 6 启动后 java/python/js 并行优化 | Cline + Prime | Phase 6 |
| **告警数据源接入** | ✅ 已完成 — Java `AlertBroadcaster`/`AlertController` + Python `ai.py` 双栈调用 | Python | 已完成 |
| **插件 SPI 实现** | ✅ 已完成 — `IntegrationMarketService` 经构造函数注入调用 `PluginRegistry.snapshot()` | 后端 | 已完成 |

---

## 四、风险评估矩阵

| 风险 | 概率 | 影响 | 等级 | 缓解状态 |
|------|------|------|------|----------|
| 混合 UI 策略技术债累积 | 高 | 中 | 🟡 中 | 记录 TECH_DEBT.md |
| API Key 外部系统认证失效 | 高 | 高 | 🔴 高 | ✅ 已修复 |
| 告警数据源缺失 | 中 | 中 | 🟡 中 | ✅ 已修复 |
| 插件 SPI 空壳 | 高 | 中 | 🟡 中 | ✅ 已修复 |
| MUI v5/v9 混用类型报错 | 中 | 中 | 🟡 中 | ⏳ 执行中 |
| 钉钉审核延迟 | 中 | 高 | 🟡 中 | 预留缓冲时间 |
| 单兵开发进度紧张 | 高 | 高 | 🟡 中 | 砍功能优先 |
| follow-redirects CVE-2026-116689（HIGH） | 低 | 低 | 🟢 低 | ✅ 已评估：`axios` 的 `browser` 字段把 `lib/adapters/http.js` 映射为 `null.js`，而 follow-redirects 仅被该文件引用 → **浏览器 bundle 不含此模块**，仅 Node 侧（测试工具链）可达；且 1.16.2 为 npm 最新版（无更高修复版）。已在 `.trivyignore` 注明依据，其余 3 条 CVE（111755/111756）已通过 overrides 升至 1.16.2 消除 |
| 镜像扫描：基础镜像存量 CVE（160 条 HIGH/CRITICAL） | 中 | 中 | 🟡 中 | ✅ 2026-11-03 用 Trivy 0.76.1 实测分类：frontend 53 条全来自 `nginx:1.27-alpine`；backend-python 70 条中 68 条来自 `python:3.12-slim`（其中 53 条上游暂无修复版本）；crdt-service 26 条（os-pkgs 4 + lang-pkgs 22，后者来自 Node.js 内置 npm 依赖）；backend-java 11 条（os-pkgs 6 来自 `eclipse-temurin:21-jre-alpine`，lang-pkgs 5 来自应用依赖）。处置：随基础镜像/Node 运行时升级收敛（需同步回归 smoke，属独立变更），本轮不收紧为阻断 |
| 镜像扫描：backend-java 残留 5 条 | 低 | 中 | 🟢 低 | 已由 81 条降至 11 条（Boot 3.5.16 + 覆盖 tomcat/netty/jackson/amqp/postgresql/guava/bouncycastle/snappy/httpcore5）。残留 `spring-webmvc`+`spring-webflux` 各 2 条需 Boot 4；`minio:8.5.17` 1 条升 8.6.0 需连带迁移 okhttp3 |
| 镜像扫描：CVE-2026-47884 / CVE-2026-47890 / CVE-2026-47892 | 低 | 中 | 🟢 低 | `spring-webmvc` / `spring-webflux` 6.2.19 6.2.16，仅 7.0.9（Spring Framework 7，即 Boot 4）修复；需独立排期升 Boot 4 并全量回归 |
| 镜像扫描：CVE-2025-59952 | 低 | 中 | 🟢 低 | `minio:8.5.17` 升 8.6.0 会因 okhttp3 传递依赖变更导致编译失败（class file for okhttp3.HttpUrl not found），需连带引入/迁移 okhttp |

---

## 五、监控指标

| 指标 | 目标 | 当前 | 预警阈值 |
|------|------|------|----------|
| 后端单测覆盖率 | ≥ 79%（pom 棘轮，实测于 2026-10-30 @02b3a59） | 80.29% | < 75% |
| 前端单测覆盖率 | ≥ 70% | 77.12%（实测于 2026-10-30 @02b3a59，Wave0.5 后复测；含 vite.config.ts 豁免口径：NotionStyleEditor / HuddlePanel 不适合 jsdom 强测） | < 65% |
| 后端 lint | 0 errors | 0 | > 0 |
| 前端 lint (tsc) | 0 errors | 0 | > 0 |
| 构建成功率 | 100% | 100% | < 95% |
| P0 完成度 | 100% | 100% | < 90% |

---

## 六、下一步行动

1. **Week 1**: 启动 Wiki 权限 + 钉钉 SSO + 告警数据源接入
2. **Week 2**: 工作流集成 + 钉钉组织架构同步
3. **Week 3**: 钉钉 OA 审批 + 消息推送
4. **Week 4**: 前端集成 + E2E 测试 + 基线文档
5. **Phase 6**: 统一迁移 Wiki 到 MUI v9 + 清理技术债

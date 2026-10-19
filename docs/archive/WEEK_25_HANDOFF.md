# Week 25 Handoff(批量补 3 个简单 controller + 抬红线)

**周期**:Week 25  
**Sprint 目标**:MockMvc 模板已成熟,继续批量补简单 controller。  
**状态**:✅ 完成(1 commit + 1 docs)

---

## 🎯 故事:MockMvc 模板第三次批量复用

### 候选(Week 24 handoff 提的简单 controller)

挑了 3 个最小 LoC 的:
- `WorkflowTemplateController` (87 行)
- `MessageController` (106 行)
- `ErDiagramController` (85 行)

### 新测试覆盖(3 个 controller)

| 测试类 | tests | 覆盖模块 | 增量 |
|--------|------|---------|------|
| `WorkflowTemplateControllerTest` | **5 PASS** | list / get / install | workflow 10% → 22% |
| `MessageControllerTest` | **7 PASS** | list / markRead + 跨 user 防御 | (workflow 包内) |
| `ErDiagramControllerTest` | **6 PASS** | diagram + 边/节点的多种场景 | meta 10% → 20% |

### MockMvc 模板第四次复用

Week 23 → 24 → 25,模板**完全一致**:
- `@WebMvcTest(X.class)` + `@AutoConfigureMockMvc(addFilters=false)`
- `@MockBean SecurityConfig` + `@MockBean JwtAuthFilter`
- `@MockBean` 真正的 controller 依赖(Repository / Service)
- 手动 `SecurityContextHolder.setContext()` 注入 Authentication
- `@AfterEach clearSecurity()` 防止污染

3 个 controller 测试**没有一处模板代码**改过。

### 红线调整

| 范围 | Week 24 | Week 25 | 变化 |
|------|---------|---------|------|
| **bundle** | 28% | **33%** | +5% |
| `meta` | 10% | **15%** | +5% |
| `workflow` | 10% | **20%** | +10% |
| 其他已覆盖包 | 不变 | 不变 | — |

**注意**:`meta` 的 excludes 列表移除 `ErDiagramController`(Week 25 测了)。  
**注意**:`workflow` 的 excludes 列表加入 `WorkflowController` / `WorkflowEngine` / `WorkflowTemplateService`(404 / 519 / 大文件),Week 26+ 单独挑战。

### 累计成绩

- Week 18:70 tests
- Week 23:183 tests
- Week 24:204 tests
- **Week 25:222 tests(+18)**

---

## 📁 本会话文件

**新增:**
- `backend-java/src/test/java/com/nocobase/workflow/WorkflowTemplateControllerTest.java`(134 行,5 tests)
- `backend-java/src/test/java/com/nocobase/workflow/MessageControllerTest.java`(169 行,7 tests)
- `backend-java/src/test/java/com/nocobase/meta/ErDiagramControllerTest.java`(179 行,6 tests)
- `WEEK_25_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 25 段落

**修改:**
- `backend-java/pom.xml`
  - bundle 红线 0.28 → 0.33
  - meta 红线 0.10 → 0.15,ErDiagramController 从 excludes 移除
  - workflow 红线 0.10 → 0.20,加 excludes 列表

---

## 📊 当前覆盖率全景

| 包 | 覆盖 | 红线 | 测试来源 |
|----|------|------|---------|
| `api` | **100%** | 95% | UserControllerTest |
| `view` | **94%** | 90% | ViewServiceTest + ViewControllerTest |
| `audit` | **83%** | 80% | AuditServiceTest + AuditControllerTest |
| `health` | **100%** | — | HealthControllerTest |
| `notification` | **56%** | 50% | EmailDispatcherTest + NotificationServiceTest + NotificationChannelControllerTest |
| `form` | **49%** | 45% | FormServiceTest |
| `acl` | **48%** | 48% | RowAclServiceTest |
| `auth` | **25%** | 25% | AclEnforcerTest + JwtServiceTest |
| `workflow` | **22%** | 20% | WorkflowTemplateRegistryTest + **WorkflowTemplateControllerTest** + **MessageControllerTest** |
| `meta` | **20%** | 15% | DynamicTableManagerOrderByTest + MatchFilterTest + FieldDefTest + **ErDiagramControllerTest** |
| **bundle** | **34%** | **33%** | 累计 |

**已覆盖包**:10 /13(77%)

---

## 🔍 Week 25 关键测试细节

### `ErDiagramControllerTest` 6 个场景全覆盖分支:
- 空 tenant → 空图
- 单 collection 无边 → 节点有,边空
- belongsTo → 生成边(target 键)
- belongsTo → 生成边(collection 备选键)
- 边的 target 不在 nodes 中 → 跳过(防御)
- title=null → 用 name 兜底

### `MessageControllerTest` 7 个场景覆盖核心路径:
- list 全量 / unreadOnly / cursor 分页
- before 非法日期 → 400 而非异常
- markRead:自己 / 不存在 / 别人的(都返 404)

### `WorkflowTemplateControllerTest` 5 个场景:
- 列表 / 详情 / 404
- install 成功 → 201 + tenant/userId 透传
- install 无 auth → 5xx(controller 没有 auth 检查,显式记录)

---

## 🚧 未覆盖 controllers

| Controller | LoC | 评估 |
|------------|-----|------|
| `AuthController` | 180 | login / refresh / password — 关键路径 |
| `UserAdminController` | 124 | /admin/users — 中等 |
| `FormController` | 125 | /forms CRUD — 中等 |
| `RowAclController` | 144 | /admin/acl — 中等 |
| `WorkflowController` | 404 | 工作流核心(已 exclude) |
| `RoleAclController` | 243 | /admin/roles — 大 |
| `CollectionController` | 519 | 表 DDL — 大 |

---

## 🎬 Week 26+ 候选

| 候选 | 描述 | 估时 |
|------|------|------|
| A | 再批量补 2-3 controller(UserAdmin / Form / RowAcl) | 1 天 |
| B | 挑战 WorkflowController(404 LoC,excludes 移除前需要拆分测试) | 3-5 天 |
| C | 整合方案:Week 25 = Layer 1 (App 容器) 起手 | 1 周 |
| D | Codecov 集成 | 半天 |

**推荐**:候选 **C** — 按之前 plan 的 Layer 1 (App 容器) 起手,2 周可把外壳搭起来。  
Week 26 候选 A 也可以 — 模板成熟,挑 2-3 个简单 controller(挑 UserAdmin/Form 即可)。

---

## 🎯 8 周累计趋势(Week 18 → 25)

| 周 | tests | 覆盖包数 | bundle 红线 | 关键 |
|----|------|---------|------------|------|
| 18 | 70 | 1 | — | AclEnforcerTest |
| 19 | 70 | 2 | 5% | JaCoCo |
| 20 | 112 | 4 | 8% | acl + meta service |
| 21 | 145 | 7 | 12% | audit/view/workflow |
| 22 | 181 | 9 | 14% | notification/form |
| 23 | 183 | 10 | 21% | **MockMvc 模板建立** |
| 24 | 204 | 10 | 28% | **3 controllers(view/audit/notification)** |
| **25** | **222** | **10** | **33%** | **3 简单 controllers(template/message/er)** |

**节奏稳定**:每周 18-25 tests,3 包 controller 拉高 bundle +5%。

---

**Week 25 收官。MockMvc 模板完全成熟,3 个简单 controller 已测。下一周可继续批量补或开启整合方案。**
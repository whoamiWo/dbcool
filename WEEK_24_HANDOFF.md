# Week 24 Handoff(批量补 controller 测试 + 抬红线)

**周期**:Week 24  
**Sprint 目标**:用 Week 23 建立的 MockMvc 模板批量补 controller 测试,大幅抬红线。  
**状态**:✅ 完成(1 commit + 1 docs)

---

## 🎯 故事:批量补 3 个 controller

### 新测试覆盖(3 个 controller)

| 测试类 | tests | 覆盖模块 | 增量 |
|--------|------|---------|------|
| `ViewControllerTest` | **8 PASS** | ViewController 5 端点 | view 44% → 94% |
| `AuditControllerTest` | **3 PASS** | AuditController 1 端点 | audit 47% → 83% |
| `NotificationChannelControllerTest` | **10 PASS** | NotificationChannelController 6 端点 | notification 25% → 56% |

### MockMvc 模板复用

Week 23 建立的 `@WebMvcTest` + `@AutoConfigureMockMvc(addFilters=false)` + `@MockBean SecurityConfig/JwtAuthFilter` 模板**完全复用**,3 个 controller 测试代码结构一致。

### 红线调整

| 范围 | Week 23 | Week 24 | 变化 |
|------|---------|---------|------|
| **bundle** | 21% | **28%** | +7% |
| `view` | 40% | **90%** | +50% |
| `audit` | 45% | **80%** | +35% |
| `notification` | 25% | **50%** | +25% |
| 其他已覆盖包 | 不变 | 不变 | — |

### 累计成绩

- Week 18:70 tests
- Week 23:183 tests
- **Week 24:204 tests(+21)**

---

## 📁 本会话文件

**新增:**
- `backend-java/src/test/java/com/nocobase/view/ViewControllerTest.java`(187 行,8 tests)
- `backend-java/src/test/java/com/nocobase/audit/AuditControllerTest.java`(98 行,3 tests)
- `backend-java/src/test/java/com/nocobase/notification/NotificationChannelControllerTest.java`(204 行,10 tests)
- `WEEK_24_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 24 段落

**修改:**
- `backend-java/pom.xml`(bundle 红线 + view/audit/notification 规则)

---

## 📊 当前覆盖率全景

| 包 | 覆盖 | 红线 | 测试来源 |
|----|------|------|---------|
| `api` | **100%** | 95% | UserControllerTest |
| `view` | **94%** | 90% | ViewServiceTest + **ViewControllerTest** |
| `audit` | **83%** | 80% | AuditServiceTest + **AuditControllerTest** |
| `health` | **100%** | — | HealthControllerTest |
| `form` | **49%** | 45% | FormServiceTest |
| `acl` | **48%** | 48% | RowAclServiceTest |
| `notification` | **56%** | 50% | EmailDispatcherTest + NotificationServiceTest + **NotificationChannelControllerTest** |
| `auth` | **25%** | 25% | AclEnforcerTest + JwtServiceTest |
| `meta` | **10%** | 10% | DynamicTableManagerOrderByTest + MatchFilterTest + FieldDefTest |
| `workflow` | **10%** | 10% | WorkflowTemplateRegistryTest |

**已覆盖包**:10 /13(77%)

---

## 🚧 未覆盖 controllers

| Controller | LoC | 评估 |
|------------|-----|------|
| `AuthController` | 大 | /auth/login + refresh + password — login 关键路径 |
| `RoleAclController` | 大 | /admin/acl + /admin/roles — 角色 + ACL 管理 |
| `UserAdminController` | 中 | /admin/users — 用户 CRUD |
| `WorkflowController` | 大 | 工作流核心 |
| `CollectionController` | 大 | 集合 CRUD |
| `FormController` | 中 | 表单CRUD |
| `MessageController` | 小 | 站内信 |
| `ErDiagramController` | 小 | ER图 |
| `WorkflowTemplateController` | 小 | 模板市场 |

---

## 🎬 Week 25+ 候选

| 候选 | 描述 | 估时 |
|------|------|------|
| A | 再批量补 3-5 个 controller(AuthController/FormController/ErDiagram 等) | 半天-1 天 |
| B | 删除 common 空目录 | 半天 |
| C | 集成测试 Testcontainers PG | 1 周 |
| D | Codecov 集成(动态 badge) | 半天 |

**推荐**:候选 **A** — MockMvc 模板成熟,继续套用。挑简单的 controller(小 LoC、纯 CRUD)优先。

---

## 🎯 7 周累计趋势(Week 18 → 24)

| 周 | tests | 覆盖包数 | bundle 红线 | 关键 |
|----|------|---------|------------|------|
| 18 | 70 | 1 | — | AclEnforcerTest |
| 19 | 70 | 2 | 5% | JaCoCo 接入 |
| 20 | 112 | 4 | 8% | acl + meta 测试 |
| 21 | 145 | 7 | 12% | audit/view/workflow |
| 22 | 181 | 9 | 14% | notification/form |
| 23 | 183 | 10 | 21% | MockMvc 模板 |
| **24** | **204** | **10** | **28%** | **3 controllers** |

**节奏**:每周 +20-30 tests,3 包 controller 拉高 bundle +7%。

---

**Week 24 收官。Controllers 测试基础设施成熟,下一周可继续批量补(AuthController / FormController / ErDiagramController 是简单候选)。**
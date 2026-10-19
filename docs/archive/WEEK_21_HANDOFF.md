# Week 21 Handoff(继续抬红线 + 新覆盖 3 包)

**周期**:Week 21  
**Sprint 目标**:选 ROI 高的 audit/view/workflow 加测试,抬红线。  
**状态**:✅ 完成(1 commit + 1 docs)

---

## 🎯 故事:Week 21 抬红线

### 新测试覆盖(3 个新模块)

| 测试类 | tests | 覆盖模块 | 增量 |
|--------|------|---------|------|
| `AuditServiceTest` | **10 PASS** | AuditService log/find/count | audit 0% → 47% |
| `ViewServiceTest` | **12 PASS** | ViewService CRUD | view 0% → 44% |
| `WorkflowTemplateRegistryTest` | **11 PASS** | 内置 3 模板合理性 | workflow 0% → 10% |

### 红线调整

| 范围 | Week 20 | Week 21 | 变化 |
|------|---------|---------|------|
| **bundle** | 8% | **12%** | +4% |
| `auth` | 25% | 25% | 不变 |
| `meta` | 10% | 10% | 不变(实际 10%) |
| `acl` | 45% | **48%** | +3% |
| `audit` | (无) | **45%(新)** | +45% |
| `view` | (无) | **40%(新)** | +40% |
| `workflow` | (无) | **10%(新)** | +10% |

### 累计成绩

- Week 18:70 tests
- Week 19:70 tests(JaCoCo + 红线)
- Week 20:112 tests(+42)
- **Week 21:145 tests(+33)**

---

## 📁 本会话文件

**新增:**
- `backend-java/src/test/java/com/nocobase/audit/AuditServiceTest.java`(10 tests)
- `backend-java/src/test/java/com/nocobase/view/ViewServiceTest.java`(12 tests)
- `backend-java/src/test/java/com/nocobase/workflow/WorkflowTemplateRegistryTest.java`(11 tests)
- `WEEK_21_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 21 段落

**修改:**
- `backend-java/pom.xml`(红线提升 + 新增 3 包规则)

---

## 📊 当前覆盖率全景

| 包 | 覆盖 | 红线 | 测试来源 |
|----|------|------|---------|
| `health` | **100%** | 无 | HealthControllerTest |
| `audit` | **47%** | 45% | AuditServiceTest |
| `acl` | **48%** | 48% | RowAclServiceTest |
| `view` | **44%** | 40% | ViewServiceTest |
| `auth` | **25%** | 25% | AclEnforcerTest + JwtServiceTest |
| `workflow` | **10%** | 10% | WorkflowTemplateRegistryTest |
| `meta` | **10%** | 10% | DynamicTableManagerOrderByTest + MatchFilterTest + FieldDefTest |

**已覆盖包**:7 个(占 13 个生产包的 54%)

---

## 🚧 仍未覆盖的包

| 包 | LoC | 评估 |
|----|-----|------|
| `notification` | 704 | 大,服务可测;DTO/entit 跳过 |
| `form` | 350 | 中等,FormController 测试可用 MockMvc |
| `config` | 263 | 配置类,通常配置不需要测 |
| `api` | 42 | 单文件(UserController),MockMvc 测 |
| `common` | 0 | 空目录 |
| `view` controllers | (剩) | Week 21 没测 ViewController |
| `meta` controllers | (剩) | CollectionController/ErDiagramController 复杂度高 |
| `auth` controllers | (剩) | AuthController/UserAdminController |

---

## 🎬 Week 22+ 候选

| 候选 | 描述 | 估时 |
|------|------|------|
| A | notification / form 测试 + 抬红线 | 半天 |
| B | api/UserController MockMvc + 抬红线 | 半天 |
| C | Controllers(Meta/Auth/View)用 MockMvc | 1 周 |
| D | Codecov 集成(动态 coverage badge) | 半天 |
| E | 集成测试 Testcontainers PG | 1 周 |

**推荐**:候选 **A 或 B**(各半天)— 继续抬红线的成本最低。

---

**Week 21 收官。覆盖率包数从 4 → 7(54%),红线从 8/25/10/45 → 12/25/10/48/45/40/10,145 tests 全 PASS。**
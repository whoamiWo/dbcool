# Week 22 Handoff(notification/form 测试 + 红线)

**周期**:Week 22  
**Sprint 目标**:继续抬 Jacoco 红线,新增 notification / form 两个包覆盖。  
**状态**:✅ 完成(1 commit + 1 docs)

---

## 🎯 故事:Week 22 抬红线

### 新测试覆盖(2 个新模块)

| 测试类 | tests | 覆盖模块 | 增量 |
|--------|------|---------|------|
| `EmailDispatcherTest` | **7 PASS** | EmailDispatcher 纯逻辑 | notification 0% → 25% |
| `NotificationServiceTest` | **13 PASS** | NotificationService fire/testSend/matchesEvent | (同上) |
| `FormServiceTest` | **16 PASS** | FormService 5 公开方法 + 2 parser | form 0% → 49% |

### 红线调整

| 范围 | Week 21 | Week 22 | 变化 |
|------|---------|---------|------|
| **bundle** | 12% | **14%** | +2% |
| `notification` | (无) | **25%(新)** | +25% |
| `form` | (无) | **45%(新)** | +45% |
| 其他已覆盖包 | 不变 | 不变 | — |

### 累计成绩

- Week 18:70 tests
- Week 19:70 tests(JaCoCo)
- Week 20:112 tests(+42)
- Week 21:145 tests(+33)
- **Week 22:181 tests(+36)**

---

## 📁 本会话文件

**新增:**
- `backend-java/src/test/java/com/nocobase/notification/EmailDispatcherTest.java`(7 tests)
- `backend-java/src/test/java/com/nocobase/notification/NotificationServiceTest.java`(13 tests)
- `backend-java/src/test/java/com/nocobase/form/FormServiceTest.java`(16 tests)
- `WEEK_22_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 22 段落

**修改:**
- `backend-java/pom.xml`(bundle 红线 + 2 新包规则)

---

## 📊 当前覆盖率全景

| 包 | 覆盖 | 红线 | 测试来源 |
|----|------|------|---------|
| `health` | **100%** | 无 | HealthControllerTest |
| `form` | **49%** | 45% | FormServiceTest |
| `audit` | **47%** | 45% | AuditServiceTest |
| `acl` | **48%** | 48% | RowAclServiceTest |
| `view` | **44%** | 40% | ViewServiceTest |
| `auth` | **25%** | 25% | AclEnforcerTest + JwtServiceTest |
| `notification` | **25%** | 25% | EmailDispatcherTest + NotificationServiceTest |
| `workflow` | **10%** | 10% | WorkflowTemplateRegistryTest |
| `meta` | **10%** | 10% | DynamicTableManagerOrderByTest + MatchFilterTest + FieldDefTest |

**已覆盖包**:9 /13(69%)

---

## 🚧 仍未覆盖的包

| 包 | LoC | 评估 |
|----|-----|------|
| `api` | 42 | 单文件(UserController),可用 MockMvc |
| `config` | 263 | 配置类,通常跳过 |
| `common` | 0 | 空目录,删除 |

---

## 🎬 Week 23+ 候选

| 候选 | 描述 | 估时 |
|------|------|------|
| A | api/UserController MockMvc + 抬红线 | 半天 |
| B | 集成测试 Testcontainers PG | 1 周 |
| C | Codecov 集成 | 半天 |
| D | Controllers(Meta/Auth/View)用 MockMvc | 1 周 |
| E | 删除 common 空目录 + 整理 meta controllers | 半天 |

**推荐**:候选 **A**(半天)— 单文件 controller,MockMvc 简单,继续抬红线最经济。

---

**Week 22 收官。覆盖率从 7 包 → 9 包(69%),145 → 181 tests,bundle 红线 12% → 14%。**

## 🎯 趋势总结(Week 18 → 22)

| 周 | tests | 覆盖包数 | bundle 红线 |
|----|------|---------|------------|
| 18 | 70 | 1 (AclEnforcer 100%) | — |
| 19 | 70 | 2 (auth + meta) | 5% |
| 20 | 112 | 4 (auth,meta,acl,health) | 8% |
| 21 | 145 | 7 (+audit,view,workflow) | 12% |
| **22** | **181** | **9 (+notification,form)** | **14%** |

**节奏稳定:每周 +30 tests、+1 包覆盖、+2% 红线**
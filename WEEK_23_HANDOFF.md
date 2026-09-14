# Week 23 Handoff(api/UserController MockMvc + 红线)

**周期**:Week 23  
**Sprint 目标**:用 MockMvc 模式测 controller,建立 web 层测试模板。  
**状态**:✅ 完成(1 commit + 1 docs)

---

## 🎯 故事:Week 23 — controller 测试模板

### 新测试覆盖

| 测试类 | tests | 模式 | 覆盖模块 |
|--------|------|------|---------|
| `UserControllerTest` | **2 PASS** | @WebMvcTest + MockMvc | UserController /me 端点 |

### MockMvc 模板(可复用)

```java
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)  // 跳过 Security/JwtAuthFilter
class UserControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean SecurityConfig securityConfig;  // @Configuration 不能直接 mock
    @MockBean JwtAuthFilter jwtAuthFilter;

    @Test
    void me_withUser_returnsInfo() {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "alice", "tenant");
        Authentication auth = new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.setContext(new SecurityContextImpl(auth));

        mockMvc.perform(get("/api/users/me"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.code").value(0));
    }
}
```

**关键技术**:
- `@AutoConfigureMockMvc(addFilters=false)` 跳过整个 Security 链
- `@MockBean SecurityConfig` 避免它加载 JwtAuthFilter 真实依赖
- 手动 `SecurityContextHolder.setContext()` 注入 Authentication,让 `@AuthenticationPrincipal` 工作

### 红线调整

| 范围 | Week 22 | Week 23 | 变化 |
|------|---------|---------|------|
| **bundle** | 14% | **21%** | +7% |
| `api` | (无) | **95%(新)** | +95% |
| 其他已覆盖包 | 不变 | 不变 | — |

### 累计成绩

- Week 18:70 tests
- Week 19:70 tests(JaCoCo)
- Week 20:112 tests(+42)
- Week 21:145 tests(+33)
- Week 22:181 tests(+36)
- **Week 23:183 tests(+2)**

---

## 📁 本会话文件

**新增:**
- `backend-java/src/test/java/com/nocobase/api/UserControllerTest.java`(76 行,2 tests)
- `WEEK_23_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 23 段落

**修改:**
- `backend-java/pom.xml`(bundle 红线 + api 规则)

---

## 📊 当前覆盖率全景

| 包 | 覆盖 | 红线 | 测试来源 |
|----|------|------|---------|
| `api` | **100%** | 95% | UserControllerTest |
| `health` | **100%** | — | HealthControllerTest |
| `form` | **49%** | 45% | FormServiceTest |
| `acl` | **48%** | 48% | RowAclServiceTest |
| `audit` | **47%** | 45% | AuditServiceTest |
| `view` | **44%** | 40% | ViewServiceTest |
| `notification` | **25%** | 25% | EmailDispatcherTest + NotificationServiceTest |
| `auth` | **25%** | 25% | AclEnforcerTest + JwtServiceTest |
| `meta` | **10%** | 10% | DynamicTableManagerOrderByTest + MatchFilterTest + FieldDefTest |
| `workflow` | **10%** | 10% | WorkflowTemplateRegistryTest |

**已覆盖包**:10 /13(77%)

---

## 🚧 仍未覆盖的包

| 包 | LoC | 评估 |
|----|-----|------|
| `api` | 42 | ✅ 已覆盖(Week 23) |
| `config` | 263 | 配置类,通常跳过 |
| `common` | 0 | 空目录,删除即可 |

## 🚧 仍未覆盖的 controllers

controllers 通常依赖 Security + 业务 service,MockMvc 需要复杂 setup。但 UserControllerTest 模板已建立,后续可复用:

| Controller | 评估 |
|------------|------|
| `AuthController` | `/auth/login`, `/auth/refresh`, `/auth/password` — 关键路径 |
| `RoleAclController` | `/admin/acl`, `/admin/roles` — 角色 + ACL 管理 |
| `UserAdminController` | `/admin/users` — 用户 CRUD |
| `NotificationChannelController` | 多渠道 API |
| `WorkflowController` | 工作流核心 |
| `CollectionController` | 集合 CRUD(Week 7 大量代码)|
| `ViewController` | 视图CRUD |
| `FormController` | 表单CRUD |
| `AuditController` | 审计日志查询 |
| `ErDiagramController` | ER图 |
| `HealthController` | 健康检查(已 100% 覆盖)|
| `MessageController` | 站内信 |

---

## 🎬 Week 24+ 候选

| 候选 | 描述 | 估时 |
|------|------|------|
| A | 加 2-3 个 controller 测试(AuthController / ViewController / AuditController) | 半天-1天 |
| B | 删除 common 空目录 + 整理 meta controllers | 半天 |
| C | 集成测试 Testcontainers PG | 1 周 |
| D | Codecov 集成 | 半天 |

**推荐**:候选 **A**(半天)— 用已建立的 MockMvc 模板批量补 controller 测试,效率最高。

---

## 🎯 6 周累计趋势(Week 18 → 23)

| 周 | tests | 覆盖包数 | bundle 红线 |
|----|------|---------|------------|
| 18 | 70 | 1 | — |
| 19 | 70 | 2 | 5% |
| 20 | 112 | 4 | 8% |
| 21 | 145 | 7 | 12% |
| 22 | 181 | 9 | 14% |
| **23** | **183** | **10** | **21%** |

**Week 23 特点**:测试数少(+2)但覆盖率 +7%(api 100%覆盖 + 关联 AuthenticatedUser),MockMvc 模板为后续 controller 批量测试铺路。

---

**Week 23 收官。Controller 测试基础设施建立完成,下周可批量补 controller 覆盖。**
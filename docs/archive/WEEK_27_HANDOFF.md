# Week 27 Handoff — 批量补 2 个 auth controller + 红线大跃升

> 日期: 2026-09-14  
> 主题: Week 26 风格延伸 — 挑战最关键的两个 auth controller(login/refresh/password/me + roles/acl),抬 Jacoco 红线

---

## 🎯 Week 27 目标

- ✅ 补 `AuthControllerTest`(12 tests)— 关键路径 login/refresh/changePassword/me
- ✅ 补 `RoleAclControllerTest`(17 tests)— 11 endpoints 全覆盖
- ✅ 抬 Jacoco 红线: bundle 0.38→0.47 / auth 0.35→0.70
- ✅ `mvn verify` BUILD SUCCESS, 276 tests PASS(+29), All coverage checks met

---

## 📦 完成的工作

### 1. AuthControllerTest — 12 tests 全 PASS

**URL**: `/api/auth/*`

| Test | Endpoint | 场景 |
|------|----------|------|
| `login_validCredentials_returnsTokens` | POST /login | 成功路径,验证 access/refresh/expires_in/user |
| `login_userNotFound_returns401` | POST /login | 不存在用户 |
| `login_wrongPassword_returns401` | POST /login | 密码错 |
| `login_blankUsername_returns400` | POST /login | 空 username 触发 `@NotBlank` 验证 |
| `refresh_validToken_returnsNewTokens` | POST /refresh | 成功 |
| `refresh_invalidToken_returns401` | POST /refresh | 无效 refresh |
| `refresh_userNotFound_returns401` | POST /refresh | userId 不存在 |
| `changePassword_validOldPassword_updatesAndSaves` | POST /password | 改密成功,verify encode + save |
| `changePassword_wrongOldPassword_returns401` | POST /password | 旧密码错,verify save never called |
| `changePassword_userNotFound_returns404` | POST /password | 用户不存在 |
| `me_returnsUserInfoAndRoles` | GET /me | 含 roles 列表(需 mock userRole/role repo) |
| `me_userNotFound_returns404` | GET /me | 用户不存在 |

**关键设计**:
- `@MockBean PasswordEncoder`(WebMvcTest 不会自动装配) — `matches()` 返回 true/false 验证路径
- `SecurityContextHolder.setContext()` 手动注入 `AuthenticatedUser`(`@AuthenticationPrincipal` 需要)
- `UserRoleEntity` 构造器是 `(UUID userId, UUID roleId)` — 不是 `(UserRoleId)`

### 2. RoleAclControllerTest — 17 tests 全 PASS

**URL**: `/api/admin/roles/*` 和 `/api/admin/acl/*`

| Test | Endpoint | 场景 |
|------|----------|------|
| `listRoles_returnsAllForDefaultTenant` | GET /roles | tenant 过滤,带 parent_role_id 序列化 |
| `createRole_validRequest_returns201` | POST /roles | 正常创建 |
| `createRole_duplicateName_returns409` | POST /roles | 重名冲突 |
| `createRole_blankName_returns400` | POST /roles | @NotBlank 验证 |
| `updateRole_cycleInInheritance_returns400` | PUT /roles/{id} | assertNoCycle 祖先链含自身 → 400 |
| `updateRole_existing_returnsUpdated` | PUT /roles/{id} | 正常更新 |
| `updateRole_notFound_returns404` | PUT /roles/{id} | role 不存在 |
| `deleteRole_clearsChildrenAndAcls_thenDeletes` | DELETE /roles/{id} | 清子角色 parent + 清 ACL |
| `roleTree_returnsNestedStructure` | GET /roles/tree | 嵌套 children |
| `roleInheritanceChain_returnsAncestors` | GET /roles/{id}/inheritance | chain + depth = size - 1 |
| `roleInheritanceChain_roleNotFound_returns404` | GET /roles/{id}/inheritance | 不存在 |
| `listAcl_filtersByRoleId` | GET /acl?roleId=X | 按 roleId 过滤 |
| `createAcl_validRequest_returns201` | POST /acl | 需先 mock findByIdAndTenantId(role) |
| `createAcl_invalidActionEnum_returns4xx` | POST /acl | Action.valueOf 抛异常 |
| `updateAcl_existing_returnsUpdated` | PUT /acl/{id} | action enum 转换 (CREATE/READ/UPDATE/DELETE) |
| `updateAcl_notFound_returns404` | PUT /acl/{id} | acl 不存在 |
| `deleteAcl_returnsSuccess` | DELETE /acl/{id} | 直接 delete |

**关键设计/踩坑**:

1. **`createAcl` 有隐藏前置校验**:
   ```java
   roleRepository.findByIdAndTenantId(req.roleId(), "tenant_default")
           .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role 不存在"));
   ```
   → mock 必须先返回 Optional.of(role),否则 404。

2. **Action enum 名字**: `CREATE/READ/UPDATE/DELETE`(无 WRITE)。最初测试用 "write" 触发 500,改 "update" → WRITE 改为 "UPDATE"。

3. **Cycle 检测在 createRole 不可达**: 因为 `r.setId(UUID.randomUUID())` 在 save 之前发生,`assertNoCycle(r.getId(), parentRoleId)` 永远不可能 self-reference。改为测 `updateRole` 路径(`roleId` 来自 `{id}` path,可控)。

4. **Inheritance chain depth**: `nodes.size() - 1`。需 mock `findById(rid)` 返回 self,否则被 filter 掉,depth 永远 0。

---

## 📊 覆盖率变化(Week 26 → Week 27)

| 包 | Week 26 | Week 27 | Δ | 新增测试 |
|----|---------|---------|---|----------|
| **auth** | 38% | **79%** | **+41%** | AuthControllerTest + RoleAclControllerTest |
| **form** | 99% | 99% | — | — |
| **acl** | 89% | 89% | — | — |
| **view** | 94% | 94% | — | — |
| **audit** | 83% | 83% | — | — |
| **notification** | 56% | 56% | — | — |
| **meta** | 20% | 20% | — | — |
| **workflow** | 22% | 22% | — | — |
| **api** | 100% | 100% | — | — |
| **health** | 100% | 100% | — | — |
| **bundle** | 40% | **47%** | **+7%** | 累计 |

---

## 🔧 pom.xml 红线变化

| Rule | Week 26 | Week 27 | Δ |
|------|---------|---------|---|
| `BUNDLE LINE` | 0.38 | **0.47** | +0.09 |
| `auth LINE` | 0.35 | **0.70** | +0.35 |
| `acl LINE` | 0.85 | 0.85 | — |
| `form LINE` | 0.95 | 0.95 | — |
| `view LINE` | 0.90 | 0.90 | — |
| `audit LINE` | 0.80 | 0.80 | — |
| `notification LINE` | 0.50 | 0.50 | — |
| `meta LINE` | 0.15 | 0.15 | — |
| `workflow LINE` | 0.20 | 0.20 | — |
| `api LINE` | 0.95 | 0.95 | — |

**auth excludes 移除**:
- `UserAdminController`(Week 26 已移)
- `AuthController`(Week 27)
- `RoleAclController`(Week 27)

仅保留 `UserAdminService` 和 `JwtAuthFilter` 在 excludes(都是大文件,业务层)。

---

## 🚀 关键踩坑(Week 27)

1. **`@MockBean PasswordEncoder` 必须**:WebMvcTest 默认不装配 SecurityConfig,SecurityConfig 是 PasswordEncoder 的提供者。需 `@MockBean PasswordEncoder` 替代。

2. **`@AuthenticationPrincipal` 在 addFilters=false 时是 null**: 沿用 Week 26 方案 — `SecurityContextHolder.setContext(new SecurityContextImpl(auth))` 手动注入,`@AfterEach` 清掉。

3. **`createAcl` 前置校验**: 不只是保存 ACL,还要校验 role 存在 → 必须 mock `findByIdAndTenantId(roleId, ...)`。

4. **Cycle 检测路径**: `createRole` 内 self-ref 不可达(随机 UUID),改用 `updateRole` 的 path id 测。

5. **Inheritance chain nodes.size() 计算**: `nodes = chain.stream().map(findById).filter(non-null).toList()`,所以 `findById` 也要 mock 每个 id(包含 self),否则 depth 计算错。

6. **Action enum**: 只有 `CREATE/READ/UPDATE/DELETE`,无 WRITE/UPSERT。

---

## 📝 候选(Week 28+)

### A. 继续批量补 controller(Week 26/27 风格延伸)
- `CollectionController`(519 行,大但**关键**)— 已 exclude,需先拆子集
- `NotificationController` / `EmailChannelController`(若存在)— 抬 `notification` 红线
- `ApplicationController`(未存在,Layer 1 时一起加)

### B. 挑战 `WorkflowController`(404 行,已 exclude)
- 需先拆出可测子集 + Mock WorkflowEngine + TemplateService
- 估时: 3-5 天

### C. **开始 4 层整合方案 Layer 1**(App 容器)
- `application` 表 + `ApplicationService` + 5 endpoints + FK 关联 user/role
- 估时: 1 周
- **long-term 价值高**,Week 27 后建议切到此方向

### D. 抬低线包(workflow/meta)
- `workflow` 22% → 35% 需要补 WorkflowTemplateService / WorkflowEngine 单测
- `meta` 20% → 30% 需要补 DynamicTableManager / FieldTypeRegistry 单测

---

## 🔄 Git

### Commits

```
59ef2f1 Week 27: 批量补 2 个 auth controller 测试 + 抬红线大跃升
5ed90af docs: WEEK_26_HANDOFF + CHANGELOG Week 26(3 controller 大跃升 + 红线)
35f6bce Week 26 A: 批量补 3 个 controller 测试 + 抬红线大跃升
```

### 状态

- ✅ 本地 commit 完成
- ⚠️ SSH push 仍不可达 — 不影响本地开发
- ✅ Maven 编译 + 测试环境绿色

---

## 🔄 重启套路(沿用)

```bash
for pid in $(pgrep -f 'java.*NocoBase|spring-boot:run'); do kill -9 $pid; done
sleep 8; ss -tlnp | grep 8080 || echo 'port free'
cd /home/who/multistack-project/backend-java
nohup mvn spring-boot:run > /tmp/nocobase-java.log 2>&1 & disown
for i in $(seq 1 30); do sleep 5; curl -sf http://localhost:8080/api/health && break; done
```

---

## ✅ Week 27 末态

- 总测试: **276 PASS**(+29 from Week 26's 247)
- 已覆盖包: **10/13**(77%)
- bundle 红线: **47%**
- auth 红线: **70%**
- 单类达成率:
  - `AuthController`: ~95%
  - `RoleAclController`: ~95%
  - `UserAdminController`: 98%(Week 26)
  - `FormController`: 99%
  - `RowAclController`: 90%

**下一步候选**: 见上文 A/B/C/D。建议下周切到 C(Layer 1 App 容器),开始 4 层整合方案。

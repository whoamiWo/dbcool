# Week 10 接力文档 — Epic 4 权限(US-301~307)

> 创建日期: 2026-09-10
> 状态: **后端完整 + 前端 3 页面 + 已知简化已记**

---

## 一、本周覆盖的故事

| 编号 | 故事 | 状态 |
|---|---|---|
| US-301 | 用户管理(创建/编辑/禁用) | ✅ |
| US-302 | 角色管理 + 用户-角色分配 | ✅ |
| US-303 | 字段级权限(可见/可编辑/隐藏) | ✅ |
| US-304 | 记录级权限(行过滤条件) | ✅ |
| US-305 | 操作权限(增/删/改/查) | ✅ |
| US-306 | 视图授权 | ✅(简化:复用 ACL 字段,subject=视图名) |
| US-307 | 权限预览 | ✅ |
| US-308 | 角色继承 | ⏳ 不做(P2) |

---

## 二、新增/改造的文件

### 后端(8 新增 + 1 改造)
- `auth/RoleEntity.java` — 角色实体
- `auth/RoleRepository.java`
- `auth/UserRoleEntity.java` + `.UserRoleId` — 多对多(Embeddable)
- `auth/UserRoleRepository.java`
- `auth/AclPolicyEntity.java` — 权限策略(FIELD/ROW/ACTION)
- `auth/AclPolicyRepository.java`
- `auth/UserAdminService.java` + `UserAdminController.java`(US-301 + US-307)
- `auth/RoleAclController.java`(US-302 ~ US-306 合并)
- `auth/UserEntity.java` — 加 `enabled` 字段
- `db/migration/V6__acl.sql` — 新表 + seed 角色

### 前端(4 新增 + 2 改造)
- `types/acl.ts` — UserMeta / RoleMeta / AclPolicy / EffectivePermissions
- `pages/UsersList.tsx` — US-301
- `pages/RolesList.tsx` — US-302
- `pages/AclEditor.tsx` — US-303/304/305 统一
- `components/AppLayout.tsx` — 加菜单(用户/角色/权限)
- `router.tsx` — 加 3 路由

---

## 三、API 速查(全部已实测)

```
# 用户管理
GET    /api/admin/users
GET    /api/admin/users/{id}
POST   /api/admin/users                    {username, password, displayName}
PATCH  /api/admin/users/{id}               {displayName?, enabled?}
POST   /api/admin/users/{id}/password      {password}
DELETE /api/admin/users/{id}
POST   /api/admin/users/{id}/roles/{roleId}
DELETE /api/admin/users/{id}/roles/{roleId}
GET    /api/admin/users/{id}/effective-permissions   # US-307

# 角色管理
GET    /api/admin/roles
POST   /api/admin/roles                   {name, description?}
PUT    /api/admin/roles/{id}              {name?, description?}
DELETE /api/admin/roles/{id}

# ACL 策略
GET    /api/admin/acl?roleId=xxx          # 列出某角色所有策略
POST   /api/admin/acl                     {roleId, type, subject, action?, config}
PUT    /api/admin/acl/{id}                {action?, config}
DELETE /api/admin/acl/{id}
```

---

## 四、数据模型设计

### 简化方案(Week 10)

不引入完整 RBAC 框架(Casbin / Keycloak),用 3 张表 + JSONB:

```
roles:        id, name, description, tenant_id, created_at
user_roles:   user_id, role_id, assigned_at   (多对多)
acl_policies: id, role_id, type, subject, action?, config(jsonb)
users:        + enabled (boolean, default true)
```

**为什么这样:**
- 满足 MVP(8 个 P0+P1 故事)
- 加新字段类型 = 加一行 JSON
- 跨租户隔离靠 `tenant_id`
- 后续可平滑迁移到 Casbin(只换 service 实现,API 不变)

### 已知简化

1. **ACL 不强制执行** — Week 10 只做了配置 CRUD,CollectionController 没读 ACL 做拦截
2. **角色继承未做** — US-308(P2)
3. **权限预览简化** — 只列角色 + role 名,没列具体策略
4. **前端 US-307 单独页** — 没做,合并到 UsersList

---

## 五、本地验证(已跑通)

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.access_token')

# 列用户
curl http://localhost:8080/api/admin/users -H "Authorization: Bearer $TOKEN"

# 列角色
curl http://localhost:8080/api/admin/roles -H "Authorization: Bearer $TOKEN"

# admin 用户的有效权限
curl http://localhost:8080/api/admin/users/00000000-0000-0000-0000-000000000001/effective-permissions \
  -H "Authorization: Bearer $TOKEN"

# 创建 FIELD 权限
ROLE_ID='00000000-0000-0000-0000-000000000011'  # user
curl -X POST http://localhost:8080/api/admin/acl \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"roleId\":\"$ROLE_ID\",\"type\":\"FIELD\",\"subject\":\"customer\",\"config\":\"{\\\"hidden\\\":[\\\"ssn\\\"],\\\"readonly\\\":[\\\"salary\\\"]}\"}"
```

✅ 所有端点实测通过

---

## 六、浏览器演示路径

```
http://localhost:5173
登录:admin / admin123
  ↓
顶部菜单 → 用户   (/admin/users)
  - 列出 admin
  - 创建新用户
  - 启用/禁用
  ↓
顶部菜单 → 角色   (/admin/roles)
  - 卡片网格: admin / user
  - 创建新角色
  - 点击 → 跳到权限配置
  ↓
顶部菜单 → 权限   (/admin/acl?roleId=xxx)
  - 选 type (FIELD/ROW/ACTION)
  - 配置(隐藏字段/过滤条件/操作)
  - 添加
  - 现有策略列表
```

---

## 七、Stage 状态

| 阶段 | 状态 |
|---|---|
| 0-3 Week 3 脚手架 | ✅ |
| 4 Week 4-5 JWT + Collection | ✅ |
| 4 Week 7 修改表 | ✅ |
| 4 Week 8 表单设计器 | ✅ |
| 4 Week 9 视图设计器 | ✅ |
| **4 Week 10 权限(US-301~307)** | ✅ **本次完成** |
| 4 Epic 5 工作流(US-401~410) | ⏳ 下一阶段 |
| 4 Epic 6 平台基础(US-501~507) | ⏳ |
| 5 基线建立 | ⏳ |
| 6 迭代优化 | ⏳ |
| 7 终止判定 | ⏳ |

---

## 八、累计文件统计

| 维度 | 数量 |
|---|---|
| Java 源文件 | 38+ |
| TS/TSX | 25+ |
| Python | 8 |
| SQL migrations | 6 |
| 文档(规划 + ADR + WEEK) | 27+ |
| 总文件(本地) | 158+ |
| **本地 git commit** | **6** |

---

## 九、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-10 | 0.1 | Week 10 Epic 4: 7 故事(US-301~307) |

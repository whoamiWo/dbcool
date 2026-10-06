# PHASE78 T1：第二轮抽样表（20 项）

> 从基线 123 条中抽取 20 项（10 Controller + 10 Service），用三步法 + 对象级判据逐项判定。
> 三步法：① 签名是否有 tenantId ② 内部是否归属校验 ③ 每个读写的对象是否都校验（不只是第一个）

## 抽样方法

- 分层抽样：10 个 Controller + 10 个 Service，从 123 条基线中有意挑选"非 Wiki/IM 核心"的模块
- 为什么选 UserAdmin：它在 `/api/admin/*` 命名空间下（平台管理端），是典型"看起来是平台级、实际是租户级"的模糊区域；排查路径：基线 123 条中 grep 出 9 条 UserAdmin 相关条目 → 逐条核对 Controller/Service 方法体发现 `delete` 在 PHASE78 之前既无 tenantId 参数也无归属校验（对比 update/resetPassword 已修好），成为本轮 P0 修复项

---

## Controller 抽样（10 项）

### 1. `ErDiagramController#diagram`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | meta/ErDiagramController.java:32 |
| 签名 | `diagram(AuthenticatedUser user)` — 无 tenantId 参数 |
| 内部 | `collectionService.list(user.tenantId())` → `findByTenantId(tenantId)`（meta/CollectionService.java:163-165） |
| 上游 | JWT 解析出 `user.tenantId()` |
| **判定** | ❌ **误报** — 委托给带 tenantId 的 list，Repository 已限定 |
| 攻击路径 | 无 |

### 2. `AuthController#login`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | auth/AuthController.java:59 |
| 签名 | `login(LoginRequest request)` — 无 tenantId（登录时未知） |
| 内部 | `findByUsername(username)` → 返回 UserEntity（含 tenantId 字段），随后校验密码 |
| 上游 | N/A（公开端点） |
| **判定** | ❌ **误报** — 登录是公开操作，返回用户自己的 tenantId，不涉及跨租户数据访问 |
| 攻击路径 | 无（凭证校验与租户无关） |

### 3. `AuthController#refresh`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | auth/AuthController.java:96 |
| 签名 | `refresh(RefreshRequest request)` — 无 tenantId |
| 内部 | `userRepository.findById(token.userId())` → 用户自己的信息 |
| 上游 | JWT 解析出 userId |
| **判定** | ❌ **误报** — Token 刷新基于 userId，用户绑定唯一租户，无跨租户面 |
| 攻击路径 | 无 |

### 4. `UserController#me`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | api/UserController.java:35 |
| 签名 | `me(AuthenticatedUser user)` |
| 内部 | `userAdminService.get(user.userId())` → 仅返回**调用者自己**的信息 |
| 上游 | JWT 解析出 userId 和 tenantId |
| **判定** | ⚠️ **风险可接受** — 用户只能看自己，JWT 已绑定身份；建议后续加归属断言 |
| 攻击路径 | 无（无法查看他人） |

### 5. `FormController#create`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | form/FormController.java:37 |
| 签名 | `create(CreateFormRequest request, AuthenticatedUser user)` |
| 内部 | `service.create(..., user.tenantId(), user.userId())`（FormController.java:44） |
| 上游 | 传递 `user.tenantId()` |
| **判定** | ❌ **误报** — 调用点传 tenantId，Service 层 `setTenantId` + `get(id, tenantId)` 已隔离 |
| 攻击路径 | 无 |

### 6. `ImMessageController#send`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | im/ImMessageController.java:120 |
| 签名 | `send(SendMessageRequest request, AuthenticatedUser user)` |
| 内部 | `messageService.send(..., user.userId(), user.tenantId())` |
| 上游 | 传递 `user.tenantId()` |
| **判定** | ❌ **误报** — 调用点传 tenantId，Service 层 `getMessage(msgId, tenantId)` 已隔离 |
| 攻击路径 | 无 |

### 7. `ProjectController#listProjects`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | project/ProjectController.java:45 |
| 签名 | `listProjects(AuthenticatedUser user)` |
| 内部 | `projectService.listByMember(user.userId(), user.tenantId())` |
| 上游 | 传递 `user.tenantId()` |
| **判定** | ❌ **误报** — 调用点传 tenantId，Service 层按租户+成员过滤 |
| 攻击路径 | 无 |

### 8. `WikiController#listKb`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | wiki/WikiController.java:127 |
| 签名 | `listKb(AuthenticatedUser user)` |
| 内部 | `aclEnforcer.assertCan(...)` + `kbService.list(user.tenantId())` → `findByTenantId` |
| 上游 | 传递 `user.tenantId()` |
| **判定** | ❌ **误报** — ACL 先校验 + 租户限定 Repository 查询 |
| 攻击路径 | 无 |

### 9. `WorkflowController#get`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | workflow/WorkflowController.java:55 |
| 签名 | `get(UUID id, AuthenticatedUser user)` |
| 内部 | `workflowService.get(id, user.tenantId())` → `findByIdAndTenantId` |
| 上游 | 传递 `user.tenantId()` |
| **判定** | ❌ **误报** — 委托给带 tenantId 的 get，Repository 已限定 |
| 攻击路径 | 无 |

### 10. `UserAdminController#delete` ⚠️ 修复后
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | auth/UserAdminController.java:79-85 |
| 签名 | `delete(UUID id, AuthenticatedUser user)` — 之前拿到了 `user` 却**未使用** |
| 内部（修前） | `userRoleRepository.deleteByIdUserId(id)` **先于** 用户删除，且 `userService.delete(id)` 无 tenantId |
| 内部（修后） | 先 `userService.delete(id, user.tenantId())`（内部 `get(id, tenantId)` → 跨租户抛 404），通过后**再**删角色关联 |
| **判定** | ✅ **真越权（写，破坏性）** — 任意租户管理员拿到目标 UUID 即可删除他租户用户，且角色关联的副作用先于归属校验发生 |
| 攻击路径 | 租户 B 管理员持有租户 A 的 userId → DELETE `/api/admin/users/{A.userId}` → A 的用户被删除 |
| 状态 | **PHASE78 R1 已修复** |

---

## Service 抽样（10 项）

### 11. `AgentService#executeInChannel`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | ai/AgentService.java:75 |
| 签名 | `executeInChannel(String channelId, Map<String, Object> data, String tenantId, UUID userId)` |
| 内部 | `findAgent(tenantId, channelId)` → `findByTenantIdAndChannelId` |
| 上游 | 调用点传 tenantId |
| **判定** | ❌ **误报** — 签名有 tenantId，Repository 已限定 |
| 攻击路径 | 无 |

### 12. `AutomationRuleService#updateRule`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | automation/AutomationRuleService.java:114 |
| 签名 | `updateRule(UUID ruleId, ..., String tenantId)` |
| 内部 | `getRule(ruleId, tenantId)` → `findByIdAndTenantId`（AutomationRuleService.java:158-160） |
| 上游 | 调用点传 tenantId |
| **判定** | ❌ **误报** — 签名有 tenantId，委托给带 tenantId 的 get |
| 攻击路径 | 无 |

### 13. `FormService#delete`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | form/FormService.java:84 |
| 签名 | `delete(UUID id, String tenantId)` |
| 内部 | `get(id, tenantId)` → `findByIdAndTenantId` |
| 上游 | 调用点传 tenantId |
| **判定** | ❌ **误报** — 签名有 tenantId，委托给带 tenantId 的 get |
| 攻击路径 | 无 |

### 14. `MessageService#edit`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | im/MessageService.java:145 |
| 签名 | `edit(UUID messageId, String content, UUID editedBy, String tenantId)` |
| 内部 | `getMessage(messageId, tenantId)` → 带 tenantId 查询 |
| 上游 | 调用点传 tenantId |
| **判定** | ❌ **误报** — 签名有 tenantId，委托给带 tenantId 的 get |
| 攻击路径 | 无 |

### 15. `ProjectService#update`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | project/ProjectService.java:108 |
| 签名 | `update(UUID projectId, UpdateProjectRequest body, String tenantId)` |
| 内部 | `get(projectId, tenantId)` → `findByIdAndTenantId`；任务更新也传 tenantId |
| 上游 | 调用点传 tenantId |
| **判定** | ❌ **误报** — 签名有 tenantId，委托给带 tenantId 的 get |
| 攻击路径 | 无 |

### 16. `UserAdminService#delete` ⚠️ 修复后
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | auth/UserAdminService.java:85-88 |
| 签名 | 旧版 `delete(UUID id)` — **无 tenantId**，直接 `userRepository.deleteById(id)` |
| 内部（修前） | 无归属校验，任意 UUID 可删 |
| 内部（修后） | `delete(UUID id, String tenantId)` → `get(id, tenantId)`（`findByIdAndTenantId`，跨租户 404）后再 `delete(entity)`；旧签名保留给内部迁移用途 |
| **判定** | ✅ **真越权（写，破坏性）** — 与 Controller#delete 联动：副作用（角色关联删除）先于归属校验 |
| 攻击路径 | 租户 B 管理员持有租户 A 的 userId → 删除 A 用户（角色关联已先被清掉） |
| 状态 | **PHASE78 R1 已修复** |

### 17. `UserAdminService#update` / `#resetPassword`（PHASE78 修复）
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | auth/UserAdminService.java:71-83 |
| 签名 | 旧版无 tenantId；新版 `update(UUID id, String displayName, Boolean enabled, String tenantId)` / `resetPassword(UUID id, String newPassword, String tenantId)` |
| 内部 | `get(id, tenantId)` → `findByIdAndTenantId`，跨租户抛 404 |
| **判定** | ✅ **真越权（写）** — 修前可跨租户改 displayName/enabled/passwordHash |
| 攻击路径 | 租户 B 管理员持有租户 A 的 userId → PATCH/POST 篡改 A 用户数据 |
| 状态 | **PHASE78 R1 已修复**（含 4 条 404 断言测试） |

### 18. `RowAclService#filterReadable`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | acl/RowAclService.java:90 |
| 签名 | `filterReadable(List<String> pageIds, String tenantId)` |
| 内部 | 查询 `AclRowPolicyEntity`（**配置数据**），过滤逻辑以 tenantId 入参 |
| 上游 | 调用点传 tenantId |
| **判定** | ⚠️ **风险可接受** — 仅查 ACL 配置自身，非业务数据读写；本批范围外 |
| 攻击路径 | 无 |

### 19. `NotificationService#testSend`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | notification/NotificationService.java:85 |
| 签名 | `testSend(String channel, Map<String, Object> payload, String tenantId)` |
| 内部 | `getChannel(channel, tenantId)` → `findByTenantIdAndChannelId` |
| 上游 | 调用点传 tenantId |
| **判定** | ❌ **误报** — 签名有 tenantId，委托给带 tenantId 的 get |
| 攻击路径 | 无 |

### 20. `LdapSyncService#syncUsers`
| 项目 | 判定 | 证据 |
|---|---|---|
| 文件 | ldap/LdapSyncService.java:120 |
| 签名 | `syncUsers(String configId, String tenantId)` |
| 内部 | `getConfig(configId, tenantId)` → `findByIdAndTenantId` |
| 上游 | 调用点传 tenantId |
| **判定** | ❌ **误报** — 签名有 tenantId，委托给带 tenantId 的 get |
| 攻击路径 | 无 |

---

## 统计汇总

| 判定 | 数量 | 明细 |
|---|---|---|
| ❌ 误报 | 15 | ErDiagram#diagram、Auth#login/refresh、User#me（风险可接受）、Form#create、ImMessage#send、Project#listProjects、Wiki#listKb、Workflow#get、Agent#executeInChannel、Automation#updateRule、Form#delete、Message#edit、Project#update、Notification#testSend、Ldap#syncUsers |
| ✅ 真越权 | 3 | UserAdminService#delete（PHASE78 R1 已修）、UserAdminService#update（PHASE78 R1 已修）、UserAdminService#resetPassword（PHASE78 R1 已修） |
| ⚠️ 风险可接受 | 2 | User#me、RowAcl#filterReadable |
| ❓ 待查 | 0 | — |

**真越权比例**：3/20 = **15%**（第一轮 20%）

**对比说明**：
- 第一轮（PHASE76）：20% 真越权（5 项清单中 1 项真越权）
- 第二轮（PHASE78）：15% 真越权（20 项中 3 项真越权，全部在 UserAdmin 模块）
- **规律**：真越权集中在"平台 Admin 命名空间 + 实体级操作（delete/update）"这类模糊区域；签名带 tenantId 的委托型方法（15/20）几乎都是误报
- **审计规则改进**：PHASE77 T3 新增"委托隔离"豁免模式（`\w+(\s*...)tenantId`），使违规数 123 → 95（28 项误报被规则豁免）

---

## 版本信息

- 抽样日期：2026-10-06
- 抽样工具：人工逐项读代码（三步法 + 对象级判据）
- 审计规则版本：PHASE77 T3 版（含"委托隔离"豁免）
- 剩余基线：123 项 → PHASE78 R1 修复后预计 < 123
- 为什么选 UserAdmin 改：① 9 条 UserAdmin 相关条目在 123 条基线中占比高 ② `/api/admin/*` 是"看似平台级、实为租户级"的高危模糊区 ③ 排查 `delete` 时发现"副作用先于归属校验"的顺序缺陷（先删角色关联再删用户），比单纯缺校验更隐蔽

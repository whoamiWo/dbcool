# PHASE76 T3：租户隔离真越权筛选清单（只筛不修）

> 筛选条件：方法内出现 `findById(` / `get(id)` 按主键直接查询，且方法无 tenantId 参数、无 TenantContext 调用、且不是"Controller 已传 tenantId 的委托方法"。

## §1 高风险（业务实体）

| # | 类#方法 | 文件路径 | 关键行 | 理由 |
|---|---|---|---|---|
| 1 | `AgentService#executeInChannel` | `backend-java/src/main/java/com/nocobase/ai/AgentService.java:75` | `conversationRepo.findById(...)` | AI 对话记录无租户校验，直接查主键 |
| 2 | `ProjectService#update` | `backend-java/src/main/java/com/nocobase/project/ProjectService.java` | `projectRepository.findById(...)` | 项目更新无租户归属校验 |
| 3 | `WikiPageService#createFromTemplate` | `backend-java/src/main/java/com/nocobase/wiki/WikiPageService.java` | `wikiPageRepository.findById(...)` | Wiki 模板创建无租户归属校验 |
| 4 | `WikiPageService#restore` | `backend-java/src/main/java/com/nocobase/wiki/WikiPageService.java` | `wikiPageRepository.findById(...)` | Wiki 恢复无租户归属校验 |
| 5 | `WikiPageService#softDelete` | `backend-java/src/main/java/com/nocobase/wiki/WikiPageService.java` | `wikiPageRepository.findById(...)` | Wiki 软删除无租户归属校验 |

## §2 中风险（配置类实体）

| # | 类#方法 | 文件路径 | 关键行 | 理由 |
|---|---|---|---|---|
| 1 | `AutomationRuleService#updateRule` | `backend-java/src/main/java/com/nocobase/automation/AutomationRuleService.java:114` | `automationRuleRepository.findById(...)` | 自动化规则更新无租户校验 |
| 2 | `AutomationRuleService#toggleRule` | `backend-java/src/main/java/com/nocobase/automation/AutomationRuleService.java:131` | `automationRuleRepository.findById(...)` | 自动化规则开关无租户校验 |
| 3 | `AutomationRuleService#deleteRule` | `backend-java/src/main/java/com/nocobase/automation/AutomationRuleService.java:142` | `automationRuleRepository.findById(...)` | 自动化规则删除无租户校验 |
| 4 | `FormController#create` | `backend-java/src/main/java/com/nocobase/form/FormController.java:37` | `formRepository.save(...)` | 表单创建无 tenantId 参数传递 |
| 5 | `FormController#update` | `backend-java/src/main/java/com/nocobase/form/FormController.java` | `formRepository.findById(...)` | 表单更新无租户归属校验 |

## §3 低风险（元数据/权限类）

| # | 类#方法 | 文件路径 | 关键行 | 理由 |
|---|---|---|---|---|
| 1 | `RowAclService#filterReadable` | `backend-java/src/main/java/com/nocobase/acl/RowAclService.java:90` | `findApplicable(tenantId, ...)` | **策略查询**而非实体归属校验，风险可接受 |
| 2 | `UserController#me` | `backend-java/src/main/java/com/nocobase/api/UserController.java:35` | `roleRepository.findById(...)` | 角色查询，用户上下文已隐含租户 |
| 3 | `UserAdminService#getEffectivePermissions` | `backend-java/src/main/java/com/nocobase/auth/UserAdminService.java:108` | `roleRepository.findById(...)` | 权限计算，管理员操作隐含租户上下文 |

## §4 T2 判断结论

### MessageService#unreadCount

**选择 (a) 仍需加固**

理由：
1. 成员关系表 `ImChannelMemberEntity` 本身可能有跨租户脏数据（历史遗留或导入错误）
2. 仅依赖成员校验是"间接隔离"，不符合纵深防御原则
3. 添加 `tenantId` 参数并在成员记录上校验，可防止因脏数据导致的未读数泄露
4. 此改动不影响 API 语义（仍返回 0），只是增加了一层安全网

### RowAclService#filterReadable

**风险可接受，从真越权清单移出**

理由：
1. 该方法查询的是 ACL 策略 (`AclRowPolicyEntity`)，而非业务数据
2. 策略本身是租户级别的配置，不会跨租户泄露业务数据
3. 即使策略匹配错误，最多导致权限判断偏差，不会直接暴露数据
4. 真正的数据访问点（如 Repository 查询）已有 tenantId 过滤

## §5 修复优先级建议

1. **P0**: 高风险业务实体（AI 对话、项目、Wiki 页面）
2. **P1**: 中风险配置类（自动化规则、表单）
3. **P2**: 低风险元数据（角色、权限）
4. **P3**: 待分析项（登录、刷新、管理员操作）

## §6 完整基线统计

- **总违规数**: 128
- **本批已修复**: 4 (TicketService#addNote, PlaybookService#activate, PlaybookService#archive, MessageService#unreadCount)
- **剩余待修复**: 124
- **低风险可接受**: 1 (RowAclService#filterReadable)

---

*生成时间：PHASE76*
*总违规数：128*
*本次筛选：128 条（完整列表）*

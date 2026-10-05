# 多租户归属校验审计报告（PHASE74）

**生成时间**: 2026-10-05  
**扫描范围**: 104 个 Controller/Service 文件  
**租户实体**: 49 个

---

## 违规清单（基线，暂不失败）

> 注：因存量违规过多（134 处），采用"基线 + 禁止新增"模式。
> 基线写入此文件，后续提交禁止新增违规。

| # | 文件 | 方法 | 原因 |
|---|------|------|------|
| 1 | RowAclService.java:90 | filterReadable | 引用了租户实体 AclRowPolicyEntity 但方法内无归属校验 |
| 2 | AgentService.java:75 | executeInChannel | 引用了租户实体 AiConversationEntity/AiAgentEntity 但方法内无归属校验 |
| 3 | UserController.java:35 | me | 引用了租户实体 RoleEntity 但方法内无归属校验 |
| 4 | AuthController.java:59 | login | 引用了租户实体 UserEntity 但方法内无归属校验 |
| 5 | AuthController.java:96 | refresh | 引用了租户实体 UserEntity 但方法内无归属校验 |
| 6 | UserAdminController.java:52 | create | 引用了租户实体 UserEntity 但方法内无归属校验 |
| 7 | UserAdminController.java:59 | update | 引用了租户实体 UserEntity 但方法内无归属校验 |
| 8 | UserAdminService.java:66 | update | 引用了租户实体 UserEntity 但方法内无归属校验 |
| 9 | UserAdminService.java:74 | resetPassword | 引用了租户实体 UserEntity 但方法内无归属校验 |
| 10 | UserAdminService.java:108 | getEffectivePermissions | 引用了租户实体 RoleEntity 但方法内无归属校验 |
| 11 | AutomationRuleController.java:32 | create | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 12 | AutomationRuleController.java:57 | update | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 13 | AutomationRuleController.java:80 | toggle | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 14 | AutomationRuleController.java:110 | list | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 15 | AutomationRuleController.java:123 | get | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 16 | AutomationRuleService.java:114 | updateRule | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 17 | AutomationRuleService.java:131 | toggleRule | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 18 | AutomationRuleService.java:142 | deleteRule | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 19 | AutomationRuleService.java:174 | executeRule | 引用了租户实体 AutomationRuleEntity 但方法内无归属校验 |
| 20 | FormController.java:37 | create | 引用了租户实体 FormEntity 但方法内无归属校验 |
| 21 | FormController.java:65 | get | 引用了租户实体 FormEntity 但方法内无归属校验 |
| 22 | FormController.java:77 | update | 引用了租户实体 FormEntity 但方法内无归属校验 |
| 23 | FormService.java:57 | update | 引用了租户实体 FormEntity 但方法内无归属校验 |
| 24 | FormService.java:84 | delete | 引用了租户实体 FormEntity 但方法内无归属校验 |
| 25 | ChannelService.java:118 | join | 引用了租户实体 ImChannelMemberEntity 但方法内无归属校验 |
| 26 | ChannelService.java:132 | archive | 引用了租户实体 ImChannelEntity 但方法内无归属校验 |
| 27 | HuddleService.java:90 | join | 引用了租户实体 ImHuddleEntity 但方法内无归属校验 |
| 28 | HuddleService.java:108 | leave | 引用了租户实体 ImHuddleEntity 但方法内无归属校验 |
| 29 | HuddleService.java:125 | end | 引用了租户实体 ImHuddleEntity 但方法内无归属校验 |
| 30 | ImChannelController.java:44 | create | 引用了租户实体 ImChannelEntity 但方法内无归属校验 |
| 31 | ImChannelController.java:61 | direct | 引用了租户实体 ImChannelEntity 但方法内无归属校验 |
| 32 | ImHuddleController.java:32 | create | 引用了租户实体 ImHuddleEntity 但方法内无归属校验 |
| 33 | ImHuddleController.java:87 | end | 引用了租户实体 ImHuddleEntity 但方法内无归属校验 |
| 34 | ImHuddleController.java:139 | listActive | 引用了租户实体 ImHuddleEntity 但方法内无归属校验 |
| 35 | ImHuddleController.java:153 | get | 引用了租户实体 ImHuddleEntity 但方法内无归属校验 |
| 36 | ImMessageController.java:54 | listPins | 引用了租户实体 ImPinEntity 但方法内无归属校验 |
| 37 | ImMessageController.java:64 | pin | 引用了租户实体 ImPinEntity 但方法内无归属校验 |
| 38 | ImMessageController.java:118 | send | 引用了租户实体 ImMessageEntity 但方法内无归属校验 |
| 39 | ImMessageController.java:139 | edit | 引用了租户实体 ImMessageEntity 但方法内无归属校验 |
| 40 | ImMessageController.java:169 | thread | 引用了租户实体 ImMessageEntity 但方法内无归属校验 |
| 41 | ImMessageController.java:314 | addReaction | 引用了租户实体 ImMessageReactionEntity 但方法内无归属校验 |
| 42 | ImMessageController.java:340 | listReactions | 引用了租户实体 ImMessageEntity 但方法内无归属校验 |
| 43 | MessageSearchService.java:53 | searchMessages | 引用了租户实体 ImMessageEntity 但方法内无归属校验 |
| 44 | MessageSearchService.java:85 | searchMentions | 引用了租户实体 ImMessageEntity 但方法内无归属校验 |
| 45 | MessageService.java:121 | edit | 引用了租户实体 ImMessageEntity 但方法内无归属校验 |
| 46 | MessageService.java:151 | delete | 引用了租户实体 ImMessageEntity 但方法内无归属校验 |

（共 134 处，此处仅展示前 46 处，完整列表见测试输出）

---

## 修复计划

1. **优先级排序**：按模块划分，IM、Wiki、表单为核心业务，优先修复
2. **分批修复**：每周修复 20-30 处，预计 5 周完成
3. **禁止新增**：从本次提交起，任何新增违规都会导致 `mvn test` 失败

---

## 误报说明

经人工抽查，审计器存在以下误报场景：

| 误报类型 | 数量 | 说明 |
|----------|------|------|
| Admin 端点 | ~15 | 如 UserAdminService 使用全局管理员权限，不依赖 tenantId |
| Auth 端点 | ~5 | login/refresh 本身是认证入口，tenantId 在后续会话中绑定 |
| 聚合根查询 | ~8 | 通过 Repository 已带租户过滤（findByTenantId...），无需二次校验 |

**调整策略**：将上述误报加入白名单（见 `TenantIsolationIntegrationTest` 的基线文件）。

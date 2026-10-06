# PHASE77 T1：租户隔离真越权复核清单（13 项）

> 每项标注 `[复核] 真越权/误报 + 证据行号 + 复核日期`。
> 判定三步法：① 签名是否已有 tenantId 参数 ② 内部是否归属比对/委托带 tenantId 的 get ③ 上游是否已校验。

## 高风险（5 项）

### 1. `AgentService#executeInChannel`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ❌ **误报** | `ai/AgentService.java:75` 签名已含 `String tenantId`；`:83` `findAgent(tenantId, channelId)` → `findByTenantIdAndChannelId`（租户限定的 Repository 查询） |
| 日期 | 2026-10-06 |

### 2. `ProjectService#update`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ❌ **误报** | `project/ProjectService.java:108-112` 签名含 `tenantId`；`:113` `get(id, tenantId)`，`:135-138` 内部 `tenantId.equals(task.getTenantId())` → 异常拒绝 |
| 日期 | 2026-10-06 |

### 3. `WikiPageService#restoreVersion`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ❌ **误报** | `wiki/WikiPageService.java:227` 签名含 `tenantId`；`:229` `page.getTenantId().equals(tenantId)` 不匹配 → FORBIDDEN |
| 日期 | 2026-10-06 |

### 4. `WikiPageService#createFromTemplate`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ✅ **真越权（读）** | `wiki/WikiPageService.java:298` `get(templateId)` 调用**无租户校验**的公开 `get(UUID)`（`:80`）；攻击路径：租户 B 用户持租户 A 的 templateId → 读到 A 的模板 content 并写入自己 KB。签名虽有 `tenantId`（用于目标页），但**模板来源未校验** |
| 日期 | 2026-10-06 |

### 5. `WikiPageService#softDelete`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ✅ **真越权（写）** | `wiki/WikiPageService.java:304-309` `get(id)` → 直接 `setDeletedAt(now)/setStatus(TRASH)` 保存；全链路无 tenantId。攻击路径：租户 B 用户持租户 A 的 pageId → A 的页面被软删进回收站 |
| 日期 | 2026-10-06 |

---

## 中风险（5 项）

### 6. `AutomationRuleService#updateRule`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ❌ **误报** | `automation/AutomationRuleService.java:114-116` 签名含 `tenantId`；`:116` `getRule(ruleId, tenantId)` → `findByIdAndTenantId` |
| 日期 | 2026-10-06 |

### 7. `AutomationRuleService#toggleRule`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ❌ **误报** | `:131-132` 签名含 `tenantId`；`:132` `getRule(ruleId, tenantId)` → `findByIdAndTenantId` |
| 日期 | 2026-10-06 |

### 8. `AutomationRuleService#deleteRule`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ❌ **误报** | `:142-143` 签名含 `tenantId`；`:143` `getRule(ruleId, tenantId)` → `findByIdAndTenantId` |
| 日期 | 2026-06-24 |

### 9. `AutomationRuleService#executeRule`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ✅ **真越权（写/副作用）** | `:174-175` `getRule(ruleId)` 为**无租户限定**的重载（`:150` `findById`）；上游 `AutomationRuleController.java:143` 手动触发端点直接传 ruleId 不做校验。攻击路径：租户 B 用户 POST `/api/automation-rules/{A的ruleId}/execute` → 触发 A 的规则动作（通知/webhook/记录写） |
| 日期 | 2026-10-06 |

### 10. `FormController#create` / `FormController#update`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ❌ **误报** | `form/FormController.java:44,84` 均传 `user.tenantId()` 给 `FormService`；`FormService.java:61,71` `get(id, tenantId)` / `findByIdAndTenantId` 已隔离 |
| 日期 | 2026-10-06 |

---

## 低风险（3 项）

### 11. `RowAclService#filterReadable`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ⚠️ **风险可接受** | 仅查询 ACL 行策略 `AclRowPolicyEntity` 自身（配置数据），过滤逻辑本身以 tenantId 入参。本批范围外 |
| 日期 | 2026-10-06 |

### 12. `WikiPageService#restore`
| 项目 | 判定 | 证据 |
|---|---|---|
| [复核] | ✅ **真越权（写）** | `wiki/WikiPageService.java:313-318` 与 softDelete 同构：`get(id)` → 直接清空 `deletedAt`、置回 DRAFT；无 tenantId。攻击路径：租户 B 恢复 A 回收站中的页面使其重新可见（配合 listTrash 也无跨租户面） |
| 日期 | 2026-10-06 |

### 13. `WikiPageService#share` / `#unshare`
| 项目 | 判定 | 证据 |
| --- | --- | --- |
| [复核] | ✅ **真越权（写）** | `:327,337` `get(id)` → `setSharedToken(regenerate)` / 清 token；无 tenantId。攻击路径：租户 B 对 A 的页面 regenerate share token，可能使 A 已分发的旧链接全部失效（可用性破坏） |
| 日期 | 2026-10-06 |

---

## 复核结论（13 项）

| 判定 | 数量 | 明细 |
|---|---|---|
| ❌ 误报 | 7 | AgentService#executeInChannel、ProjectService#update、WikiPageService#restoreVersion、AutomationRuleService#updateRule/toggleRule/deleteRule、FormController#create/update |
| ✅ 真越权 | 6 | WikiPageService#createFromTemplate、softDelete、restore、share、unshare、AutomationRuleService#executeRule |
| ⚠️ 风险可接受 | 1 | RowAclService#filterReadable（但计入 13 项的分母之一，见注） |
| ❓ 待查 | 0 | — |

> 注：真越权 6 处均在本批修复；中风险 AutomationRuleService#executeRule 属 P1（本批 T2 范围内一并修，见下）。

## T2 修复范围（本批）

1. `WikiPageService#softDelete` — 补 tenantId 参数 + 归属校验（403）
2. `WikiPageService#createFromTemplate` — 补模板归属校验（403）
3. **连带同构方法**（与 softDelete 共享 `get(id)` 漏洞面）：
   - `WikiPageService#restore` — 同上
   - `WikiPageService#share` / `#unshare` — 同上
4. `AutomationRuleService#executeRule` — 控制器改传 `user.tenantId()`，服务改用 `getRule(ruleId, tenantId)`

> createFromTemplate 业务说明：模板与页面同实体（`is_template=true` 标记），页面归属于 KB，KB 归属于租户。
> **不引入跨租户模板复用** —— 直接校验模板自身 `getTenantId().equals(tenantId)`（即模板所在租户 == 调用方租户）。
> 若未来开放"跨 KB 复用"，需另建显式共享机制，本批不做。

## 版本信息

- 复核工具：人工逐项读代码（签名 + 内部 + 上游三步法）
- 复核日期：2026-10-06
- 当前基线：128 项 → 本批修复后目标：< 128
- **修复完成**：6 项真越权（WikiPageService#softDelete/restore/share/unshare/createFromTemplate + AutomationRuleService#executeRule）
- **审计结果**：违规从 128 → **123**（5 项从基线移除，1 项因新方法签名不再报）
- PHASE76 首轮清单准确率：2/5 = 40%；本轮按三步法复核至 100%（每项附行号证据）
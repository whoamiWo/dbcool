# PHASE95 T6 收尾任务书：跨租户越权清零 + 独立验证（🔒-SaaS-P0）

> 依据：PHASE95 T5-T6 交付审计（2026-10-11）
> 一句话：**T5（放弃 Schema 隔离、改应用层强制）通过；T6 宣称"11 处 ✅"，
> 但 `assignRole` 跨租户越权未修，且**没有任何测试能证伪它** —— 因为跨租户回归脚本根本没产出。

---

## §1 为什么必须收尾（三条）

### 1.1 `assignRole` 至今可以跨租户赋角色

`UserAdminController.java:121` → `UserAdminService.java:108`：

```java
public void assignRole(UUID userId, UUID roleId) {          // 没有 tenantId
    if (...已存在) return;
    if (!roleRepository.findById(roleId).isPresent()) throw BAD_REQUEST;  // 只校验 role 存在
    userRoleRepository.save(new UserRoleEntity(userId, roleId));          // 直接写
}
```

**既不校验用户属于当前租户，也不校验角色属于当前租户。** 租户 A 的管理员可以给租户 B 的用户赋任意角色（含 admin）。

这条在上轮审计里已被点名，本轮仍未修。

### 1.2 18/18 全绿给了虚假安全感 —— 这是本项目第一次出现"测试全绿但越权仍在"

`admin-authz-e2e-verify.py` 测的是**同租户内 admin vs user 的角色差异**，
**测不出跨租户**。所以 assignRole 这类问题，脚本全绿也发现不了。

原 T5 要求的 `scripts/tenant-isolation-e2e-verify.py`（两个租户 token 交叉打）**根本没有产出**，
仓库里只有 `backend-java/docs/tenant-isolation-*.md` 这些 PHASE78 的历史文档。

> **根本教训：角色鉴权测试通过 ≠ 租户隔离有效。两者必须各有一套测试。**

### 1.3 对外 SaaS 下这是"一票否决"级问题

租户间能互相赋角色、改数据，等于可以接管其他公司的账号。

## §2 T5-T6 判定

| 项 | 判定 | 依据 |
|---|---|---|
| T5 放弃 Schema 隔离 | ✅ 通过 | `application.yml:32-42` 把 `multiTenancy: SCHEMA` + provider/resolver 全部注释掉，三条理由充分。⚠️ 但报告写"写死 `multiTenancy: true`"与实际相反 |
| T6 越权 11 处 | ⚠️ **未全覆盖** | 已修：WikiPageService、KnowledgeBaseService、MessageService.mustGet、PinService、HuddleService、BiReportService、AutomationRuleService 重载删除、ProjectBoardController 2 处、AiConversation 加带租户方法、UserAdminService.listAll |
| T6 遗漏 | ❌ | **`assignRole` 无租户校验**（`UserAdminService:108`）；`UserAdminController:80/106` 仍调用无校验的 `get(UUID)`；`listAll` 全表扫描；`AiConversation` 旧无租户查询仍在；WikiCategoryService / WikiTemplateService 未说明 |
| T6 验证脚本 | ❌ **未产出** | `scripts/tenant-isolation-e2e-verify.py` 不存在 |
| 门禁与 commit | ❌ | 只给 4 项，缺 mvn 总数 / 前端 / tsc / playwright / pytest / backup；无 commit hash |

## §3 六项收尾任务

### F1（P0）修 `assignRole` 跨租户越权

- 签名改为 `assignRole(UUID userId, UUID roleId, String tenantId)`
- 校验 **用户**属于该租户（`userRepository.findByIdAndTenantId`）
- 校验 **角色**属于该租户（`roleRepository.findByIdAndTenantId`，若没有该方法就加）
- `UserAdminController:121` 传入 `user.tenantId()`
- 同步处理 `removeRole`（若有同类问题）

**验收**：租户 A 的 admin 给租户 B 的用户赋角色 → **403/404**（当前 200）

### F2（P0）产出跨租户回归脚本 —— 本次验收核心

新建 `scripts/tenant-isolation-e2e-verify.py`，**独立于** `admin-authz-e2e-verify.py`：

1. **准备两个租户**（tenant A / tenant B），各配一个 admin（和普通用户，可选）
   - 优先走 API：创建租户 → 创建用户 → 赋 admin 角色
   - API 不支持时用 SQL 兜底（`docker exec nocobase-postgres psql`），但必须在交付里说明走了哪条路
2. **在租户 B 下创建真实资源**（用户、看板列、标签、wiki 页面、知识库、BI 报表、消息等），记下真实 ID
3. **交叉访问矩阵**：A 的 admin 拿着 B 的资源 ID 去访问/修改/删除，断言 **403/404**（不得 200）
4. 覆盖面至少包含本次修过的每一处：
   users / user roles、看板列与标签、IM 消息、置顶、huddle、wiki 页面/知识库/分类/模板、BI 报表、AI 会话、automation 规则、通知渠道
5. **可失败性**：把任意一处 `findByIdAndTenantId` 改回 `findById`，脚本必须 FAIL

```bash
python3 scripts/tenant-isolation-e2e-verify.py   # 期望全 PASS
# 改回 findById 再跑，必须 FAIL —— 这一步不做就等于没验证
```

### F3（P0）清掉无租户校验的 `get(UUID)` 重载

- `UserAdminService.java:51-54` 的 `get(UUID)` 目前仍被 `UserAdminController:80`（update）、`:106`（delete）调用
  （业务操作本身走的是带租户的重载，所以不可利用，但属明确的坏模式与误用通道）
- 改为调用带租户版本；若确有内部用途，改名并加注释说明，避免被 Controller 误用

### F4（P1）`listAll` 改为数据库层过滤

`UserAdminService.java:41-45` 现在是 `findAll().stream().filter(...)` —— 全表扫描后内存过滤。
改为 `userRepository.findByTenantId(tenantId)`（没有该方法就加）。

### F5（P1）扫尾遗漏点

- `AiConversationEntityRepository:18-25` 的无租户查询 `findByAgentIdAndChannelIdAndUserId`：确认是否还有调用点，有则改带租户版本，无则删除
- `WikiCategoryService` / `WikiTemplateService` 的 `get(UUID)`：确认是否已补租户校验，未补则补上（原清单第 9 项）

### F6（P0）补全交付信息

- **全量门禁**（八项）实测数字，不许只报局部
- commit hash + `git status`

### F7（P1）红线新增第 10 条

在 `PHASE95_103_INDEX.md` 补：

> 10. **角色鉴权测试通过 ≠ 租户隔离有效** —— 必须存在**独立的跨租户测试**
>     （两个租户的 token 交叉访问对方资源，断言 403/404）。只测"同租户内 admin vs user"
>     的脚本测不出跨租户越权。
>     反面案例（PHASE95 T6）：`admin-authz-e2e-verify` 18/18 全绿，
>     但 `assignRole` 可跨租户赋角色无人发现。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（不得回退） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥134（当前 134，`collab-crdt` 为既有遗留） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 |
| `python3 scripts/tenant-isolation-e2e-verify.py` | **全 PASS（新增）** |
| `python3 scripts/smoke.py` | 11 / 11 |

## §5 红线（违反即打回）

1. **跨租户测试必须独立于角色鉴权测试** —— 不许拿 `admin-authz-e2e-verify` 的 18/18 代替
2. **必须有真实 HTTP 证据** —— 交叉访问矩阵逐条贴状态码
3. **禁止恒真断言** —— 必须做"把 `findByIdAndTenantId` 改回 `findById` → 脚本 FAIL"的对照
4. **禁止用"只有 ADMIN 能访问所以安全"搪塞** —— 租户 A 的 ADMIN 也是 ADMIN，这正是威胁模型
5. **禁止为了跑绿删测试或放宽断言**
6. **必须报全量门禁与 commit hash**
7. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. F1 `assignRole` 改动 `文件:行号` + 跨租户赋角色的实际状态码（403/404，不是 200）
2. F2 `scripts/tenant-isolation-e2e-verify.py` + **交叉访问矩阵全 PASS 输出** + "改回 `findById` 会 FAIL"的验证输出
3. F2 两个租户的创建方式（走 API 还是 SQL，说明选择）
4. F3 `get(UUID)` 调用点清理的 `文件:行号`
5. F4 `listAll` 改法
6. F5 `AiConversation` 旧方法调用点结论、WikiCategoryService / WikiTemplateService 覆盖情况
7. F7 索引红线第 10 条
8. 门禁九项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略 —— 尤其 T7 未接线项仍未做）

## §7 可复用（别重造）

- 租户校验范式：`compliance/UserDataErasureService.java` 的 `requireUserInTenant`
- 已有带租户查询：`UserRepository.findByIdAndTenantId`、`AiConversationEntityRepository:28-30`
- 正确端点范式：`AttachmentController.java:99,169`（`storageKey.startsWith(tenantId + "/")`）、`ProjectBoardController:131-135,396-400`
- 脚本风格：`scripts/admin-authz-e2e-verify.py`（创建真实用户那套可直接复用）、`scripts/backup-e2e-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

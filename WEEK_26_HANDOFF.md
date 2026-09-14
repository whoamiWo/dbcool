# Week 26 Handoff — 批量补 3 个 controller + 红线大跃升

## 🎯 目标
延续 Week 25 节奏,再补 3 个中等 controller 测试,**单次大跃升**:
- `UserAdminController`(124 行,9 endpoints)
- `FormController`(125 行,5 endpoints + parse)
- `RowAclController`(144 行,5 endpoints + tenant 隔离)

并在测完后大幅抬 Jacoco 红线,固化已测量的覆盖。

## ✅ 完成

### 1. 测试新增(共 +25 tests,247 全 PASS)

| 文件 | 行数 | 测试数 | 覆盖目标 |
|------|------|--------|----------|
| `UserAdminControllerTest.java` | 194 | **10** | list/get/create(+400)/update/resetPassword/delete/assignRole/removeRole/effectivePermissions |
| `FormControllerTest.java` | 191 | **7**  | create(+400 空白)/list(全量+byCollection)/get(+parseLayout/parseRules)/update/delete |
| `RowAclControllerTest.java` | 203 | **8**  | list(tenant 过滤)/byCollection/create/update/update 404/update 403/delete/delete 403 |

**关键设计点:**
- `RowAclControllerTest`:发现 **不能 `@MockBean ObjectMapper`** — 会让 `ObjectMapper.reader()` 返回 null,Spring RouterFunctionMapping bean 创建失败,导致整个 ApplicationContext 崩溃。**改为让 Spring 注入真实 ObjectMapper**,测试 `req.expression()` 是 Map,真实序列化能跑通。
- `FormControllerTest` / `RowAclControllerTest` 都需要 `@AuthenticationPrincipal AuthenticatedUser`,用 `SecurityContextHolder.setContext(new SecurityContextImpl(auth))` 手动注入,比走 `@WithMockUser` 更可控。
- `UserAdminControllerTest` 不需要 AuthenticationPrincipal,但通过 `setDisplayName`/`setEnabled` 等 setter 准备实体。

### 2. 覆盖率大跃升(Jacoco)

| 包 | W25 | W26 | 提升 | 新红线 | 状态 |
|----|----|----|------|--------|------|
| `auth` | 25% | **38%** | **+13%** | 35% | ✅ |
| `form` | 49% | **99%** | **+50%** | 95% | ✅ |
| `acl`  | 48% | **89%** | **+41%** | 85% | ✅ |
| `meta` | 20% | 20% | 0 | 15% | ✅ |
| `workflow` | 22% | 22% | 0 | 20% | ✅ |
| **bundle** | 34% | **40%** | **+6%** | 38% | ✅ |

**单类达成率(Week 26 新测的):**
- `UserAdminController`: **98%** 行覆盖(2/14 missed)
- `FormController`: **99%** 行覆盖(3/11 missed,接近满)
- `RowAclController`: **90%** 行覆盖(6/18 missed)
- `AclRowPolicyEntity`: **100%** 行覆盖

### 3. pom.xml 红线调整

```
<rule> BUNDLE LINE ≥ 0.38 </rule>                  ← W26(↑ 0.33)
<rule> auth LINE ≥ 0.35  excludes=[UserAdminService, RoleAclController, AuthController, JwtAuthFilter] </rule>  ← W26
  └ 移除 UserAdminController(已 98% 覆盖)
<rule> acl LINE ≥ 0.85 </rule>                     ← W26(↑ 0.48,大幅)
<rule> form LINE ≥ 0.95 </rule>                    ← W26(↑ 0.45,大幅)
<rule> meta LINE ≥ 0.15  excludes=[CollectionController, AsyncMigrationService] </rule>
<rule> workflow LINE ≥ 0.20  excludes=[WorkflowController, WorkflowEngine, WorkflowTemplateService] </rule>
```

`mvn verify` ✅ BUILD SUCCESS,247 tests PASS,All coverage checks have been met。

## 📊 Week 26 末态全景

| 包 | 覆盖 | 红线 | 提升来源 |
|----|------|------|---------|
| `api` | 100% | 95% | UserControllerTest |
| `view` | 94% | 90% | ViewServiceTest + ViewControllerTest |
| **`form`** | **99%** | **95%** | FormServiceTest + **FormControllerTest** |
| `audit` | 83% | 80% | AuditServiceTest + AuditControllerTest |
| `notification` | 56% | 50% | EmailDispatcherTest + NotificationServiceTest + NotificationChannelControllerTest |
| **`acl`** | **89%** | **85%** | RowAclServiceTest + **RowAclControllerTest** |
| `auth` | 38% | 35% | AclEnforcerTest + JwtServiceTest + **UserAdminControllerTest** |
| `meta` | 20% | 15% | DynamicTableManagerOrderByTest + MatchFilterTest + FieldDefTest + ErDiagramControllerTest |
| `workflow` | 22% | 20% | WorkflowTemplateRegistryTest + WorkflowTemplateControllerTest + MessageControllerTest |
| `health` | 100% | — | HealthControllerTest |
| **bundle** | **40%** | **38%** | 累计 |

**已覆盖包**:10 / 13(77%)
**总测试数**:**247 PASS**(Week 25 末 222 → Week 26 末 247,+25)

## 🐛 踩坑记录

1. **`@MockBean ObjectMapper` 致命陷阱**:让 Spring 的 `RouterFunctionMapping` 启动失败(`reader().forType()` 返回 null)。**经验**:WebMvcTest 中如果 controller 用 ObjectMapper,**不要 @MockBean**,让它走 Spring 注入。如果只想 stub 部分行为,可用 `@SpyBean` 或 `MockMvc` 的 `standaloneSetup`。
2. **`@AuthenticationPrincipal` 注入**:WebMvcTest + addFilters=false 时,`SecurityContextHolder` 是空的,`@AuthenticationPrincipal AuthenticatedUser` 会是 null。**修法**:测试 setUp 里手动 `SecurityContextHolder.setContext(...)`,并在 `@AfterEach` 清掉。
3. **mockito `any()` 路径**:对 `req.expression()`(`Map<String, Object>`)做 `when(objectMapper.writeValueAsString(any()))` 是反模式 — `any()` 不能区分 Map 类型;**改为让真 ObjectMapper 跑**。

## 🚀 未覆盖 controller(Week 27+ 候选)

| Controller | LoC | 包 | 备注 |
|------------|-----|----|------|
| `AuthController` | 180 | auth | login + refresh + changePassword(关键路径) |
| `RoleAclController` | 243 | auth | 角色 + ACL 策略,大文件 |
| `CollectionController` | 519 | meta | **excluded**(大) |
| `WorkflowController` | 404 | workflow | **excluded**(大) |
| `WorkflowEngine` | — | workflow | **excluded**(核心引擎) |
| `WorkflowTemplateService` | — | workflow | **excluded** |

## 🔧 Git

```
35f6bce (HEAD) Week 26 A: 批量补 3 个 controller 测试 + 抬红线大跃升
8275e25       docs: WEEK_25_HANDOFF + CHANGELOG Week 25(3 简单 controller 收尾)
670f30e       Week 25 A: 批量补 3 个简单 controller 测试 + 抬红线
```

## 🔁 重启套路(沿用)

```bash
for pid in $(pgrep -f 'java.*NocoBase|spring-boot:run'); do kill -9 $pid; done
sleep 8; ss -tlnp | grep 8080 || echo 'port free'
cd /home/who/multistack-project/backend-java
nohup mvn spring-boot:run > /tmp/nocobase-java.log 2>&1 & disown
for i in $(seq 1 30); do sleep 5; curl -sf http://localhost:8080/api/health && break; done
```

## 🎯 下一周选方向(Week 27)

A. **继续批量补 controller**:
   - `AuthController`(180 行,login/refresh/changePassword)— 关键路径,推荐
   - `RoleAclController`(243 行,角色 CRUD)
   - 估时:1-2 天

B. **挑战 WorkflowController**(404 行,已 exclude):
   - 需先拆出可测子集
   - 估时:3-5 天

C. **开始 4 层整合方案 Layer 1**(App 容器):
   - 加 application 表 + ApplicationService + 5 endpoints
   - collection/form/view/workflow 加 application_id FK
   - 估时:1 周

D. **Codecov 集成**(动态 badge):
   - 估时:半天

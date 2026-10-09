# PHASE95 返工任务书：让鉴权真正生效（🔒-SaaS-P0-R）

> 返工依据：PHASE95 Round 1 交付审计（2026-10-09）
> 一句话：**T2 加的 11 个 `@PreAuthorize` 完全是死注解，T3 的架构测试恒真，T4 只做了一半。**
> T1（登录响应 roles）已通过，不在本次返工范围（仅收尾两个小瑕疵）。

---

## §1 为什么必须返工（三条硬伤）

### 1.1 `@PreAuthorize` 根本不会执行

`config/SecurityConfig.java:26-27` 只有 `@Configuration`，**全仓库搜不到 `@EnableMethodSecurity` / `@EnableGlobalMethodSecurity`（0 匹配）**。
Spring Boot 3 + Spring Security 6 只自动提供 `@EnableWebSecurity`，**方法级安全必须显式开启**。

后果有两层：
- T2 新增的 11 个 `@PreAuthorize("hasRole('ADMIN')")` **一个都不生效**，端点仍裸奔
- **历史上** `TenantQuotaController:44,56`、`AuditController:27`、`UserDataComplianceController:46,70`、`UserTenantController:47` 的 `@PreAuthorize` **从来没生效过**

### 1.2 就算开启，也会立刻变成另一个事故

```java
// auth/JwtAuthFilter.java:55 —— 所有用户的 authority 恒为 ROLE_USER
var auth = new UsernamePasswordAuthenticationToken(principal, null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
```
`hasRole('ADMIN')` 需要 `ROLE_ADMIN` → **真管理员也会被 403**，管理功能上线即全挂。
T1 只改了登录**响应体**的 roles 字段，没有把角色接进 JWT / SecurityContext —— **T1 与 T2 之间没有衔接**。

### 1.3 T3 的架构测试是恒真的（三重）

`backend-java/docs/controller-authorization-baseline.txt` 只有 **135 字节 = 3 行注释，0 条数据**：

1. `ControllerAuthorizationCoverageTest.java:43-55` —— 首次运行自动生成基线后**直接 `return`**（必然 PASS）
2. 基线为空说明扫描结果 `missing` 为空：`METHOD_DEF` 正则（`:30-31`）`\b(public|protected)\s+\w+\s+(\w+)\s*\(` **匹配不了泛型返回类型**（`ResponseEntity<Map<...>>` 的 `<` 让 `\w+` 断掉），项目里绝大多数方法都是这种写法 → 大面积漏检
3. 只扫 `POST/PUT/DELETE/PATCH`（`:28-29`），**GET 越权读取完全不检查** —— 而 `GET /api/admin/users` 正是 P0-1 最典型的越权口

Java 1412 = 1411 + 1，这个"架构测试"只有 1 个用例且恒真。

## §2 Round 1 逐项判定

| 项 | 判定 | 依据 |
|---|---|---|
| T1 角色模型落地 | ✅ 通过 | `AuthController.java:90-97` 硬编码已移除，改查 `userRoleRepository` + `roleRepository` |
| T2 管理端点鉴权收口 | ❌ **无效** | 无 `@EnableMethodSecurity`；authority 恒为 `ROLE_USER`；未贴 HTTP 状态码 |
| T3 Controller 鉴权覆盖测试 | ❌ **恒真** | 基线空、自动生成即通过、正则漏检泛型、不查 GET |
| T4 actuator/Swagger 收口 | ⚠️ **半完成** | `SecurityConfig:84-85` 区分 ✅；`application.yml:95` `show-details: always` 未改 ❌；Swagger 生产收口无证据 ❌ |

## §3 六项返工任务

### R1（P0）让方法安全真正生效 —— 本次返工核心

1. `SecurityConfig` 增加 `@EnableMethodSecurity`（Spring Security 6 写法）
2. **角色 → authority 真正映射**，二选一（选一个并说明理由）：
   - **方案 A（推荐）**：JWT 里带 `roles` claim；`JwtService.issueAccessToken` 签发时写入，`JwtAuthFilter` 解析后映射为 `SimpleGrantedAuthority`
   - **方案 B**：`JwtAuthFilter` 内查库取角色（注意登录/高频请求的性能，需缓存）
3. **统一前缀与大小写**：项目里角色名可能是 `admin`（小写，`RoleEntity.name`），而 `hasRole('ADMIN')` 要求 authority 为 `ROLE_ADMIN`。必须明确映射规则并写测试锁定（否则要么全 403、要么全放行）
4. 保留 `ROLE_USER` 作为默认 authority

**验收（必须两者都成立，缺一不可）**：
```bash
# 管理员：200
curl -s -o /dev/null -w 'ADMIN->%{http_code}\n' -H "Authorization: Bearer <管理员token>" \
  localhost:8080/api/admin/users
# 普通用户：403
curl -s -o /dev/null -w 'USER->%{http_code}\n' -H "Authorization: Bearer <普通用户token>" \
  localhost:8080/api/admin/users
# 期望：ADMIN->200  USER->403
```
> **只跑单元测试不算验证**。必须贴真实 HTTP 状态码。

### R2（P0）影响面全量核对 —— 防止"开了注解全员 403"

方法安全一旦生效，所有已加 `@PreAuthorize` 的端点会同时开始真正校验：

- 新增的 11 个：`UserAdminController:44,51,59,72,86,99,113,126,139`、`JwtKeyRotationController:41,53`
- 历史的 5 处：`TenantQuotaController:44,56`、`AuditController:27`、`UserDataComplianceController:46,70`、`UserTenantController:47`

逐个用**管理员 + 普通用户**两种 token 打一遍，输出状态码矩阵（16 个端点 × 2 = 32 个结果）。
任何"管理员也 403"或"普通用户 200"都是 FAIL。

**同时检查**：业务代码里是否有依赖"鉴权不生效"才能跑通的既有测试/流程（开启后可能大面积 403），逐个修。

### R3（P0）重写架构测试 —— 必须能失败

删掉 `ControllerAuthorizationCoverageTest.java` 的"自动生成基线即通过"分支（`:43-55`），改为：

1. **正则支持泛型返回类型**：
   ```java
   // 不能再用 \w+ 匹配返回类型（撞上 < 就断）
   // 建议：先取方法签名第一个 ( 之前的部分，再取最后一个空格后的 token 作为方法名
   ```
2. **GET 也纳入检查**（越权读取同样致命），可读端点允许"有注解 或 显式白名单"
3. **白名单必须内联在测试里**（不是自动生成的文件），每条带理由
4. **先让它红**：第一版跑出来必须列出真实 missing 数量（预计几十条），把数字写进交付
5. 再逐条：加注解 或 进白名单（写理由）
6. **验证可失败性**：随手删掉某个 `@PreAuthorize` 再跑，必须 FAIL

### R4（P0）actuator 与 Swagger 收口补齐

- `application.yml:95` `show-details: always` → 改 `when_authorized`（匿名 `/actuator/health` 不得暴露连接池/磁盘细节）
- Swagger：生产 profile 关闭或加鉴权（`SecurityConfig:95-100` 当前是放行），给出 dev/prod 的实际状态码

```bash
curl -s localhost:8080/actuator/health | grep -c "db\|disk\|redis"   # 匿名访问期望 0 处细节
```

### R5（P1）T1 的两个小瑕疵

`AuthController.java:91-97`：
- `roleRepository.findById(...)` 不带 tenantId → 补租户维度（或复用 `RoleRepository` 已有的递归链查询）
- 逐角色 `findById` 是 N+1 → 改为批量/一次查询

### R6（P0）产出可重复执行的 HTTP 验收脚本

`scripts/admin-authz-e2e-verify.py`：自动完成"管理员 200 / 普通用户 403 / 匿名 401"三类断言，覆盖 R1+R2 的 16 个端点，一键输出 PASS/FAIL。

```bash
python3 scripts/admin-authz-e2e-verify.py   # 期望全 PASS
# 验证可失败性：把某个 @PreAuthorize 注掉再跑，必须 FAIL
```

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（开启方法安全后既有测试可能大面积 403，必须逐个修，不许删测试） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/admin-authz-e2e-verify.py` | 全 PASS（新增） |

## §5 红线（违反即打回）

1. **必须有真实 HTTP 证据** —— "我加了注解"不算，"单元测试过了"不算；必须是 `curl`/脚本打出来的 403/200
2. **禁止恒真断言** —— 尤其禁止"自动生成基线即通过"这类写法（本项目第二次踩）；写完随手改错一次确认会红
3. **禁止为了跑绿删测试或放宽规则** —— 开启方法安全后测试大面积 403 是**预期内的**，要逐个修业务，不是关掉功能
4. **禁止只验证"普通用户被拦"** —— 必须同时验证"管理员能访问"，否则等于把功能封死
5. **禁止修改已应用的 Flyway 迁移**
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. R1：`@EnableMethodSecurity` 的 `文件:行号` + 角色映射方案（A/B）与理由 + **管理员 200 / 普通用户 403 的实际输出**
2. R2：16 个端点 × 2 种 token 的**状态码矩阵**（32 个结果，逐个贴）
3. R2：开启方法安全后新增失败的既有测试清单 + 逐个修法
4. R3：重写后的测试 + **首版跑出的 missing 真实数量** + "删注解会 FAIL"的验证输出
5. R4：`/actuator/health` 匿名访问不再暴露细节的证据 + Swagger dev/prod 状态码
6. R5：`文件:行号` 与改动说明
7. R6：`scripts/admin-authz-e2e-verify.py` + 全 PASS + "注掉注解会 FAIL"的验证
8. 门禁七项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 角色体系：`auth/RoleEntity.java`、`auth/RoleRepository.java`（已有递归祖先链查询，`:34-70`）
- ACL：`auth/AclEnforcer.java:72,249`（已支持角色继承）—— 鉴权是否可以直接复用它，值得先评估
- JWT：`auth/JwtService.java`（`issueAccessToken` / `parseAccessToken`）
- 过滤器：`auth/JwtAuthFilter.java`（authority 就在 `:55`）
- 安全配置：`config/SecurityConfig.java:51+`（`securityFilterChain`）
- 验证脚本风格：`scripts/backup-e2e-verify.py`、`scripts/user-data-e2e-verify.py`

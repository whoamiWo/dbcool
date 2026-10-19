# PHASE95 任务书：授权与多租户隔离基线（🔒-SaaS-P0）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §3.1（P0-1 授权层近乎不存在）、§3.2（P0-2 多租户隔离事实上未生效）
> 上线目标：**对外多租户 SaaS** —— 这两项是"租户数据会不会被别人看到/改掉"的生死线，必须最先清。

---

## §1 为什么做这个（三条理由）

### 1.1 当前状态是"能进就能改"

`AuthController.java:103` 在**登录成功响应**里硬编码 `"roles", new String[]{"admin"}`（同文件 `:206` 的另一处用的是真 `roleNames`，说明主登录路径就是假的）。
配合 `/api/admin/users/**` 与 `/api/admin/jwt-keys` 零权限注解，等价于：**任何一个能登录的人，都能改所有人的账号，还能轮换 JWT 签名密钥伪造任意身份**。

对外售卖时这不是"风险"，这是**事故**。

### 1.2 多租户隔离是"以为有、实际没有"

上一版 `LAUNCH_READINESS_REPORT.md` 称"多租户已实现 Schema 级隔离，架构性风险等级下调"——**该结论已被证伪**：

- `META-INF/services/org.hibernate.boot.spi.Integrator` 文件名错误：`TenantServiceIntegrator.java:8,20` 引用的接口是 `org.hibernate.integrator.spi.Integrator`
- `application.yml:35,39-40` 的 `multi_tenant_connection_provider` / `multi_tenant_identifier_resolver` 两行**被注释掉**

→ Schema 隔离从未生效，隔离全靠应用层手写 `tenantId`，而手写已经漏了至少 11 处。

### 1.3 有做对的范式可以照抄，不是从零设计

`AttachmentController.java:99,169`（`storageKey.startsWith(tenantId + "/")`）、`AuditController.java:35-36`、`ProjectService.java:135-138` 是正确的写法。
本批是**把正确写法推广到漏网点**，不是重新发明权限模型。

## §2 现状（实测，不是照抄文档）

| 项 | 实测 |
|---|---|
| 登录响应角色 | ❌ `AuthController.java:103` 硬编码 `roles=["admin"]` |
| `/api/admin/users/**` | ❌ `UserAdminController.java:42-129` **9 个端点**零权限注解 |
| `/api/admin/jwt-keys` | ❌ `JwtKeyRotationController.java:39-51` 零权限注解（GET 快照 + POST rotate） |
| `@PreAuthorize` 覆盖面 | ❌ 仅 **8 个文件**，而 Controller 文件有 **53 个** |
| `/actuator/**` | ❌ `SecurityConfig.java:83` `permitAll` + `show-details: always` |
| Swagger | ❌ 生产 profile 未关闭且匿名放行（`application.yml:117-128`） |
| CORS 配置 | ❌ `app.cors.allowed-origins` 定义但**从未被读取**（`SecurityConfig.java:137-152` 硬编码） |
| Hibernate Schema 隔离 | ❌ SPI 文件名错 + provider/resolver 被注释 → **未生效** |
| 跨租户越权点 | ❌ 已实锤 **11 处**（见 T6 清单） |
| 正面参照 | ✅ `AttachmentController:99,169`、`AuditController:35-36`、`ProjectService:135-138` |

## §3 八项任务

### T1（P0）角色模型落地 + 修掉硬编码 admin

- `AuthController.java:103` 改为从**真实角色**取（与 `:206` 的 `roleNames` 同源），不得硬编码
- 确认角色来源表/关联存在；若角色体系本身未落地，则**先落地角色模型**再改这里（不许用硬编码糊过去）
- 删除/收敛"所有登录用户都是 admin"的任何兜底分支

**验收（必须会失败）**：
```bash
# 用非管理员账号登录，响应里不得出现 admin
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"<非管理员>","password":"<pwd>"}' | tee /tmp/login.json
grep -q '"roles":\["admin"\]' /tmp/login.json && echo FAIL || echo PASS
```

### T2（P0）管理端点鉴权收口

- `UserAdminController.java` 全部 9 个端点：加 `@PreAuthorize`（如 `hasRole('ADMIN')` 或项目既有权限表达式）
- `JwtKeyRotationController.java:39-51`：GET 快照与 POST rotate 必须鉴权（**轮换签名密钥至少要是平台管理员**）
- 全仓再搜一遍 `"/api/admin` 路径，逐个确认有鉴权

**验收**：
```bash
# 用普通用户 token 逐个打，必须 403（当前是 200）
for p in /api/admin/users /api/admin/jwt-keys; do
  echo -n "$p -> "; curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer <普通用户token>" localhost:8080$p
done
# 期望两行都是 403
```

### T3（P0）全量 Controller 方法级鉴权补齐

53 个 Controller 只有 8 个文件有 `@PreAuthorize`。逐个补齐不现实也不必要，采用**可测量的策略**：

1. 建立权限矩阵文档（哪个 Controller 需要什么角色/权限）
2. 对**涉及跨租户数据、写操作、管理语义**的端点优先补齐（读操作可放宽但要显式声明）
3. **新增一个架构测试**：扫描所有 `@RestController`/`@Controller` 的 public 方法，断言每个方法要么有 `@PreAuthorize`/`@Secured`，要么在**显式白名单**里（白名单要写清理由）。白名单外缺失即 FAIL

**验收**：
```bash
cd backend-java && mvn -o test -Dtest=ControllerAuthorizationCoverageTest
# 期望 PASS；随手把某个 Controller 的注解删掉再跑，必须 FAIL（证明断言不是恒真）
```

### T4（P0）actuator 与 Swagger 生产收口

- `/actuator/**` 不再 `permitAll`：`health` 可匿名（且去掉 `show-details: always`），`metrics`/`prometheus`/`info` 必须鉴权
- Swagger/OpenAPI 在生产 profile 关闭（或至少加鉴权）
- `SecurityConfig.java:137-152` 改为**真正消费** `app.cors.allowed-origins`；`setAllowCredentials(true)` 不得与 `*` 混用

**验收**：
```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/metrics   # 期望 401/403
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/health    # 期望 200（且不暴露细节）
curl -s -o /dev/null -w '%{http_code}\n' -D- localhost:8080/swagger-ui/index.html | head -1  # 生产 profile 期望 401/403/404
```

### T5（P0）Hibernate Schema 多租户：要么真接线，要么明确放弃并改为应用层强制

二选一，**必须给出决策依据与证据**（不许含糊）：

- **方案 A（推荐先试）**：修 `META-INF/services` 文件名为 `org.hibernate.integrator.spi.Integrator`，打开 `application.yml:39-40` 的 provider/resolver，修 `SchemaTenantConnectionProvider` 的无参构造器隐患
  - 验收：不同租户请求下 `SHOW search_path` 必须落到不同 schema；跨租户查询拿不到对方数据
- **方案 B**：若 A 的风险/回归面过大，则**明确放弃** Schema 隔离，改为应用层强制：统一 `findByIdAndTenantId`，并用架构测试禁止出现"无 tenant 的 findById"（见 T6/T7），同时在 `application.yml` 里把 `multiTenancy` 关掉避免误导

> 无论选哪个，**必须在交付里说明选了哪个、为什么、以及证据**。历史上"以为有隔离"正是这次事故的来源。

### T6（P0）跨租户越权全量修复（11 处，逐点修）

| # | 位置 | 修法 |
|---|---|---|
| 1 | `auth/UserAdminService.java:38-40` | `listAll()` 按当前租户过滤（或明确仅平台管理员可见并鉴权） |
| 2 | `auth/UserAdminService.java:42-45` | 改 `findByIdAndTenantId`（参照 PHASE93 的 `UserDataErasureService.requireUserInTenant`） |
| 3 | `auth/UserAdminService.java:98-104` | 用户与角色**双方**都要校验同租户 |
| 4 | `bi/BiReportService.java:64-65` | 改 `findByIdAndTenantId`，且 id 不存在时**抛 404**，不许 `orElseGet` 造空实体 |
| 5 | `project/ProjectBoardController.java:129-131` | 删除前比对 `tenantId` |
| 6 | `project/ProjectBoardController.java:392-394` | 同上 |
| 7 | `im/MessageService.java:231-234` | `mustGet` 增加租户校验（不能只校频道成员） |
| 8 | `wiki/WikiPageService.java:80-83` + `WikiPageRepository.java:20,32,38` | 三个无 tenant 查询口补齐或删除 |
| 9 | `wiki/KnowledgeBaseService.java:56-59`、`WikiCategoryService.java:55-58`、`WikiTemplateService.java:67-70` | 改带租户校验的 get |
| 10 | `ai/AiConversationEntityRepository.java:17-24` | 增加 `tenantId` 维度查询 |
| 11 | `im/PinService.java:44`、`HuddleService.java:222`、`MessageService.java:75-77` | 补租户校验 |

顺带清掉 `automation/AutomationRuleService.java:150-153` 的无租户 `getRule(UUID)` 重载（误用通道）。

### T7（P0）越权回归测试 —— 本批验收核心

- 为 T6 的每个点写一个**跨租户**用例：用租户 A 的 token 操作租户 B 的资源，断言 **403/404**（不得 200）
- 测试用例必须**可失败**：先把校验代码注掉跑一次，确认会红，再恢复
- 汇总为一个可重复执行的脚本（参照 `scripts/user-data-e2e-verify.py` 的风格），输出 PASS/FAIL

**验收**：
```bash
python3 scripts/tenant-isolation-e2e-verify.py   # 期望全 PASS
```

### T8（P1）未接线项清理

- `ai/AiAssistantService.java:36` 的 `new RestTemplate()` 改为注入 `RestTemplateConfig` 的 Bean（要有超时/连接池）
- `config/SecurityConfig.java:131` 的 `new MdcFilter()` 改为 Spring 管理
- `im/SlashCommandRegistry.java:55-62` 的 `new` 手工装配改为 Spring Bean（若 PHASE96 会重写，则此处只登记不重复改）

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（0 failures / 0 errors） |
| `cd frontend && npm run test:run` | **> 382**（前端无改动就如实写 382） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29（不得回退） |

> 基线来源：`PHASE94_GLM53_BACKUP_TASKS.md` §4。开跑前先实测一次取真值。

## §5 红线（违反即打回）

1. **禁止恒真断言** —— 每个验收必须能失败。写完测试后**随手改错一次**，确认会红
2. **禁止静默成功** —— 校验失败要抛 403/404，不许返回空列表/默认值冒充成功
3. **禁止用"关闭端点/删功能"来过门禁** —— 端点要么鉴权后保留，要么明确下线并说明理由
4. **禁止修改已应用的 Flyway 迁移**（要改就加新的）
5. **禁止为了跑绿而删除既有测试** —— 测试变红要查原因，不是删掉
6. **禁止把"我加了注解"当作"鉴权生效"** —— 必须有真实的 HTTP 请求证据（403 而不是 200）
7. T5 的 A/B 决策必须写出依据与证据，不许跳过

## §6 交付清单

1. `AuthController.java:103` 修复前后对比（登录响应 roles 的真实来源）
2. `/api/admin/users/**`、`/api/admin/jwt-keys` 用普通 token 访问的**实际 HTTP 状态码**（403，不是 200）
3. Controller 鉴权覆盖架构测试 + "删掉注解会 FAIL"的验证输出
4. actuator/Swagger 收口后的实际状态码
5. T5 的 A/B 决策、理由与证据（`SHOW search_path` 或架构测试输出）
6. T6 的 11 处修复清单（每处 `文件:行号`）
7. `scripts/tenant-isolation-e2e-verify.py` + 全 PASS 输出
8. 门禁六项实测数字 + commit hash + `git status`（必须干净且已推送）

## §7 可复用（别重造）

- 租户归属校验范式：`backend-java/src/main/java/com/nocobase/compliance/UserDataErasureService.java`（`requireUserInTenant`，PHASE93 已落地）
- 正确端点范式：`AttachmentController.java:99,169`、`AuditController:35-36`、`ProjectService.java:135-138`
- 验证脚本风格：`scripts/user-data-e2e-verify.py`、`scripts/quota-e2e-verify.py`、`scripts/backup-e2e-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`
- 重建提醒：改了 `application.yml`/compose 环境变量后**必须重建容器**才生效

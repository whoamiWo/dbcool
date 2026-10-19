# PHASE95 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE95_GLM53_AUTHZ_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE95：授权与多租户隔离基线（🔒-SaaS-P0）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录
- **上线目标：对外多租户 SaaS**（租户间必须严格隔离）—— 本批是它的生死线

### 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑单个测试 | `cd backend-java && mvn -o test -Dtest=类名` |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`（**必须加 `PYTHONPATH=src`**） |
| **一键冒烟（真调后端）** | `python3 scripts/smoke.py`（11 条链路） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络**；不要用在线 Dockerfile，会超时） |
| 重启后端 | `docker compose up -d --no-build backend-java`（**改了 compose 环境变量必须重建容器**，否则不生效） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| **新增端点后出现 401** | 多半是没重新打包部署 —— 先 `mvn -o clean package` + 重建镜像 + 重启容器再验证 |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| 判断某配置是否配了 | 同时查 ① `application.yml` ② `docker-compose.yml` environment ③ `.env` |

---

## §1 为什么做这一项

来自 `SAAS_LAUNCH_ASSESSMENT.md` §3.1 / §3.2（2026-10-09 实测）：

**当前状态是"能进就能改"**：
- `backend-java/src/main/java/com/nocobase/auth/AuthController.java:103` 在**登录成功响应**里硬编码 `"roles", new String[]{"admin"}`（同一文件 `:206` 的另一处用的是真 `roleNames`，说明主登录路径就是假的）
- `auth/UserAdminController.java:42-129` 的 `/api/admin/users/**` **9 个端点零权限注解**：任何已登录用户可增删改查全租户用户、改密、删号、赋角色
- `auth/JwtKeyRotationController.java:39-51` 的 `/api/admin/jwt-keys` 零鉴权：**任意已认证用户可读并轮换 JWT 签名密钥**，等于可伪造任意身份

**多租户隔离是"以为有、实际没有"**：
- `backend-java/src/main/resources/META-INF/services/org.hibernate.boot.spi.Integrator` 文件名写错（应为 `org.hibernate.integrator.spi.Integrator`，见 `config/TenantServiceIntegrator.java:8,20`）
- `application.yml:35,39-40` 的 `multi_tenant_connection_provider` / `multi_tenant_identifier_resolver` 两行**被注释掉**
- 上一版报告称"Schema 级隔离已生效、风险下调"——**该结论已被证伪**。隔离全靠应用层手写 `tenantId`，而手写已漏了 11 处

对外售卖时，这类问题不是"风险"，是**事故**。

## §2 现状（实测）

| 项 | 实测 |
|---|---|
| 登录响应角色 | ❌ `AuthController.java:103` 硬编码 `roles=["admin"]` |
| `/api/admin/users/**` | ❌ 9 个端点零权限注解 |
| `/api/admin/jwt-keys` | ❌ 零鉴权（含 POST rotate） |
| `@PreAuthorize` 覆盖面 | ❌ 仅 8 个文件 / 53 个 Controller |
| `/actuator/**` | ❌ `permitAll` + `show-details: always` |
| Swagger | ❌ 生产 profile 未关闭且匿名放行 |
| CORS 配置 | ❌ `app.cors.allowed-origins` 定义但从未被读取 |
| Hibernate Schema 隔离 | ❌ SPI 文件名错 + provider/resolver 被注释 → 未生效 |
| 跨租户越权点 | ❌ 已实锤 11 处 |
| 正面参照 | ✅ `AttachmentController:99,169`、`AuditController:35-36`、`ProjectService:135-138` |

## §3 八项任务

### T1（P0）角色模型落地 + 修掉硬编码 admin

`AuthController.java:103` 改为从**真实角色**取（与 `:206` 的 `roleNames` 同源）。若角色体系本身未落地，先落地角色模型，不许用硬编码糊过去。

验收（必须会失败）：
```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"<非管理员>","password":"<pwd>"}' | tee /tmp/login.json
grep -q '"roles":\["admin"\]' /tmp/login.json && echo FAIL || echo PASS
```

### T2（P0）管理端点鉴权收口

`UserAdminController` 全部 9 个端点 + `JwtKeyRotationController` 的 GET/POST 加 `@PreAuthorize`（轮换签名密钥至少平台管理员）。

```bash
for p in /api/admin/users /api/admin/jwt-keys; do
  echo -n "$p -> "; curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <普通用户token>" localhost:8080$p
done
# 期望两行 403（当前 200）
```

### T3（P0）全量 Controller 方法级鉴权补齐

53 个 Controller 只 8 个有 `@PreAuthorize`。策略：建立权限矩阵 → 跨租户数据/写操作/管理语义优先 → **新增架构测试**扫描所有 Controller public 方法，断言有 `@PreAuthorize`/`@Secured` 或在**显式白名单**（白名单要写理由），缺失即 FAIL。

```bash
cd backend-java && mvn -o test -Dtest=ControllerAuthorizationCoverageTest
# 写完把某个 Controller 的注解删掉再跑，必须 FAIL（证明不是恒真）
```

### T4（P0）actuator 与 Swagger 生产收口

`/actuator/metrics|prometheus|info` 必须鉴权；`health` 可匿名但去掉 `show-details: always`；Swagger 生产关闭或鉴权；CORS 改为真正消费 `app.cors.allowed-origins`，`setAllowCredentials(true)` 不得与 `*` 混用。

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/metrics   # 期望 401/403
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/health    # 期望 200 且不暴露细节
```

### T5（P0）Hibernate Schema 多租户：要么真接线，要么明确放弃并改应用层强制

- **方案 A（先试）**：修 SPI 文件名为 `org.hibernate.integrator.spi.Integrator`，打开 `application.yml:39-40`，修 `SchemaTenantConnectionProvider` 无参构造器隐患。验收：不同租户请求下 `SHOW search_path` 落不同 schema，跨租户查不到对方数据。
- **方案 B**：若 A 回归面过大，**明确放弃** Schema 隔离，改应用层强制（统一 `findByIdAndTenantId` + 架构测试禁止无 tenant 的 `findById`），并把 `multiTenancy` 关掉避免误导。

> 无论选哪个，**必须写出选了哪个、为什么、证据是什么**。历史上"以为有隔离"正是这次事故的来源。

### T6（P0）跨租户越权全量修复（11 处）

| # | 位置 | 修法 |
|---|---|---|
| 1 | `auth/UserAdminService.java:38-40` | `listAll()` 按租户过滤或仅平台管理员+鉴权 |
| 2 | `auth/UserAdminService.java:42-45` | 改 `findByIdAndTenantId` |
| 3 | `auth/UserAdminService.java:98-104` | 用户与角色**双方**校验同租户 |
| 4 | `bi/BiReportService.java:64-65` | 改 `findByIdAndTenantId`；id 不存在抛 404，**不许** `orElseGet` 造空实体 |
| 5 | `project/ProjectBoardController.java:129-131` | 删前比对 tenantId |
| 6 | `project/ProjectBoardController.java:392-394` | 同上 |
| 7 | `im/MessageService.java:231-234` | `mustGet` 加租户校验（不能只校频道成员） |
| 8 | `wiki/WikiPageService.java:80-83` + `WikiPageRepository.java:20,32,38` | 三个无 tenant 查询口补齐或删除 |
| 9 | `wiki/KnowledgeBaseService.java:56-59`、`WikiCategoryService.java:55-58`、`WikiTemplateService.java:67-70` | 改带租户校验的 get |
| 10 | `ai/AiConversationEntityRepository.java:17-24` | 增加 tenantId 维度 |
| 11 | `im/PinService.java:44`、`HuddleService.java:222`、`MessageService.java:75-77` | 补租户校验 |

顺带清掉 `automation/AutomationRuleService.java:150-153` 的无租户 `getRule(UUID)` 重载。

### T7（P0）越权回归测试 —— 本批验收核心

为 T6 每个点写跨租户用例：租户 A 的 token 操作租户 B 的资源，断言 **403/404**（不得 200）。
每个用例必须**先注掉校验跑一次确认会红**，再恢复。汇总为 `scripts/tenant-isolation-e2e-verify.py`（参照 `scripts/user-data-e2e-verify.py` 风格）。

```bash
python3 scripts/tenant-isolation-e2e-verify.py   # 期望全 PASS
```

### T8（P1）未接线项清理

`ai/AiAssistantService.java:36` 的 `new RestTemplate()` 改注入 Bean（要超时/连接池）；`config/SecurityConfig.java:131` 的 `new MdcFilter()` 改 Spring 管理；`im/SlashCommandRegistry.java:55-62` 的 `new` 手工装配（若 PHASE96 会重写则只登记）。

## §4 门禁基线（跑完必须全部达标）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（0 failures / 0 errors） |
| `cd frontend && npm run test:run` | **> 382**（前端无改动就如实写 382） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29（不得回退） |

（基线来源 `PHASE94_GLM53_BACKUP_TASKS.md` §4；开跑前先实测一次取真值。）

## §5 红线（违反即打回）

1. **禁止恒真断言** —— 每个验收必须能失败；写完测试随手改错一次确认会红
2. **禁止静默成功** —— 校验失败抛 403/404，不许返回空列表/默认值冒充成功
3. **禁止用"关闭端点/删功能"过门禁** —— 要么鉴权后保留，要么明确下线并说明理由
4. **禁止修改已应用的 Flyway 迁移**（要改就加新的）
5. **禁止为了跑绿删既有测试** —— 红了要查原因
6. **禁止把"加了注解"当作"鉴权生效"** —— 必须有真实 HTTP 证据（403，不是 200）
7. T5 的 A/B 决策必须写出依据与证据，不许跳过

## §6 交付清单

1. `AuthController.java:103` 修复前后对比（roles 的真实来源）
2. `/api/admin/users/**`、`/api/admin/jwt-keys` 用普通 token 访问的**实际 HTTP 状态码**（403）
3. Controller 鉴权覆盖架构测试 + "删掉注解会 FAIL"的输出
4. actuator/Swagger 收口后实际状态码
5. T5 的 A/B 决策、理由与证据
6. T6 的 11 处修复清单（每处 `文件:行号`）
7. `scripts/tenant-isolation-e2e-verify.py` + 全 PASS 输出
8. 门禁六项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（明确列出未做项与原因，不得省略）

## §7 可复用（别重造）

- 租户归属校验范式：`compliance/UserDataErasureService.java` 的 `requireUserInTenant`（PHASE93 已落地）
- 正确端点范式：`AttachmentController.java:99,169`、`AuditController:35-36`、`ProjectService.java:135-138`
- 验证脚本风格：`scripts/user-data-e2e-verify.py`、`scripts/quota-e2e-verify.py`、`scripts/backup-e2e-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

-----END PROMPT-----

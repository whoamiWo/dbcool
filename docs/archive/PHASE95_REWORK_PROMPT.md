# PHASE95 返工提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE95_REWORK_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE95 返工：让鉴权真正生效（🔒-SaaS-P0-R）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ Spring Security 6 + PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录
- 上线目标：对外多租户 SaaS
- 本批是 **PHASE95 Round 1 的返工**：R1 交付的 T2/T3/T4 未通过审计，T1 已通过

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑单个测试 | `cd backend-java && mvn -o test -Dtest=类名` |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .` |
| 重启后端 | `docker compose up -d --no-build backend-java`（**改了配置必须重建容器**） |
| **新增端点/改鉴权后出现 401** | 多半是没重新打包部署 —— 先打包+重建+重启再验证 |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 一键冒烟 | `python3 scripts/smoke.py` |

---

## §1 为什么必须返工

### 1.1 `@PreAuthorize` 根本不会执行

`config/SecurityConfig.java:26-27` 只有 `@Configuration`，**全仓库搜不到 `@EnableMethodSecurity` / `@EnableGlobalMethodSecurity`（0 匹配）**。Spring Boot 3 + Security 6 只自动提供 `@EnableWebSecurity`，**方法级安全必须显式开启**。

后果两层：
- Round 1 新增的 11 个 `@PreAuthorize("hasRole('ADMIN')")` **一个都不生效**，端点仍裸奔
- **历史上** `TenantQuotaController:44,56`、`AuditController:27`、`UserDataComplianceController:46,70`、`UserTenantController:47` 的 `@PreAuthorize` **从来没生效过**

### 1.2 就算开启，也会立刻变成另一个事故

```java
// auth/JwtAuthFilter.java:55 —— 所有用户的 authority 恒为 ROLE_USER
var auth = new UsernamePasswordAuthenticationToken(principal, null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
```
`hasRole('ADMIN')` 需要 `ROLE_ADMIN` → **真管理员也会被 403**，管理功能上线即全挂。
Round 1 的 T1 只改了登录**响应体**的 roles 字段（`AuthController.java:90-97`），**没有把角色接进 JWT / SecurityContext** —— T1 与 T2 之间没有衔接。

### 1.3 T3 的架构测试是恒真的（三重）

`backend-java/docs/controller-authorization-baseline.txt` 只有 **135 字节 = 3 行注释，0 条数据**：

1. `ControllerAuthorizationCoverageTest.java:43-55` —— 首次运行自动生成基线后**直接 `return`**（必然 PASS）
2. 基线为空说明扫描结果 `missing` 为空：`METHOD_DEF` 正则（`:30-31`）`\b(public|protected)\s+\w+\s+(\w+)\s*\(` **匹配不了泛型返回类型**（`ResponseEntity<Map<...>>` 的 `<` 让 `\w+` 断掉）→ 大面积漏检
3. 只扫 `POST/PUT/DELETE/PATCH`（`:28-29`），**GET 越权读取完全不检查**

Java 1412 = 1411 + 1，这个"架构测试"只有 1 个用例且恒真。

### Round 1 判定

| 项 | 判定 |
|---|---|
| T1 角色模型落地 | ✅ 通过（`AuthController.java:90-97`） |
| T2 管理端点鉴权收口 | ❌ 无效 |
| T3 Controller 鉴权覆盖测试 | ❌ 恒真 |
| T4 actuator/Swagger 收口 | ⚠️ 半完成（`SecurityConfig:84-85` ✅，`application.yml:95` `show-details: always` ❌，Swagger ❌） |

## §2 任务

### R1（P0）让方法安全真正生效 —— 核心

1. `SecurityConfig` 增加 `@EnableMethodSecurity`（Security 6 写法）
2. **角色 → authority 真正映射**，二选一（说明理由）：
   - **方案 A（推荐）**：JWT 带 `roles` claim；`JwtService.issueAccessToken` 签发时写入，`JwtAuthFilter` 解析后映射 `SimpleGrantedAuthority`
   - **方案 B**：`JwtAuthFilter` 内查库取角色（注意性能，需缓存）
3. **统一前缀与大小写**：角色名可能是 `admin`（小写），而 `hasRole('ADMIN')` 要求 authority 为 `ROLE_ADMIN`。明确映射规则并**写测试锁定**
4. 保留 `ROLE_USER` 作为默认 authority

```bash
curl -s -o /dev/null -w 'ADMIN->%{http_code}\n' -H "Authorization: Bearer <管理员token>" localhost:8080/api/admin/users
curl -s -o /dev/null -w 'USER->%{http_code}\n'  -H "Authorization: Bearer <普通用户token>" localhost:8080/api/admin/users
# 期望 ADMIN->200  USER->403（两者都要，缺一不可）
```

### R2（P0）影响面全量核对 —— 防止"开了注解全员 403"

方法安全一旦生效，所有已加 `@PreAuthorize` 的端点会同时真正校验：

- 新增 11 个：`UserAdminController:44,51,59,72,86,99,113,126,139`、`JwtKeyRotationController:41,53`
- 历史 5 处：`TenantQuotaController:44,56`、`AuditController:27`、`UserDataComplianceController:46,70`、`UserTenantController:47`

逐个用**管理员 + 普通用户**两种 token 打一遍，输出**状态码矩阵**（16 端点 × 2 = 32 个结果）。
任何"管理员也 403"或"普通用户 200"都是 FAIL。

**同时检查**：是否有既有测试/流程依赖"鉴权不生效"才能跑通（开启后可能大面积 403），逐个修。

### R3（P0）重写架构测试 —— 必须能失败

删掉 `ControllerAuthorizationCoverageTest.java:43-55` 的"自动生成基线即通过"分支：

1. **正则支持泛型返回类型**（不能再用 `\w+` 匹配返回类型；建议取方法签名第一个 `(` 之前的部分，再取最后一个空格后的 token 作方法名）
2. **GET 也纳入检查**（越权读取同样致命）
3. **白名单内联在测试里**，每条带理由
4. **先让它红**：第一版必须跑出真实 missing 数量（预计几十条），把数字写进交付
5. 再逐条加注解或进白名单
6. **验证可失败性**：删掉某个 `@PreAuthorize` 再跑，必须 FAIL

### R4（P0）actuator 与 Swagger 收口补齐

`application.yml:95` `show-details: always` → `when_authorized`；Swagger 生产 profile 关闭或加鉴权。

```bash
curl -s localhost:8080/actuator/health | grep -c "db\|disk\|redis"   # 匿名访问期望 0 处细节
```

### R5（P1）T1 的两个小瑕疵

`AuthController.java:91-97`：`roleRepository.findById(...)` 不带 tenantId → 补租户维度；逐角色 `findById` 是 N+1 → 改批量查询。

### R6（P0）产出 HTTP 验收脚本

`scripts/admin-authz-e2e-verify.py`：自动完成"管理员 200 / 普通用户 403 / 匿名 401"三类断言，覆盖 R1+R2 的 16 个端点。

```bash
python3 scripts/admin-authz-e2e-verify.py   # 期望全 PASS
# 验证可失败性：把某个 @PreAuthorize 注掉再跑，必须 FAIL
```

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（开启方法安全后既有测试可能大面积 403，必须逐个修，不许删测试） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/admin-authz-e2e-verify.py` | 全 PASS（新增） |

## §4 红线（违反即打回）

1. **必须有真实 HTTP 证据** —— "加了注解"不算，"单测过了"不算；必须是 curl/脚本打出来的 403/200
2. **禁止恒真断言** —— 尤其禁止"自动生成基线即通过"（本项目第二次踩）；写完随手改错一次确认会红
3. **禁止为了跑绿删测试或放宽规则** —— 开启方法安全后测试大面积 403 是**预期内的**，要修业务不是关功能
4. **禁止只验证"普通用户被拦"** —— 必须同时验证"管理员能访问"
5. **禁止修改已应用的 Flyway 迁移**
6. 回报数字必须能在交付物里找到**产出它的代码行**

## §5 交付清单

1. R1 `@EnableMethodSecurity` 的 `文件:行号` + 角色映射方案（A/B）与理由 + **管理员 200 / 普通用户 403 实际输出**
2. R2 16 端点 × 2 种 token 的**状态码矩阵**（32 个结果逐个贴）
3. R2 开启后新增失败的既有测试清单 + 逐个修法
4. R3 重写后的测试 + **首版 missing 真实数量** + "删注解会 FAIL"验证
5. R4 `/actuator/health` 匿名不再暴露细节 + Swagger dev/prod 状态码
6. R5 `文件:行号` 与改动说明
7. R6 脚本 + 全 PASS + "注掉注解会 FAIL"验证
8. 门禁七项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §6 可复用

- 角色体系：`auth/RoleEntity.java`、`auth/RoleRepository.java:34-70`（已有递归祖先链查询）
- ACL：`auth/AclEnforcer.java:72,249`（已支持角色继承）—— 先评估能否直接复用
- JWT：`auth/JwtService.java`（`issueAccessToken` / `parseAccessToken`）
- 过滤器：`auth/JwtAuthFilter.java`（authority 在 `:55`）
- 安全配置：`config/SecurityConfig.java:51+`
- 验证脚本风格：`scripts/backup-e2e-verify.py`、`scripts/user-data-e2e-verify.py`

-----END PROMPT-----

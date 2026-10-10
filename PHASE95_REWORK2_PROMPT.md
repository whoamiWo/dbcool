# PHASE95 返工 2 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE95_REWORK2_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE95 返工 2：把"拦住了谁"真正验证出来（🔒-SaaS-P0-R2）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ Spring Security 6 + PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录
- 上线目标：对外多租户 SaaS
- 本批是 **PHASE95 第二次返工**。Round 2（commit `7af183e`）的方法安全已真正生效，但验收与白名单有问题

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .` |
| 重启后端 | `docker compose up -d --no-build backend-java`（**改了配置必须重建容器**） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 一键冒烟 | `python3 scripts/smoke.py` |
| 备份演练 | `python3 scripts/backup-e2e-verify.py` |

---

## §1 为什么必须再返工

### 1.1 最核心的断言被静默跳过

`scripts/admin-authz-e2e-verify.py:63` 用 `login("user", "user1234")` 取普通用户 token，
而 **`user1234` 全仓库只出现在这一行**（种子数据里没有这个账号）→ 登录必然失败 → `user_token = None`：

```python
else:
    user_ok = None                                            # :126
passed = no_auth_ok and admin_ok and (user_ok in (True, None))  # :128  None 也算 PASS
```

`None in (True, None)` == `True` → **13 个端点的"普通用户必须 403"全部跳过且计入 PASS**。

这批的整个目的就是证明"非管理员被拦住"，结果唯一没验证的正是它 —— 而且失败时不报错、不告警。

### 1.2 白名单把"需要鉴权"的端点合法化了

`ControllerAuthorizationCoverageTest.java:35+` 的白名单里，大量**理由就写着"需 ADMIN"**的端点被放进"无需 `@PreAuthorize`"的白名单：

```
"wiki/WikiController.java:101 -> createKb (reason: 知识库创建，需 ADMIN)"
"wiki/WikiController.java:165 -> deleteKb (reason: 知识库删除，需 ADMIN)"
"wiki/WikiController.java:442 -> deleteBlock (reason: 区块删除，需 ADMIN)"
"notification/NotificationChannelController.java:30 -> list (reason: 列出通知渠道，需 ADMIN)"
```

结果：**任何人可以删知识库、删页面、删区块、改通知渠道**，而测试永远绿。

### 1.3 门禁回退两处，理由不充分

| 项 | 基线 | Round 2 | 说法 |
|---|---|---|---|
| Playwright | ≥135 | **132** | "3 pre-existing failures unrelated" |
| Smoke | 11/11 | **10/11** | "MinIO not configured" |

都没有对照证据。`smoke.py:237-243` 校验的是附件 `storageKey` 前缀，**方法安全刚开启，也可能是端点被 403**。

### Round 2 判定

| 项 | 判定 |
|---|---|
| R1 方法安全生效 | ✅（`SecurityConfig.java:28`、`JwtAuthFilter.java:56-61` 角色映射正确） |
| R2 鉴权验证 | ❌ 403 断言被跳过；只测 13 个（要求 16）；`valid_admin` 允许 500 |
| R3 架构测试 | ⚠️ 扫描修好了（195 条含 GET ✅），但白名单滥用 |
| R4 actuator/Swagger | ⚠️ `show-details` ✅，`/v3/api-docs` 未关 ❌ |
| R5 T1 小修 | ✅ N+1 已修 |
| R6 验证脚本 | ⚠️ 13/13 是在跳过 403 的前提下得出的 |

## §2 任务

### RR1（P0）让 403 断言无法被跳过 —— 核心

1. **准备真实存在的非管理员账号**（三选一，说明选了哪个）：
   - 用 admin token 调 `POST /api/admin/users` 创建（把创建过程写进脚本，保证可重复）
   - 复用种子里已有的非管理员账号（先 grep 确认，给 `文件:行号`）
   - 通过环境变量 `AUTHZ_USER` / `AUTHZ_USER_PASS` 传入
2. **账号登录失败必须 FAIL** —— 删掉 `admin-authz-e2e-verify.py:126,128` 的 `None` 兜底：
   ```python
   if not user_token:
       print("FATAL: 非管理员账号登录失败，无法验证 403")
       sys.exit(1)      # 不许 None 兜底
   ```
3. 输出必须含**三列**：匿名 / 管理员 / 普通用户，每行给实际状态码

```bash
python3 scripts/admin-authz-e2e-verify.py
# 每行都要有：no-auth=401  admin=200  user=403
# 可失败性验证：注掉某个 @PreAuthorize 再跑，user 列必须变 200 → FAIL
```

### RR2（P0）补到 16 个端点 + 收紧条件

补齐缺失端点（`UserAdminController` 的改密、撤角色、effective-permissions 等）；
`valid_admin` **去掉 500**（`:80`）—— 鉴权代码抛异常也会是 500，不能当通过。
建议改为**扫描 Controller 上所有带 `@PreAuthorize` 的方法**自动生成端点清单，避免手工列举遗漏。

### RR3（P0）白名单只留真正公开的端点

遍历 `ControllerAuthorizationCoverageTest.java:35+` 每一条：
- 理由含"需 ADMIN / 需认证 / 需权限" → **一律移出白名单并加 `@PreAuthorize`**
- 真正公开的才留（健康检查、登录、刷新、第三方 HMAC 回调），理由写清"为什么可以公开"
- 交付里给出清理前后条目数对比
- **可失败性**：删掉某个端点上新加的 `@PreAuthorize`，测试必须 FAIL

### RR4（P0）关掉 `/v3/api-docs`

`application.yml:118-119` 在 docker/生产 profile 关闭 api-docs（或加鉴权）；同步收紧 `SecurityConfig:95-100` 里 `/v3/api-docs/**` 的 `permitAll`。
验收：匿名 `curl /v3/api-docs` 必须 401/403/404；dev profile 仍可用（给出两个 profile 的实际状态码）。

### RR5（P1）清理附带问题

- 删除 `JwtAuthFilter.java:49-50` 两条 `log.info`（打印 JWT roles + authorities，调试残留 + 权限信息泄漏）
- `scripts/test-auth.py`：说明用途或删除（疑似临时脚本）
- `GlobalExceptionHandler.java` 新增 9 行：确认 `AccessDeniedException` → **403**，贴实际响应

### RR6（P0）门禁回退必须给对照证据

- **Playwright 132/135**：在 `e1a4241`（本次改动前）跑一次 `npx playwright test` 贴输出。基线也是 132 → 登记为遗留；基线是 135 → 本次引入，必须修回
- **Smoke 10/11**：贴失败项**具体断言输出**（哪一条、什么错误）。注意可能是端点被 403，不是 MinIO

### RR7（P1）红线补第 9 条

在 `PHASE95_103_INDEX.md` 红线补：**验收脚本不得在数据/账号缺失时静默跳过断言，缺失即 FAIL**。

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（Round 2 为 1413，不得回退） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135**（当前 132，修回或给基线对照证据） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/admin-authz-e2e-verify.py` | **全 PASS 且每行含 user=403** |
| `python3 scripts/smoke.py` | **11/11**（当前 10/11） |

## §4 红线（违反即打回）

1. **403 断言不许被跳过** —— 非管理员账号缺失/登录失败必须 `exit 1`，**禁止 `None` 兜底**
2. **必须有真实 HTTP 证据** —— 三列状态码逐条贴出
3. **禁止恒真断言** —— 每个验收要"改错会 FAIL"的对照
4. **禁止用"pre-existing"搪塞门禁回退** —— 必须给改动前 commit 的对照输出
5. **白名单不许收"需 ADMIN"的端点**
6. **禁止为跑绿放宽通过条件**（`valid_admin` 不许再放 500）
7. **禁止修改已应用的 Flyway 迁移**
8. 回报数字必须能在交付物里找到**产出它的代码行**

## §5 交付清单

1. RR1 脚本改动 `文件:行号` + **完整三列矩阵**（16 行）+ "注掉注解 → user 变 200 → FAIL"验证
2. RR2 端点补齐清单 + 收紧后的 `valid_admin`
3. RR3 白名单清理前后条目数对比 + 加注解的端点清单 + "删注解会 FAIL"验证
4. RR4 `/v3/api-docs` 在 dev / 生产两个 profile 的实际状态码
5. RR5 调试日志删除、`test-auth.py` 处置、`AccessDeniedException → 403` 实际响应
6. RR6 Playwright 基线对照输出 + Smoke 失败项具体断言
7. RR7 索引红线更新
8. 门禁八项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §6 可复用

- 现有脚本：`scripts/admin-authz-e2e-verify.py`（改它，不要重写）
- 架构测试：`backend-java/src/test/java/com/nocobase/security/ControllerAuthorizationCoverageTest.java`
- 角色映射：`auth/JwtAuthFilter.java:51-62`（已正确，只删调试日志）
- 安全配置：`config/SecurityConfig.java:28,95-100`
- 冒烟：`scripts/smoke.py`（`:237-243` 附件那条）
- 脚本风格参考：`scripts/backup-e2e-verify.py`

-----END PROMPT-----

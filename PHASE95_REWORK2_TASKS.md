# PHASE95 返工 2 任务书：把"拦住了谁"真正验证出来（🔒-SaaS-P0-R2）

> 返工依据：PHASE95 返工 Round 2 交付审计（2026-10-10，commit `7af183e`）
> 一句话：**方法安全真的生效了（R1 ✅），但"非管理员被拦住"这件事一次都没被验证过 ——
> 脚本里 `user` 账号不存在，403 断言被 `None` 兜底静默跳过，还计成了 13/13 PASS。**

---

## §1 为什么必须再返工（三条）

### 1.1 最核心的断言被静默跳过

`scripts/admin-authz-e2e-verify.py:63` 用 `login("user", "user1234")` 取普通用户 token，
而 **`user1234` 全仓库只出现在这一行**（种子数据里没有这个账号）→ 登录必然失败 → `user_token = None`：

```python
else:
    user_ok = None                                            # :126
passed = no_auth_ok and admin_ok and (user_ok in (True, None))  # :128  None 也算 PASS
```

`None in (True, None)` == `True` → **13 个端点的"普通用户必须 403"全部跳过且计入 PASS**。

这批返工的整个目的就是证明"非管理员被拦住"，结果唯一没验证的正是它 —— 而且失败时**不报错、不告警**，又是静默成功。

### 1.2 白名单把"需要鉴权"的端点合法化了

`ControllerAuthorizationCoverageTest.java:35+` 的白名单语义被反转 —— 大量**理由就写着"需 ADMIN"**的端点被放进"无需 `@PreAuthorize`"的白名单：

```
"wiki/WikiController.java:101 -> createKb (reason: 知识库创建，需 ADMIN)"
"wiki/WikiController.java:165 -> deleteKb (reason: 知识库删除，需 ADMIN)"
"wiki/WikiController.java:442 -> deleteBlock (reason: 区块删除，需 ADMIN)"
"notification/NotificationChannelController.java:30 -> list (reason: 列出通知渠道，需 ADMIN)"
```

结果：**任何人可以删知识库、删页面、删区块、改通知渠道**，而测试永远绿。
形式从"文件基线"换成"内联白名单"，本质仍是红线 8 禁止的"存量问题合法化"。

### 1.3 门禁回退了两处，理由都不充分

| 项 | 基线 | Round 2 | 说法 |
|---|---|---|---|
| Playwright | ≥135 | **132** | "3 pre-existing failures unrelated" |
| Smoke | 11/11 | **10/11** | "MinIO not configured" |

两者都没有对照证据。`smoke.py:237-243` 校验的是附件 `storageKey` 前缀，**方法安全刚开启，也可能是端点被 403**，不能一句归因 MinIO。

## §2 Round 2 逐项判定

| 项 | 判定 | 依据 |
|---|---|---|
| R1 方法安全生效 | ✅ 通过 | `SecurityConfig.java:28` `@EnableMethodSecurity(prePostEnabled=true, proxyTargetClass=true)`；`JwtAuthFilter.java:56-61` 角色映射正确（`ROLE_`+大写，无角色回落 `ROLE_USER`） |
| R2 鉴权验证 | ❌ **未通过** | `user` 账号不存在 → 403 断言被 `None` 兜底跳过；只测 13 个（要求 16）；`valid_admin` 允许 500 |
| R3 架构测试 | ⚠️ **半通过** | 扫描能力修好了（195 条、含 GET ✅），但白名单滥用例外 |
| R4 actuator/Swagger | ⚠️ **半通过** | `show-details: when_authorized` ✅；`/v3/api-docs` 未关且 `SecurityConfig:95-100` 仍 permitAll ❌ |
| R5 T1 小修 | ✅ 通过 | N+1 已修（`findRoleNamesByUserId`）；tenantId 维度未在报告中提及 |
| R6 验证脚本 | ⚠️ **数字不可信** | 13/13 是在跳过 403 断言的前提下得出的 |
| 附带问题 | ⚠️ | `JwtAuthFilter:49-50` 两条 `log.info` 打印 roles/authorities 到 INFO 日志；`scripts/test-auth.py` 疑似临时脚本；`GlobalExceptionHandler +9` 未确认返回 403 |

## §3 七项返工任务

### RR1（P0）让 403 断言无法被跳过 —— 本次核心

1. **准备一个真实存在的非管理员账号**，三选一（选哪个都行，必须说明）：
   - 用 admin token 调 `POST /api/admin/users` 创建（并把创建过程写进脚本，保证可重复）
   - 复用种子数据里已有的非管理员账号（先 grep 确认存在，给出 `文件:行号`）
   - 通过环境变量 `AUTHZ_USER` / `AUTHZ_USER_PASS` 传入
2. **账号登录失败必须 FAIL** —— 删掉 `scripts/admin-authz-e2e-verify.py:126,128` 的 `None` 兜底：
   ```python
   if not user_token:
       print("FATAL: 非管理员账号登录失败，无法验证 403 —— 必须准备真实非管理员账号")
       sys.exit(1)      # 不许 None 兜底
   ```
3. 脚本输出必须包含**三列**：匿名 / 管理员 / 普通用户，每行都给实际状态码

**验收（必须三者齐全）**：
```bash
python3 scripts/admin-authz-e2e-verify.py
# 每一行都要有：no-auth=401  admin=200  user=403
# 验证可失败性：把某个 @PreAuthorize 注掉再跑，user 那列必须变成 200 → FAIL
```

### RR2（P0）补到 16 个端点 + 收紧通过条件

- 补齐缺失的端点（当前 13 个）：`UserAdminController` 的**改密**、**撤角色**、**effective-permissions**，以及历史 5 处中未覆盖的
- `valid_admin` **去掉 500**（`:80` 的 `[200,404,400,409,500]`）；500 一律 FAIL，因为鉴权代码抛异常也会是 500
- 端点发现方式建议改为**扫描 Controller 上所有带 `@PreAuthorize` 的方法**自动生成，避免手工列举遗漏

### RR3（P0）白名单只留真正公开的端点

- 遍历 `ControllerAuthorizationCoverageTest.java:35+` 的每一条白名单：
  - 理由里出现"需 ADMIN / 需认证 / 需权限"的 → **一律移出白名单并加 `@PreAuthorize`**
  - 真正公开的才能留（健康检查、登录、刷新、第三方 HMAC 回调等），且理由要写清"为什么可以公开"
- 目标：白名单条目数**显著下降**，且不再出现"需 ADMIN"字样
- **验证可失败性**：随手删掉某个端点上新加的 `@PreAuthorize`，测试必须 FAIL

### RR4（P0）关掉 `/v3/api-docs`

- `application.yml:118-119` 的 `api-docs` 在生产/docker profile 关闭（或对其鉴权）
- 同步收紧 `SecurityConfig:95-100` 里 `/v3/api-docs/**` 的 `permitAll`
- 验收：匿名 `curl /v3/api-docs` 必须 401/403/404；dev profile 仍可用（给出两个 profile 的实际状态码）

### RR5（P1）清理附带问题

- 删除 `JwtAuthFilter.java:49-50` 的两条 `log.info`（打印 JWT roles 与 authorities 属调试残留 + 权限信息泄漏）
- `scripts/test-auth.py`：说明用途，或删除（疑似临时调试脚本）
- `GlobalExceptionHandler.java` 新增的 9 行：确认 `AccessDeniedException` 映射为 **403**（贴出实际响应），不是 200/500

### RR6（P0）门禁回退必须给对照证据

- **Playwright 132/135**：在 `e1a4241`（本次改动之前）跑一次 `npx playwright test`，贴输出。
  - 若基线也是 132 → 确实是 pre-existing，登记进遗留项
  - 若基线是 135 → 本次引入，必须修回 135
- **Smoke 10/11**：贴出失败项的**具体断言输出**（哪一条、什么错误）。
  特别注意：`smoke.py:237-243` 校验附件 `storageKey` 前缀，**方法安全开启后也可能是端点被 403**，不能一句归因 MinIO

### RR7（P1）把"账号/断言缺失即失败"写进通用红线

在 `PHASE95_103_INDEX.md` 的红线里补第 9 条：**验收脚本不得在数据/账号缺失时静默跳过断言** —— 缺失即 FAIL。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（Round 2 为 1413，不得回退） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135**（当前 132，必须回到 135 或给出基线对照证据） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/admin-authz-e2e-verify.py` | **全 PASS，且每行含 user=403**（新增硬要求） |
| `python3 scripts/smoke.py` | **11/11**（当前 10/11，必须修回或给出具体失败断言） |

## §5 红线（违反即打回）

1. **403 断言不许被跳过** —— 非管理员账号缺失/登录失败必须 `exit 1`，**禁止 `None` 兜底**
2. **必须有真实 HTTP 证据** —— 三列状态码（匿名 401 / 管理员 200 / 普通用户 403）逐条贴出
3. **禁止恒真断言** —— 每个验收要"改错会 FAIL"的对照（注掉注解、删白名单条目都要能变红）
4. **禁止用"pre-existing"搪塞门禁回退** —— 必须给出在改动前 commit 上跑的对照输出
5. **白名单不许收"需 ADMIN"的端点** —— 需要鉴权就加注解，不许豁免
6. **禁止为了跑绿删测试或放宽通过条件**（`valid_admin` 不许再放 500）
7. **禁止修改已应用的 Flyway 迁移**
8. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. RR1 脚本改动 `文件:行号` + **完整三列矩阵**（16 行 × 匿名/管理员/普通用户）+ "注掉注解 → user 变 200 → FAIL"的验证输出
2. RR2 端点补齐清单 + `valid_admin` 收紧后的定义
3. RR3 白名单清理前后的条目数对比 + 移出白名单并加注解的端点清单 + "删注解会 FAIL"验证
4. RR4 `/v3/api-docs` 在 dev / 生产两个 profile 下的实际状态码
5. RR5 调试日志删除、`test-auth.py` 处置、`AccessDeniedException → 403` 的实际响应
6. RR6 Playwright 基线对照输出 + Smoke 失败项的具体断言输出
7. RR7 索引红线更新
8. 门禁八项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 现有脚本：`scripts/admin-authz-e2e-verify.py`（改它，不要重写）
- 架构测试：`backend-java/src/test/java/com/nocobase/security/ControllerAuthorizationCoverageTest.java`
- 角色映射：`auth/JwtAuthFilter.java:51-62`（已正确，只删调试日志）
- 安全配置：`config/SecurityConfig.java:28,95-100`
- 冒烟：`scripts/smoke.py`（`:237-243` 是附件那条）
- 验证脚本风格：`scripts/backup-e2e-verify.py`（PASS/FAIL 汇总 + 可失败性验证）

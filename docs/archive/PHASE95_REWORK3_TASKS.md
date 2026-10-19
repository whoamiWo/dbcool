# PHASE95 返工 3（小修）任务书：让拦截证据成立（🔒-SaaS-P0-R3）

> 返工依据：PHASE95 返工 2 审计（2026-10-10，commit `e95af33`）
> 定位：**只差一小步**。方法安全已生效、白名单已清理、兜底已修好，
> 但有 3 个端点的拦截证据不成立，1 项声称做了实际没做，1 处 500 归因错误。

---

## §1 还有哪些没做完（三条）

### 1.1 三行"拦截"是假证据 —— admin 与 user 状态码完全相同

```
PATCH /api/admin/users/{id}              401  400  400
POST  /api/admin/users/{id}/password     401  400  400
POST  /api/automation                    401  400  400
```

两列相同 = **鉴权没有产生区分**。

根因：Spring MVC 的参数绑定与 `@Valid` 校验发生在**调用代理方法之前**（`invokeForRequest` 的参数解析阶段），
请求体非法时直接抛 `MethodArgumentNotValidException` → 400，**`@PreAuthorize` 根本没机会执行**。
所以 admin 也是 400。

脚本又把 `valid_user` 放宽成 `[403, 400]`（`admin-authz-e2e-verify.py:125,159`），于是 400 也被判成"拦截成功"。
**400 证明不了拦截。** 要证明拦截必须用**合法请求体**，让 admin 得到 200/201、user 得到 403 —— **两列不同**才算证据。

### 1.2 RR7 声称做了，实际没做

交付报告写"在 `PHASE95_103_INDEX.md` 红线补第 9 条"，实际索引红线**仍只有 8 条**（`:74-81` 止于第 8 条）。

### 1.3 `assign role` 的 500 归因错误

报告说是"种子数据缺 role 行"。实际是**接口健壮性 bug**：传入不存在的 role ID 抛
`DataIntegrityViolationException` 且未被捕获 → 500。无效入参应返回 **400/404**。
用"种子数据"解释会让真 bug 溜走。

## §2 返工 2 判定汇总

| 项 | 判定 |
|---|---|
| RR1 兜底删除 | ✅ `:95-104` 账号创建/登录失败 `exit(1)` |
| RR1 证据质量 | ❌ 3 行 admin/user 同码，不能证明拦截 |
| RR2 端点与条件 | ⚠️ 补到 17 ✅；`valid_admin` 去 500 ✅；`valid_user` 放宽 400 ❌ |
| RR3 白名单 | ✅ 96 → 6 条，理由不含 ADMIN，加注解 45+ |
| RR4 api-docs | ✅ `SecurityConfig:106-107` 已移除 permitAll，实测 401 |
| RR5 清理 | ✅ 调试日志删除；AccessDenied 两层 403 说明清楚 |
| RR6 门禁对照 | ✅ Playwright 基线对照给了；Smoke 回到 11/11 |
| RR7 红线第 9 条 | ❌ 未做 |

## §3 五项小修

### M1（P0）用合法请求体重测三个端点 —— 本次核心

目标：**让 admin 与 user 产生不同的状态码**。

关键做法：脚本已经能创建真实测试用户（`create_non_admin_user`，`:52-65`），
**拿它的真实 ID 作为操作目标**，不要再用 `00000000-0000-0000-0000-000000000001` 这种假 UUID（admin 也会 404/400）。

| 端点 | 合法请求 | 期望 admin | 期望 user |
|---|---|---|---|
| `PATCH /api/admin/users/{realId}` | `{"displayName":"authz-test"}` | 200 | **403** |
| `POST /api/admin/users/{realId}/password` | 合法新密码字段（按 `ResetPasswordRequest` 定义） | 200 | **403** |
| `POST /api/automation` | 一条合法规则体（按 `AutomationRuleController:34` 的入参构造） | 200/201 | **403** |

同时把 `valid_user` **收紧回 `[403]`**（`:125,159` 去掉 400）。

**验收**：
```bash
python3 scripts/admin-authz-e2e-verify.py
# 这三行必须是 admin=200/201 且 user=403（两列不同）
# 可失败性：把该端点的 @PreAuthorize 注掉再跑，user 必须变 200/201 → FAIL
```

### M2（P0）`assign role` 的无效入参不要返回 500

- 先校验 role 存在（不存在 → 404），或捕获 `DataIntegrityViolationException` → 400
- 同理检查其他写端点是否也有"无效入参 → 500"的情况（顺手扫一遍）
- 验收：传不存在的 role ID，返回 **400/404**（不是 500）

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer <admin>" \
  -H 'Content-Type: application/json' -d '{}' \
  localhost:8080/api/admin/users/{realUserId}/roles/00000000-0000-0000-0000-000000000999
# 期望 400/404（当前 500）
```

### M3（P1）补红线第 9 条

在 `PHASE95_103_INDEX.md` 第 8 条之后加：

> 9. **验收脚本不得在数据/账号缺失时静默跳过断言** —— 账号创建/登录失败、`user_token` 为空
>    一律 `exit 1`，禁止用 `None` 兜底计入 PASS。
>    反面案例（PHASE95 Round 2）：`user1234` 账号不存在，`user_ok=None` 被判为通过。

### M4（P1）报告数字自洽

交付报告里同时出现 `16/17` 与 `15/17`。给出**唯一真值**并说明 FAIL 的是哪几行。

### M5（P1）登记遗留，不要把它们藏起来

必须在遗留项里明确写出（不得省略）：

1. **PHASE95 原始任务的 T5 / T6 / T7 至今一行未做** —— Round 1 只做了 T1–T4：
   - T5：跨租户越权 11 处修复 + 越权回归测试（这是 **P0-2 的核心**，比本批已做的更严重）
   - T6：Hibernate Schema 多租户接线（或明确放弃并改应用层强制）
   - T7：未接线项清理（`AiAssistantService` 的 `new RestTemplate()`、`MdcFilter`、`SlashCommandRegistry`）
2. baseline 剩余 ~150 条未加 `@PreAuthorize` 的方法 —— 按控制器逐批补，不阻塞本批

> 本批收尾后，**下一步应立刻回到原 `PHASE95_GLM53_AUTHZ_TASKS.md` 的 T5–T7**。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（当前 1413，不得回退） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | 132（基线同为 132，已登记遗留，不得再降） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/admin-authz-e2e-verify.py` | **全 PASS（17/17），且每行 admin 与 user 状态码不同** |
| `python3 scripts/smoke.py` | 11 / 11 |

## §5 红线（违反即打回）

1. **admin 与 user 状态码必须不同** —— 相同即视为"未证明拦截"，计 FAIL
2. **`valid_user` 只能是 `[403]`** —— 不许再塞 400/404/500
3. **禁止用"种子数据/环境问题"解释 500** —— 无效入参返回 500 就是接口 bug
4. **必须有"注掉注解 → user 变 200 → FAIL"的对照验证**
5. **禁止为了跑绿放宽条件或删测试**
6. **遗留项必须写全**，尤其 T5/T6/T7 未做这一条不许省略
7. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. M1 三行的**新矩阵**（admin=200/201，user=403）+ "注掉注解 → FAIL"的验证输出
2. M2 `assign role` 无效 role ID 的实际状态码（400/404）+ `文件:行号`；顺手扫到的其他 500 清单
3. M3 索引红线第 9 条的 `文件:行号`
4. M4 唯一的 PASS/FAIL 数字与 FAIL 行明细
5. M5 遗留项（**含 T5/T6/T7 未做**）
6. 门禁八项实测数字 + commit hash + `git status`（干净且已推送）

## §7 可复用（别重造）

- 脚本：`scripts/admin-authz-e2e-verify.py`（`:52-65` 已能创建真实测试用户，直接复用其 ID）
- 被测端点定义：`UserAdminController.java:71-97`（PATCH / resetPassword，均已有 `@PreAuthorize`）
- `AutomationRuleController.java:32-34`（POST，已有 `@PreAuthorize`）
- 异常处理：`config/GlobalExceptionHandler.java`、`SecurityConfig.java:119-123`（403 已就位）
- 原始任务书：`PHASE95_GLM53_AUTHZ_TASKS.md` 的 T5–T7（本批之后的下一步）

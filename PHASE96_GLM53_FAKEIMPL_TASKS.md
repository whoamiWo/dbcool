# PHASE96 任务书：假实现与"静默成功"清零（🔒-SaaS-P0）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §3.3（P0-3）
> 这批问题的共同特征：**不报错、不告警、测试还绿** —— 只能靠契约测试暴露，是最隐蔽的一类假完成。

---

## §1 为什么做这个（三条理由）

### 1.1 "静默成功"比"报错"危险得多

`notification/EmailDispatcher.java:56-58` 在未配置 `smtp_host` 时返回 `SendResult.ok("mock-sent …")`。
调用方拿到的是**成功**，用户以为邮件发了，实际从未发出。对外 SaaS 里这直接构成对客户的服务欺诈（且无法自查）。

同类问题还有 7 处，全部"看起来正常"。

### 1.2 本项目已多次栽在这一类上

- PHASE93：用户数据导出接口返回 `{}`（TODO 占位），报错都没有
- PHASE94：PITR 声称完成，实际归档全部失败、演练顺序物理上不可能
- 前两批的共同点：**验收只看"命令返回 0"，不看"结果是否真的产生"**

### 1.3 清零后可以让后续批次的门禁真正可信

假实现不清，PHASE103 的 CI 硬化（覆盖率红线、SAST）一开就会红，且分不清是"新代码有问题"还是"历史假实现暴露"。

## §2 现状（实测）

| # | 位置 | 现状行为 |
|---|---|---|
| 1 | `integration/common/InboundMessageService.java:166-171` | `mapExternalUser()` 只有 TODO，**恒返回硬编码 UUID** `00000000-0000-0000-0000-000000000001` |
| 2 | `notification/EmailDispatcher.java:56-58` | 未配 smtp 返回 `SendResult.ok("mock-sent …")` |
| 3 | `compliance/UserDataErasureService.java:169-175` | `eraseAiConversations()` 未实现，只 `log.warn` + `incrementConversationsErased(0)` |
| 4 | `bi/BiReportService.java:64-65` | id 不存在时 `orElseGet` 造 `tenantId=null` 空实体并 `save` |
| 5 | `im/SlashCommandInitializer.java:74-88`、`im/SlashCommandRegistry.java:55-62` | `/poll`、`/code` 回显占位；registry 用 `new` 装配 5 个空 lambda，已对用户暴露 |
| 6 | `ldap/LdapSyncService.java:212` | `setPasswordHash("LDAP_SYNCED")` 写入硬编码占位口令串 |
| 7 | `integration/dingtalk/DingTalkController.java:492-496` | `handleContactUpdated()` 只打日志 + TODO，通讯录变更被吞 |
| 8 | `ai/AiAssistantService.java:36` | `new RestTemplate()` 绕过 Spring Bean，无超时/连接池（PHASE95 T8 可能已处理，此处复核） |

## §3 七项任务

> 总原则：**要么真做，要么显式失败**。"返回成功但什么都没做"一律不允许。

### T1（P0）外部用户映射真实现

`InboundMessageService.java:166-171` 的 `mapExternalUser()`：

- 按 `平台 + 外部 userId` 建立映射（缺表就加 Flyway **新**迁移，禁止改已应用的）
- 映射不存在时**创建新用户或抛明确异常**，绝不允许回落到同一个硬编码 UUID
- 验收：两个不同外部用户（Slack A / 钉钉 B）发消息后，落库 `sender_id` **必须不同**

```bash
# 分别触发两个不同外部用户的入站消息，查库
docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A \
  -c "select count(distinct sender_id) from im_message where <本次两条消息>;"
# 期望 2（修复前恒为 1）
```

### T2（P0）EmailDispatcher 未配置必须失败

- 未配 `smtp_host` 时**抛异常或返回失败结果**，并在启动/首次调用时告警
- 严禁任何形式的 `ok(...)` / `mock-sent` 返回值
- 验收：未配 SMTP 时调用发送接口，必须返回非成功（且日志有 ERROR/WARN）

```bash
grep -rn "mock-sent\|SendResult.ok" backend-java/src/main/java | wc -l   # 期望 0
```

### T3（P0）AI 会话删除真实现

`UserDataErasureService.eraseAiConversations()`：

- 依赖 PHASE95 T6#10 给 `AiConversationEntityRepository` 增加的 `tenantId` 维度
- 真做批量匿名化/删除（参照同文件里 IM 消息与审计日志的批量 UPDATE 写法）
- 验收：删除后按 userId 查 AI 会话，返回内容必须是匿名占位或 0 条；**且计数不再恒为 0**

### T4（P0）BI 报表空实体改为 404

`BiReportService.java:64-65`：id 不存在时抛 `ResponseStatusException(NOT_FOUND)`，禁止 `orElseGet` 造空实体保存。

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <token>" \
  localhost:8080/api/bi/reports/00000000-0000-0000-0000-000000000000   # 期望 404
```

### T5（P0）SlashCommand 空 handler：真实现或下线

- `/poll`、`/code` 等占位命令：要么实现最小可用版本，要么**从 `SlashCommandInitializer` 移除**，不允许对用户暴露"待后续迭代"的回显
- `SlashCommandRegistry.java:55-62` 的 `new` 手工装配改为 Spring Bean（PHASE95 T8 若已处理则复核）
- 验收：列出所有已注册命令，逐个执行，**不得出现"待后续迭代"/占位文案**

### T6（P1）LDAP 占位口令

`LdapSyncService.java:212` 的 `setPasswordHash("LDAP_SYNCED")`：LDAP 同步的用户**不得**用可预测的占位口令；应为随机不可登录口令，并在首次本地登录时走重置流程（或明确禁止本地登录）。

### T7（P1）钉钉通讯录变更事件不再被吞

`DingTalkController.java:492-496`：`handleContactUpdated()` 要么接真（触发用户同步），要么**返回明确的"未实现"状态码并记审计**，不许只打日志就返回成功。

### T8（P0）建立"禁止静默成功"的常态化防线

- 新增架构/契约测试：扫描全仓 `TODO|FIXME|占位|placeholder|mock-sent|待后续迭代`，命中即 FAIL（确有登记的未实现项放**显式白名单**，白名单条目要写清"谁、为什么不实现、什么时候做"）
- 把 T1~T7 的验收合并进一个可重复执行的脚本 `scripts/no-fake-impl-verify.py`
- 验收：脚本全 PASS；**随手把某处改回占位实现，脚本必须 FAIL**

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411**（0 failures / 0 errors） |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（PHASE95 产出，不得回退） |

## §5 红线（违反即打回）

1. **禁止静默成功** —— 占位实现要么真做、要么显式抛错/告警，绝不返回"成功"
2. **禁止恒真断言** —— 每个验收必须能失败；写完随手改错一次确认会红
3. **禁止把"删掉这个功能"当作修复** —— 如确需下线，必须在交付里说明理由与影响面
4. **禁止修改已应用的 Flyway 迁移**
5. **禁止为了跑绿删既有测试**
6. **白名单不能变成垃圾桶** —— 放进白名单的未实现项必须带"责任人与计划时间"
7. 回报的每个数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1：两个不同外部用户落库 `sender_id` 不同的证据（SQL 输出）
2. T2：`grep mock-sent` 为 0 + 未配 SMTP 时接口返回非成功的实际响应
3. T3：AI 会话删除前后的查询对比（不再恒为 0）
4. T4：不存在的报表 id 返回 404 的实际状态码
5. T5：已注册命令逐个执行的结果（无占位文案）
6. T6/T7 的`文件:行号`与验收输出
7. `scripts/no-fake-impl-verify.py` + 全 PASS 输出 + "改回占位会 FAIL"的验证
8. 门禁七项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 批量匿名化写法：`compliance/UserDataErasureService.java`（IM 消息与审计日志部分）
- 批量 UPDATE 的性能教训：逐行 `save()` 会超时（HTTP 0），必须用 `@Modifying @Query`
- 验证脚本风格：`scripts/user-data-e2e-verify.py`、`scripts/backup-e2e-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

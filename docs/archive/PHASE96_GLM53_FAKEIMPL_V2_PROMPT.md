# PHASE96 提示词 V2：假实现与"静默成功"清零

> 使用方式：把下面 `BEGIN PROMPT` 与 `END PROMPT` 之间的**全文**投喂给 Kilo Code + GLM-5.3。
> 配套任务书：`PHASE96_GLM53_FAKEIMPL_V2_TASKS.md`（同目录）。旧版
> `PHASE96_GLM53_FAKEIMPL_TASKS.md` 从未执行，保留供对照，**以本版为准**。

---

BEGIN PROMPT

# 任务：PHASE96 —— 把"返回成功但什么都没做"的假实现清零

## 0. 背景

你在 `/home/who/multistack-project`（DBCool，企业级协作平台，目标对外多租户 SaaS）。
本批清掉 11 处假实现/静默成功。共同特征：**不报错、不告警、测试还绿**。

总原则只有一句：**要么真做，要么显式失败。**"返回成功但什么都没做"一律不允许。

> ⚠️ 所有 `文件:行号` 是 2026-10-12 快照，行号会漂移，开工前逐条核对，以实测为准；
> 发现任务书与代码不符，按红线 8 回报差异，不要照抄硬改。

## 1. 现状清单（11 处，全部已核实）

| # | 位置 | 判定 |
|---|---|---|
| 1 | `integration/common/InboundMessageService.java:160-171` | `mapExternalUser()` 恒返回硬编码 UUID，失败分支是死代码 |
| 2 | `integration/dingtalk/UserMappingService.java:28-29,66-72` | 内存 HashMap 冒充映射表，tenantId 不进 key |
| 3 | `notification/EmailDispatcher.java:55-59` | 未配 smtp → `SendResult.ok("mock-sent …")` |
| 4 | `notification/EmailDispatcher.java:61-66` | 配了 host 但 real_send=false → `ok("logged-send …")`，一封不发却报成功 |
| 5 | `compliance/UserDataErasureService.java:169-175` | `eraseAiConversations()` 记 0 条，整体仍 `setSuccess(true)` |
| 6 | `bi/BiReportService.java:63-71` | id 不存在时 `orElseGet` 造 `tenantId=null` 孤儿报表 |
| 7 | `bi/BiReportService.java:64` | `findById` 无租户校验 → 可覆盖改写他租户报表 |
| 8 | `im/SlashCommandInitializer.java:74-88` | `/poll`、`/code` 回显"待后续迭代"却 `status:"ok"` |
| 9 | `ldap/LdapSyncService.java:200-218` | `setPasswordHash("LDAP_SYNCED")` 全库统一常量口令 |
| 10 | `ldap/LdapSyncService.java:160-173` | 定时同步硬编码 `findByTenantId("system")` + 空 catch 吞异常 |
| 11 | `integration/dingtalk/DingTalkController.java:492-496` | `handleContactUpdated()` 只打日志 + TODO（**未复核，先确认现状**） |

**不要改的（已核实的真实现/合规设计）**：
`BiReportService.executePivot/executeChart`（真下推 SQL + Redis 缓存）、
`LdapTemplateConfig.java:27-37`（占位 URL 是"显式失败"设计，注释明写不伪造用户数据）、
`SlashCommandConfig.java:18`（Spring Bean 化已完成，只复核）。

**未找到项**（照实写"未找到"，不许虚构）：LDAP 默认口令 `changeit`/`admin`/空串；
"LDAP 未配置静默降级为本地认证"路径（`auth` 包对 Ldap 0 命中）；
`scripts/no-fake-impl-verify.py`；`UserDataErasureService`/`LdapSyncService` 的测试类。

## 2. T1（P0）外部用户映射真实现

- 按 `平台 + 外部 userId (+ tenantId)` 建映射，缺表加 **Flyway 新迁移**（禁止改已应用的）；
  范式抄 `V28__platform_enhancement.sql:106-121` 的 `ldap_user_mapping`
- 映射不存在时**建新用户或抛明确异常**，绝不允许回落到同一个硬编码 UUID
- `UserMappingService` 的内存 HashMap 改落库（或明确缓存只读、真相在库），tenantId 必须进 key
- **同步改测试**：`InboundMessageServiceIntegrationTest.java:40-42` 的
  `MAPPED_USER = 00000000-…-0001` 把假 UUID 当期望值 → 改成"两个外部用户 → 两个不同 sender_id"

验收：两个不同外部用户发消息后
`select count(distinct sender_id) from im_message where id in (<本次两条>);` 期望 **2**（修复前恒 1）。

## 3. T2（P0）EmailDispatcher 两处假成功都必须失败

`:55-59` 与 `:61-66` **两处**都改：
- 严禁 `SendResult.ok("mock-sent…")` / `ok("logged-send…")`
- 未配 host → `error("smtp_host 未配置")`；配了 host 但 `real_send=false` → 同样 error
  （或 real_send 默认 true + 启动时对 enabled 渠道打 ERROR）
- `:68-85` 的真发送路径（JavaMailSenderImpl）**已存在且正确，不要动**
- 补部署侧入口：`application.yml`/compose 注释告知"EMAIL 渠道必须显式开 real_send"

**同步改测试**（会阻挡修复）：`EmailDispatcherTest.java` 的 `send_noSmtpHost_returnsMockOk`（:39-45）与
`send_withSmtpHost_returnsLoggedOk`（:48-58）把假成功当期望值，必须改写为断言 `success()==false`
（参考同文件 `send_realSendEnabled_attemptsRealDelivery` :76-90 的写法）。**改写，不是删除。**

验收：`grep -rn "mock-sent\|logged-send" backend-java/src/main/java | wc -l` 期望 **0**，
外加未配 SMTP 时接口返回非成功的实际响应。

## 4. T3（P0）AI 会话删除真实现

- `AiConversationEntityRepository` **无 userId 维度**（现有方法只有 tenantId 维度）→ 先补
  `@Modifying @Query` 批量匿名化/删除，写法抄 `ImMessageRepository.java:57-73`
- 类注释（`:129-132`）声称"content 替换为 `[已删除]`"与实现不符，一并修正
- **不能选显式失败**（会让 GDPR 接口对所有有 AI 会话的用户报错，等于下线合规功能）
- 本项**零测试**，必须新增

验收：删除后按 userId 查，内容是匿名占位或 0 条；**`conversationsErased` 计数不再恒为 0**
（修复前恒 0，这是最直接的证伪点）。

## 5. T4（P0）BI 报表：空实体改 404 + 补租户校验

1. id 不存在 → `ResponseStatusException(NOT_FOUND)`，禁止 `orElseGet` 造空实体
2. `findById(UUID)` → `findByIdAndTenantId(UUID, tenantId)`，堵住覆盖他租户报表的越权写入

**⚠️ 旧版验收命令失效**：`GET /api/bi/reports/{id}` 端点**不存在**（只有 POST /pivot、
POST /chart、GET /reports、POST /reports）。改用 `POST /api/bi/reports` 传不存在的 id，期望 **404**：
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/bi/reports \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"id":"00000000-0000-0000-0000-000000000000","collectionName":"users"}'
# 期望 404（修复前 200 + 落一条 tenant_id=null 孤儿报表）
```
再用他租户的报表 id 打一次，期望同样 404。

**同步改测试**：`BiReportServiceTest.java` 全 mock 自证且**没有覆盖"传入不存在的 id"**的用例，
至少补一个能失败的新用例。

## 6. T5（P0）SlashCommand 空 handler：真实现或下线

- `/poll`、`/code`：要么实现最小可用版，要么**从 `SlashCommandInitializer` 摘除注册**
  （`GET /api/im/slash/commands` 会把它广告给前端，属虚假承诺）
- 选下线必须在交付里说明理由与影响面
- `SlashCommandRegistry` 的 Spring Bean 化已完成（`SlashCommandConfig.java:18`），只复核
- **顺带登记不修**：`ImSlashController.java:64` 未注册命令 → NPE → 500；`:51` 缺失 channelId → 500

**同步改测试**：`ImSlashControllerTest.java` 的 `execute_runsRealHandler` 用的是自己临时注册的
`test` 命令，绕开内置 5 个；`SlashCommandInitializer` 零测试。至少补一个逐个断言内置命令
不回显占位文案的用例。

验收：列出所有已注册命令逐个执行，**不得出现"待后续迭代"/占位文案**。

## 7. T6（P1→可能升 P0）LDAP 占位口令 + 定时同步

1. `setPasswordHash("LDAP_SYNCED")` → 随机**不可登录**口令（哈希 UUID），并在本地登录链路
   识别"LDAP 用户"直接拒绝提示走 IdP。**不要**只改成"随机但可登录"
2. `scheduledSync()` 的 `findByTenantId("system")` → 遍历所有租户启用配置；
   空 `catch (Exception e)` 改为记录 ERROR
3. LDAP 零测试 → 至少补 1 个
4. **必须实测登录**（红线 7）：用 `LDAP_SYNCED` 作密码尝试登录一个已同步用户，贴出实际状态码。
   **若能登入 → 这是可利用的已知口令，优先级升 P0**，交付里必须点名

## 8. T7（P1）钉钉通讯录变更事件不再被吞

`DingTalkController.java:492-496`（旧版记录，**先复核行号与现状**）：`handleContactUpdated()`
要么接真（触发用户同步），要么返回明确"未实现"状态码并记审计，不许只打日志就返回成功。

## 9. T8（P0）建立常态化防线

- 新增契约/架构测试：扫描全仓 `TODO|FIXME|占位|placeholder|mock-sent|待后续迭代`，命中即 FAIL；
  确有登记的放**显式白名单**，条目必须带"谁、为什么不实现、什么时候做"
- 把 T1~T7 验收合并成 `scripts/no-fake-impl-verify.py`（**当前不存在**）
- 验收：脚本全 PASS + **随手把某处改回占位实现，脚本必须 FAIL**

## 10. 门禁十项（逐条不得回退）

| # | 命令 | 基线 |
|---|---|---|
| 1 | `mvn -o test` | > 1411（当前 1415 / 0F / 0E） |
| 2 | `mvn -o verify` | JaCoCo ≥ 82%（**当前 61%，已裁决遗留转 PHASE102，不得再降**） |
| 3 | `npm run test:run` | ≥ 382（实测 382） |
| 4 | `npx tsc --noEmit` | 0 errors |
| 5 | `npx playwright test` | ≥ 135（当前 133，collab-crdt 429 遗留，不得再降） |
| 6 | pytest | 48 passed / 1 skipped |
| 7 | `backup-e2e-verify.py` | **30 / 30** |
| 8 | `admin-authz-e2e-verify.py` | 18 / 18 |
| 9 | `tenant-isolation-e2e-verify.py` | 全 PASS（14 条，不得回退） |
| 10 | `smoke.py` | 11 / 11 |

**输出归档**：每条命令与输出落 `reports/`，禁止只放 `/tmp`。

## 11. 红线（违反即打回）

1. 禁止静默成功 —— 要么真做，要么显式失败
2. 禁止恒真断言 —— 每个验收必须能失败，写完随手改错一次确认会红
3. 禁止把"删掉功能"当修复 —— 确需下线必须说明理由与影响面
4. 禁止修改已应用的 Flyway 迁移
5. 禁止为了跑绿删既有测试 —— 固化假成功的测试要**改写**
6. 白名单不能变垃圾桶 —— 条目必须带责任人与计划时间
7. 安全类改动必须有真实 HTTP 证据（尤其 T6-4 登录实测）
8. 文档与代码不符以代码为准并回报差异（旧版 T4 的 curl 路径就是反例）
9. 每个数字必须能在交付物里找到产出它的代码行
10. **提交需用户授权** —— 不得自行 commit / push

## 12. 交付清单

1. T1：两个外部用户 `sender_id` 不同的 SQL 输出（期望 2）
2. T2：`grep mock-sent|logged-send` 为 0 + 未配 SMTP 的非成功实际响应
3. T3：删除前后查询对比（计数不再恒 0）
4. T4：不存在 id 的 404 + 他租户 id 的 404 + 孤儿报表数为 0
5. T5：已注册命令逐个执行结果（无占位文案）
6. T6：`文件:行号` + `LDAP_SYNCED` 登录实测状态码（**无论能否登入都要贴**）+ 定时同步改动
7. T7：`文件:行号` 与验收输出
8. T8：`scripts/no-fake-impl-verify.py` + 全 PASS + "改回占位会 FAIL"的输出
9. 门禁**十项**实测数字 + commit hash + `git status`（未授权写"待授权"）
10. **遗留项**（强制）：白名单条目及责任人/时间；T5 登记的 NPE；各"未找到"项复核结论

## 13. 止停线

不改：`BiReportService.executePivot/executeChart`、`LdapTemplateConfig`、`SlashCommandConfig`（只复核）。
不改业务规则、不改 `pom.xml`、不放宽断言、不删有效测试、不自行 commit/push。

END PROMPT

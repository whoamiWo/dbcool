# PHASE96 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE96_GLM53_FAKEIMPL_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE96：假实现与"静默成功"清零（🔒-SaaS-P0）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录
- **上线目标：对外多租户 SaaS**

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 一键冒烟 | `python3 scripts/smoke.py` |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .` |
| 重启后端 | `docker compose up -d --no-build backend-java` |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| **新增端点后出现 401** | 多半是没重新打包部署 —— 先打包+重建+重启再验证 |
| 敏感配置 | 一律放 `.env`（已 gitignore，禁止提交） |

---

## §1 为什么做这一项

来自 `SAAS_LAUNCH_ASSESSMENT.md` §3.3（2026-10-09 实测）。

`notification/EmailDispatcher.java:56-58` 在未配置 `smtp_host` 时返回 `SendResult.ok("mock-sent …")`。
调用方拿到的是**成功**，用户以为邮件发了，实际从未发出 —— 对外 SaaS 里这构成对客户的服务欺诈，且无法自查。

**本项目已多次栽在这一类上**：
- PHASE93：用户数据导出返回 `{}`（TODO 占位），连报错都没有
- PHASE94：PITR 声称完成，实际归档全部失败、演练顺序物理上不可能
- 共同点：**验收只看"命令返回 0"，不看"结果是否真的产生"**

## §2 现状（实测，8 处）

| # | 位置 | 现状 |
|---|---|---|
| 1 | `integration/common/InboundMessageService.java:166-171` | `mapExternalUser()` 只有 TODO，**恒返回硬编码 UUID** `00000000-0000-0000-0000-000000000001` |
| 2 | `notification/EmailDispatcher.java:56-58` | 未配 smtp 返回 `SendResult.ok("mock-sent …")` |
| 3 | `compliance/UserDataErasureService.java:169-175` | `eraseAiConversations()` 未实现，只 warn + 计 0 |
| 4 | `bi/BiReportService.java:64-65` | id 不存在时 `orElseGet` 造 `tenantId=null` 空实体并 `save` |
| 5 | `im/SlashCommandInitializer.java:74-88`、`im/SlashCommandRegistry.java:55-62` | `/poll`、`/code` 回显占位，已对用户暴露 |
| 6 | `ldap/LdapSyncService.java:212` | `setPasswordHash("LDAP_SYNCED")` 占位口令 |
| 7 | `integration/dingtalk/DingTalkController.java:492-496` | `handleContactUpdated()` 只打日志 + TODO |
| 8 | `ai/AiAssistantService.java:36` | `new RestTemplate()` 绕过 Spring Bean（PHASE95 可能已处理，复核） |

## §3 任务

> **总原则：要么真做，要么显式失败。"返回成功但什么都没做"一律不允许。**

### T1（P0）外部用户映射真实现

按 `平台 + 外部 userId` 建立映射（缺表加 Flyway **新**迁移，禁止改已应用的）。映射不存在时创建新用户或抛明确异常，**绝不回落硬编码 UUID**。

```bash
docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A \
  -c "select count(distinct sender_id) from im_message where <本次两条消息>;"
# 期望 2（修复前恒为 1）
```

### T2（P0）EmailDispatcher 未配置必须失败

未配 `smtp_host` 抛异常或返回失败结果 + 告警；严禁任何 `ok(...)`/`mock-sent`。

```bash
grep -rn "mock-sent\|SendResult.ok" backend-java/src/main/java | wc -l   # 期望 0
```

### T3（P0）AI 会话删除真实现

依赖 PHASE95 T6#10 给 `AiConversationEntityRepository` 加的 `tenantId` 维度。真做批量匿名化/删除（参照同文件里 IM 消息与审计日志的批量 UPDATE 写法）。验收：删除后不再恒为 0。

### T4（P0）BI 报表空实体改为 404

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <token>" \
  localhost:8080/api/bi/reports/00000000-0000-0000-0000-000000000000   # 期望 404
```

### T5（P0）SlashCommand：真实现或下线

不允许对用户暴露"待后续迭代"的回显。要么实现最小可用版本，要么从 `SlashCommandInitializer` 移除。验收：逐个执行已注册命令，输出不得含占位文案。

### T6（P1）LDAP 占位口令

LDAP 同步用户不得用可预测占位口令；改随机不可登录口令 + 首次登录走重置（或明确禁止本地登录）。

### T7（P1）钉钉通讯录变更不再被吞

要么接真（触发用户同步），要么返回明确的"未实现"状态码并记审计，不许只打日志返回成功。

### T8（P0）建立"禁止静默成功"的常态化防线

新增架构/契约测试扫描全仓 `TODO|FIXME|占位|placeholder|mock-sent|待后续迭代`，命中即 FAIL（确有登记的未实现项放**显式白名单**，白名单要写"谁、为什么不实现、什么时候做"）。合并为 `scripts/no-fake-impl-verify.py`。

**验收：脚本全 PASS；随手把某处改回占位实现，脚本必须 FAIL。**

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（PHASE95 产出） |

## §5 红线（违反即打回）

1. **禁止静默成功** —— 要么真做、要么显式抛错/告警
2. **禁止恒真断言** —— 每个验收必须能失败；写完随手改错一次确认会红
3. **禁止把"删掉功能"当修复** —— 确需下线要说明理由与影响面
4. **禁止修改已应用的 Flyway 迁移**
5. **禁止为了跑绿删既有测试**
6. **白名单不能变成垃圾桶** —— 必须带责任人与计划时间
7. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 两个外部用户 `sender_id` 不同的 SQL 证据
2. T2 `grep` 为 0 + 未配 SMTP 时的实际响应
3. T3 AI 会话删除前后对比（不再恒为 0）
4. T4 404 实际状态码
5. T5 命令逐个执行结果（无占位文案）
6. T6/T7 的 `文件:行号` 与验收输出
7. `scripts/no-fake-impl-verify.py` + 全 PASS + "改回占位会 FAIL"的验证
8. 门禁七项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制，不得省略）

## §7 可复用

- 批量匿名化写法：`compliance/UserDataErasureService.java`（IM 消息、审计日志部分）
- 性能教训：逐行 `save()` 会超时（表现为 HTTP 0），必须用 `@Modifying @Query` 批量 UPDATE
- 验证脚本风格：`scripts/user-data-e2e-verify.py`、`scripts/backup-e2e-verify.py`

-----END PROMPT-----

# PHASE99 任务书：对外 SaaS 必补三项（🔒-SaaS-P1）

> 选题依据：`SAAS_LAUNCH_ASSESSMENT.md` §4
> 这三项在"内部自用"口径下是**可延后**的；用户已确认目标是**对外多租户 SaaS**，因此全部升级为**必做**。
> 前置依赖：**PHASE95 必须先完成**（租户上下文可信 + 租户隔离校验到位，否则本批等于在沙上建塔）。

---

## §1 为什么做这个（三条理由）

### 1.1 集成凭证是全局的 —— 多租户下无法各配各的

集成凭证（如 `dingtalk.app-secret`）目前是全局 `@Value`：所有租户共用同一套第三方应用凭证。
对外售卖时意味着**租户 A 的钉钉消息会走租户 B 的应用发出去**，且租户无法自助配置自己的应用。

### 1.2 入站回调无限流 —— 对外等于敞开被刷

第三方回调（Slack / 钉钉 / 飞书 / Mattermost / 企微）目前**无限流**，项目里只有登录限流。
对外暴露后，任何人拿到回调地址就能打爆服务。

### 1.3 集成行为无审计 —— 出事无法追溯

谁安装了什么、谁卸载了、入站消息来自哪个外部身份，目前都没有审计记录。
对外 SaaS 的客户安全问卷一定会问"能否追溯第三方集成的操作与数据来源"。

## §2 现状（需先定位，下列为已知线索）

| 项 | 现状 |
|---|---|
| 集成凭证 | 全局 `@Value`（`LAUNCH_READINESS_REPORT.md:144` 记录为 `dingtalk.app-secret` 全局 `@Value`） |
| 入站限流 | ❌ 无（仅登录限流） |
| 集成审计 | ❌ 无 |

> **执行要求**：本批开工前先 `grep -rn "@Value" backend-java/src/main/java/com/nocobase/integration/` 定位所有凭证读取点，
> 把实际 `文件:行号` 写进交付清单。**不得照抄本文档的线索行号当作实测值。**

## §3 四项任务

### T1（P0）集成凭证租户级隔离

- 新增凭证存储（租户级）：**加新的 Flyway 迁移**（禁止改已应用的），字段至少含 `tenant_id`、`provider`、`app_key`、`app_secret_enc`、状态、更新时间
- **凭证必须加密存储**（不可明文落库；密钥走环境变量/配置，禁止硬编码）
- 读取优先级：**租户级配置 → 回退全局 `@Value`**（保证存量部署不炸）
- 凭证读接口必须带租户归属校验（参照 PHASE95 的范式）

**验收（必须会失败）**：
```bash
# 两个租户分别配置不同的 app_key，各自生效且互不读取
# 用租户 A 的凭证配置去读租户 B 的配置，必须 403/404
# 未配置租户级凭证时，必须回退到全局 @Value（存量行为不变）
```

### T2（P0）第三方回调入站限流

- 对所有 `/api/{slack|feishu|wecom|dingtalk|mattermost}/events`、`/api/dingtalk/approval-callback` 等**匿名放行**的回调端点加限流
- 维度建议：按来源 IP + 按 provider（可配置阈值）
- 超限返回 **429**（不是 200 + 业务码）
- 限流触发必须**记日志/告警**（不许静默丢弃）

**验收**：
```bash
# 短时间内对回调端点打 N 次（N 超过阈值），必须出现 429
for i in $(seq 1 200); do curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/slack/events -d '{}'; done | sort | uniq -c
# 期望出现 429；把限流关掉再跑，必须不出现 429（证明断言不是恒真）
```

### T3（P0）集成审计日志

- 覆盖三类事件：**安装 / 卸载 / 入站消息**
- 审计记录必须含 `tenantId`、操作人（安装卸载场景）、provider、外部身份、消息来源
- 复用既有 `AuditService.log(...)` 的写法（不要另造一套）

**验收**：
```bash
# 安装一个集成 → 审计表出现对应记录（含 tenantId）
# 触发一条入站消息 → 审计表出现记录且能追溯到外部身份
docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A \
  -c "select action, resource, tenant_id from audit_log where resource like 'integration%' order by created_at desc limit 10;"
```

### T4（P1）可重复执行的验收脚本

产出 `scripts/saas-readiness-verify.py`，覆盖 T1/T2/T3，一键输出 PASS/FAIL，并纳入 `PHASE95_103_INDEX.md` 的门禁表。

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（PHASE95） |
| `python3 scripts/api-contract-verify.py` | 0 处漂移（PHASE97） |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/saas-readiness-verify.py` | 全 PASS（新增） |

## §5 红线（违反即打回）

1. **禁止凭证明文落库** —— 必须加密存储，密钥走配置且禁止硬编码
2. **禁止破坏存量行为** —— 租户级取不到时必须回退全局 `@Value`，并写测试锁定该回退
3. **禁止恒真断言** —— 每条验收必须能失败；限流那项必须"关掉限流会不出现 429"的对照验证
4. **禁止静默丢弃** —— 限流触发要记日志/告警，不许悄悄 200
5. **禁止修改已应用的 Flyway 迁移**
6. **禁止照抄线索行号当实测** —— 交付里的行号必须是自己 grep 出来的
7. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 凭证读取点的**实测清单**（`grep "@Value"` 得到的 `文件:行号`）+ 租户级表结构（新迁移文件名）+ 加密方案 + 回退测试
2. T2 限流配置与 429 实测输出 + "关掉限流不出现 429"的对照
3. T3 审计记录 SQL 输出（安装/卸载/入站消息三类）
4. `scripts/saas-readiness-verify.py` + 全 PASS 输出
5. 门禁九项实测数字 + commit hash + `git status`（干净且已推送）
6. **遗留项**（强制，不得省略）

## §7 可复用（别重造）

- 审计写法：`backend-java/src/main/java/com/nocobase/audit/AuditService.java` 与 `AuditLogEntity`
- 租户归属校验范式：`compliance/UserDataErasureService.java` 的 `requireUserInTenant`（PHASE95 已推广）
- 既有限流（登录）：先 grep 复用，不要另造轮子
- 验证脚本风格：`scripts/backup-e2e-verify.py`、`scripts/api-contract-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

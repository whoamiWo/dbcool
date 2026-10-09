# PHASE99 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE99_GLM53_SAAS_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE99：对外 SaaS 必补三项（🔒-SaaS-P1）

> **前置依赖：PHASE95 必须先完成。** 租户上下文不可信时做凭证隔离等于在沙上建塔。

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录
- **上线目标：对外多租户 SaaS** —— 本批三项由"可延后"升级为**必做**

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 一键冒烟 | `python3 scripts/smoke.py` |
| 查审计表 | `docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A -c "<SQL>"` |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests` |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .` |
| 重启后端 | `docker compose up -d --no-build backend-java` |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（已 gitignore，禁止提交） |

---

## §1 为什么做这一项

来自 `SAAS_LAUNCH_ASSESSMENT.md` §4（2026-10-09）。

**集成凭证是全局的**：集成凭证（如 `dingtalk.app-secret`）目前是全局 `@Value`，所有租户共用同一套第三方应用凭证。对外售卖时意味着**租户 A 的钉钉消息会走租户 B 的应用发出去**，且租户无法自助配置自己的应用。

**入站回调无限流**：第三方回调（Slack / 钉钉 / 飞书 / Mattermost / 企微）目前无限流，项目里只有登录限流。对外暴露后，任何人拿到回调地址就能打爆服务。

**集成行为无审计**：谁安装了什么、谁卸载了、入站消息来自哪个外部身份，都没有审计记录。客户安全问卷一定会问"能否追溯第三方集成的操作与数据来源"。

## §2 现状（已知线索，开工后必须自己 grep 复核）

| 项 | 现状 |
|---|---|
| 集成凭证 | 全局 `@Value`（线索：`LAUNCH_READINESS_REPORT.md:144`） |
| 入站限流 | ❌ 无（仅登录限流） |
| 集成审计 | ❌ 无 |

**执行要求**：先 `grep -rn "@Value" backend-java/src/main/java/com/nocobase/integration/` 定位所有凭证读取点，把实际 `文件:行号` 写进交付清单。**不得把上面的线索行号当作实测值。**

## §3 任务

### T1（P0）集成凭证租户级隔离

- 新增租户级凭证存储（**加新的 Flyway 迁移**，禁止改已应用的），字段至少含 `tenant_id`、`provider`、`app_key`、`app_secret_enc`、状态、更新时间
- **凭证加密存储**，密钥走环境变量/配置，**禁止硬编码**
- 读取优先级：**租户级 → 回退全局 `@Value`**（保证存量部署不炸）
- 读接口带租户归属校验（参照 PHASE95 范式）

验收：两个租户配不同 `app_key` 各自生效；用租户 A 读租户 B 的配置必须 403/404；未配租户级时必须回退全局 `@Value`（写测试锁定该回退）。

### T2（P0）第三方回调入站限流

对 `/api/{slack|feishu|wecom|dingtalk|mattermost}/events`、`/api/dingtalk/approval-callback` 等**匿名放行**的回调端点加限流（按 IP + provider，阈值可配）。超限返回 **429**（不是 200 + 业务码）。限流触发必须记日志/告警。

```bash
for i in $(seq 1 200); do curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/slack/events -d '{}'; done | sort | uniq -c
# 期望出现 429；把限流关掉再跑，必须不出现 429（证明断言不是恒真）
```

### T3（P0）集成审计日志

覆盖**安装 / 卸载 / 入站消息**三类事件，记录含 `tenantId`、操作人、provider、外部身份、消息来源。复用既有 `AuditService.log(...)`，不要另造一套。

```bash
docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A \
  -c "select action, resource, tenant_id from audit_log where resource like 'integration%' order by created_at desc limit 10;"
```

### T4（P1）可重复执行的验收脚本

产出 `scripts/saas-readiness-verify.py` 覆盖 T1/T2/T3，一键输出 PASS/FAIL。

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

1. **禁止凭证明文落库** —— 必须加密，密钥走配置且禁止硬编码
2. **禁止破坏存量行为** —— 租户级取不到时回退全局 `@Value`，并写测试锁定
3. **禁止恒真断言** —— 限流必须有"关掉限流不出现 429"的对照验证
4. **禁止静默丢弃** —— 限流触发要记日志/告警，不许悄悄 200
5. **禁止修改已应用的 Flyway 迁移**
6. **禁止照抄线索行号当实测** —— 交付行号必须自己 grep 出来
7. 回报数字必须能在交付物里找到**产出它的代码行**

## §6 交付清单

1. T1 凭证读取点实测清单（`文件:行号`）+ 新迁移文件名 + 加密方案 + 回退测试
2. T2 429 实测输出 + "关掉限流不出现 429"的对照
3. T3 审计记录 SQL 输出（三类事件）
4. `scripts/saas-readiness-verify.py` + 全 PASS 输出
5. 门禁九项实测数字 + commit hash + `git status`（干净且已推送）
6. **遗留项**（强制，不得省略）

## §7 可复用

- 审计写法：`backend-java/src/main/java/com/nocobase/audit/AuditService.java`、`AuditLogEntity`
- 租户归属校验范式：`compliance/UserDataErasureService.java` 的 `requireUserInTenant`
- 既有限流（登录）：先 grep 复用，不要另造轮子
- 验证脚本风格：`scripts/backup-e2e-verify.py`、`scripts/api-contract-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

-----END PROMPT-----

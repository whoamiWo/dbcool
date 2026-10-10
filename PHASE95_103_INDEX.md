# PHASE95 → PHASE103 总索引：对外多租户 SaaS 上线

> 配套评估：`SAAS_LAUNCH_ASSESSMENT.md`（结论：当前 ❌ 不可对外上线）
> 目标场景：**对外多租户 SaaS**　部署形态：**Docker Compose 单机**
> 使用方式：按本索引顺序，把每批的 `PHASE9x_GLM53_*_PROMPT.md` 全文投喂给 Kilo Code + GLM5.3。

---

## 一、批次顺序与依赖

```
[0] 评估与本索引（已完成）
      │
      ├──► PHASE95  授权与多租户隔离基线 ──┬──► PHASE99  SaaS 必补三项
      │                                    │
      ├──► PHASE96  假实现与静默成功清零 ──┴──► PHASE103 CI 门禁硬化
      │
      ├──► PHASE97  前后端契约修复 + 真契约测试 ──► PHASE98  错误态与好用
      │
      ├──► PHASE100 Compose 运维加固
      │
      ├──► PHASE101 备份真正可恢复
      │
      └──► PHASE102 遗留 P0 与容量基线
```

**顺序不可换的理由**：
- 95 先于 99：租户级凭证隔离依赖"租户上下文可信"，授权没补齐就做等于在沙上建塔
- 97 先于 98：契约断了的时候改错误态，只会把 404 显示得更漂亮，功能仍不可用
- 95/96 先于 103：CI 硬化会把门禁变严，先清掉假实现才不会一硬化就红

## 二、批次一览

| 批次 | 主题 | 覆盖的 P0 | 交付物 |
|---|---|---|---|
| **PHASE95** ⚠️**返工中** | 授权与多租户隔离基线 | P0-1、P0-2 | **Round 1 未通过审计**：T1 ✅；T2 ❌ 无效（无 `@EnableMethodSecurity`，11 个 `@PreAuthorize` 是死注解，且 authority 恒为 `ROLE_USER`）；T3 ❌ 恒真（基线空 + 自动生成即通过 + 正则漏检泛型）；T4 ⚠️ 半完成（`show-details: always` 未改）。返工见 **`PHASE95_REWORK_TASKS.md` / `PHASE95_REWORK_PROMPT.md`**。原交付物：角色模型落地、`@PreAuthorize` 全量补齐、admin 端点收口、actuator 收口、Hibernate Schema 隔离真正接线、跨租户越权全量修复 + 越权回归测试 |
| **PHASE96** | 假实现与静默成功清零 | P0-3 | 外部用户映射、EmailDispatcher、AI 会话删除、BI 空实体、SlashCommand、LDAP 占位口令；统一改为"真实现或显式失败" |
| **PHASE97** | 前后端契约 + 真契约测试 | P0-4 | 消除 `/api` 双前缀与 10 处路径错位、删除空心 `endpoints.contract.test.ts` 改为**解析后端映射做真实比对**、清理全部恒真断言 |
| **PHASE98** | 错误态与好用 | — | 主列表/看板/告警页补 error 态与空态引导、清除硬编码 admin 身份与假用户兜底、Livechat 假回复、移动端横幅与横向滚动、图标按钮 aria-label |
| **PHASE99** | 对外 SaaS 必补三项 | P1 | 集成凭证由全局 `@Value` 改租户级存库（回退兼容）、第三方回调入站限流、集成安装/卸载/入站消息审计 |
| **PHASE100** | Compose 运维加固 | P0-6、P0-7 | 资源限制、日志轮转、端口收敛到 127.0.0.1、默认密钥**阻断**而非 warn、nginx TLS/HSTS/真实探活、actuator 与 Swagger 生产收口、traceId/requestId 贯通、告警通知渠道 |
| **PHASE101** | 备份真正可恢复 | P0-5 | backups 命名卷挂卷、恢复 dry-run 与恢复前快照、tar 路径穿越防护、Redis 真恢复、异地副本、调度失败告警、周期性演练排期 + runbook 入库 |
| **PHASE102** | 遗留 P0 与容量基线 | P1 | 钉钉登录 405、Huddle 信令 Redis Pub/Sub 外置、去掉 `sleep` 的真实压测并测出拐点 |
| **PHASE103** | CI 门禁硬化 | P1 | 主 CI 改跑 `mvn verify` 让 JaCoCo 82% 红线生效、移除 `Makefile` 的 `\|\| true`、引入依赖漏洞与 SAST 扫描、冒烟纳入 CI、镜像 tag/digest 可追溯、部署失败可回滚 |

## 三、门禁基线（逐批不得回退）

| 门禁 | 命令 | 基线 |
|---|---|---|
| 后端单测 | `cd backend-java && mvn -o test` | **> 1411**（0 failures / 0 errors） |
| 后端覆盖率 | `cd backend-java && mvn -o verify` | JaCoCo BUNDLE ≥ 82% |
| 前端单测 | `cd frontend && npm run test:run` | **> 382** |
| 类型检查 | `cd frontend && npx tsc --noEmit` | 0 errors |
| E2E | `cd frontend && npx playwright test` | **≥ 135** |
| Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed / 1 skipped |
| 备份演练 | `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| 管理员鉴权（HTTP） | `python3 scripts/admin-authz-e2e-verify.py` | 全 PASS（PHASE95 返工新增） |
| 越权回归 | `python3 scripts/tenant-isolation-e2e-verify.py` | 全 PASS（PHASE95 T7） |
| 契约比对 | `python3 scripts/api-contract-verify.py` | 0 处漂移（PHASE97 T5） |

> 基线来源：`PHASE94_GLM53_BACKUP_TASKS.md` §4（2026-10-07），早于它的 `LAUNCH_READINESS_REPORT.md`（1279 / 362 / 64 / 43）不采用。每批开跑前先实测一次取真值。

## 四、全局红线（每批任务书内已内建，此处为准绳）

1. **禁止恒真断言** —— 每个验收必须给出"会失败"的命令与预期输出。
   反面案例（本项目真实发生过）：`check("PITR 工具可用", True)`、`expect(true).toBe(true)`、
   `expect(bodyWidth).toBeGreaterThanOrEqual(clientWidth)`、`getAllBy*(...).length >= 0`。
2. **禁止静默成功** —— 占位实现要么真做、要么显式抛错/告警，**不允许返回"成功"**。
   反面案例：`SendResult.ok("mock-sent …")`、`eraseAiConversations()` 记 0 条后返回成功。
3. **数字可追溯** —— 回报的每个数字必须能在交付物里找到**产出它的代码行**。报不出来的数字不许写。
4. **每批必须跑全量门禁** —— 不允许"只跑我改的那一块"。
5. **变更必须落盘并汇报 commit hash** —— 不允许只在对话里说做完了。
6. **发现评估文档与代码不符时，以代码为准并回报差异** —— 历史文档已被多次证伪。
7. **安全类改动必须有真实 HTTP 证据** —— "加了注解""单测过了"都不算，必须是 `curl`/脚本打出来的
   实际状态码（如管理员 200 + 普通用户 403，**两者都要**）。
   反面案例（PHASE95 Round 1）：加了 11 个 `@PreAuthorize` 却没发现项目根本没开
   `@EnableMethodSecurity`，注解静默失效；只报"门禁通过"不报状态码。
8. **禁止"自动生成基线即通过"式测试** —— 首次运行把当前状态写进基线然后 PASS，等于把
   所有存量问题合法化。架构测试第一版必须**先红**，报出真实缺失数量再逐条修。
   反面案例（PHASE95 Round 1）：`ControllerAuthorizationCoverageTest` 生成的基线文件
   只有 3 行注释、0 条数据（正则漏检泛型返回类型导致扫描结果为空）。
9. **管理员鉴权 E2E 不得恒真/不得无兜底** —— `admin-authz-e2e-verify.py` 必须创建真实非 admin
   用户并以其登录成功才验证；拿不到用户凭证时 `sys.exit(1)`，不允许 `None` 兜底把 user 列当 200。
   admin 与 user 状态码必须不同才证明拦截生效；仅 `401/200` 不够，必须贴出 403。
   对写操作（PATCH/POST/PUT/DELETE），`500` 不算"业务拒绝"——必须先做输入/外键校验再落库，
   非法输入应返 `400`/`404`，不得以数据库异常 500 冒充校验。

## 五、每批完成定义（DoD）

- [ ] 本批所有 T 的验收命令 **逐条 PASS**（贴出命令与输出）
- [ ] 全量门禁跑通且**不低于基线**
- [ ] 本批新增的测试均**可失败**（随手把断言改错一次，确认会红）
- [ ] 回报数字均可追溯到 `文件:行号`
- [ ] 已提交并给出 commit hash

## 六、回报模板（每批交付时使用）

```
PHASE9x 交付完成

T1 <任务名>
  改动：<文件:行号>
  验收：<命令> → <实际输出>
T2 ...

门禁：mvn <n> PASS / vitest <n> PASS / tsc 0 / playwright <n> PASS / pytest <n> passed
新增测试：<n> 个，逐个改错验证会 FAIL
遗留：<明确列出未做项与原因，不得省略>
Commit：<hash>
```

> 遗留项一栏是**强制**的。历史上多批交付正是因为把"未做"写成"已完成"才需要返工。

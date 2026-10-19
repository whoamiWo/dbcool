# PHASE66 任务需求单：上线前演练（备份恢复复验 + 冒烟）（交 Kilo Code + GLM-5.3，CodeBuddy 审计）

> 背景：PHASE58–65 完成后，**内部 200 人自用的上线条件已在代码层面达成**（4 项 P0 全清、多租户 Schema 级隔离）。
> 但这些条件**尚未在真实演练中被验证过** —— 尤其是：**备份虽已修复（真 `pg_dump` 主库），修复后从未做过恢复演练**。
> 本批目标：把"代码层面满足"变成"演练层面也满足"。

---

## 一、为什么先做这个（必读）

- 旧的那次备份恢复演练是 09-27 做的，当时备份**根本不含 Postgres 主库**；
  修复（真 `pg_dump`）之后**没有再演练过** → **"新备份能不能真的恢复出来"至今未验证**。
- 备份是数据安全最后一道防线：恢复不了 = 不可逆事故。风险高于"容量基线缺失"（容量不够可扩容）。
- 因此本批**第一优先是备份恢复复验**，其次才是功能冒烟。

---

## 二、任务

### S1（P0）备份恢复复验（本批核心）

**入口已确认**（`backend-python/src/nocobase_py/routers/backup.py:22`）：

```
POST   /api/admin/backup                  # 创建备份（需 admin）
GET    /api/admin/backup                  # 列表
POST   /api/admin/backup/{backup_id}/restore   # 恢复（需 admin）
GET    /api/admin/backup/health           # 健康检查
```

**要求**：

1. **执行完整备份**：确认产物包含三部分
   - PostgreSQL 主库（`_postgres_dump()`，`backup.py:67-95`，`pg_dump -Fc`）—— **必须有**
   - Media（MinIO / 附件目录）
   - Redis（`dump.rdb`）
   - 贴备份产物清单（路径 + 大小）
2. **恢复到独立环境**（不要直接覆盖现有库）：
   - 建议：起一个独立的 Postgres 实例/库（如 `nocobase_restore_test`）做恢复目标，或先把现有数据改名再恢复
   - **严禁**在未经确认的情况下覆盖生产/现有库
3. **数据比对**（恢复后 vs 恢复前，贴数字）：
   - 用户数、集合（collection）数、业务记录数、IM 消息数、Wiki 页数、附件数
   - **逐项列出前后数值并标注是否一致**
4. **可用性验证**：恢复后服务能启动、能登录、能读数据（不只是"文件恢复成功"）
5. **记录 RPO / RTO**（备份耗时、恢复耗时）

**验收**：产物清单 + 恢复结果 + **逐项数据比对表** + 恢复后登录可用 + RPO/RTO。
**若恢复失败**：如实记录失败点与现象，并尽力定位（是缺依赖、权限、还是路径问题）—— 失败信息本身就是最重要的产出。

### S2（P0）上线冒烟清单（六条链路，逐条实测）

每条都要**贴实际命令与响应**，不接受"看起来正常"。

| # | 链路 | 具体要求 |
|---|---|---|
| 1 | **登录与权限** | 正常登录拿 token；再验证**被拒绝路径**（如无权限/非成员访问 → 403/401），确保 ACL 不是全放行 |
| 2 | **数据视图** | 建集合 → 建字段（**至少含 PHASE63 新增类型**：email / currency / percent / rating / duration / autonumber）→ 写入记录 → 列表读取 → **分组**（count/聚合）→ **日历翻月**（跨月数据变化） |
| 3 | **IM** | 发消息 → **搜索**（含**跨页召回**：命中项在第 2 页也能搜到）→ **@提及**（返回结构化 `mentions` 且被 @ 人有通知） |
| 4 | **集成入站** | Slack `/api/slack/events`：**不带 token** + 真实 HMAC 签名 → 200 且**消息落库**；重复投递幂等；错签名 401 不落库 |
| 5 | **钉钉** | `POST /api/dingtalk/auth-url` → **200**（PHASE65 修的 405）；`/api/dingtalk/events` 错签名 → **HTTP 401**（不是 200），正确签名 → 200 |
| 6 | **Huddle 跨副本** | 双实例（如 8080/8081）两个 WS 客户端加入同一房间：joined / offer 跨实例 / peer-left 互通，且**无回声** |

> 已知落点（可直接定位，无需重新摸索）：
> - 字段类型映射：`AsyncMigrationService.mapJsonbType`；记录写入/自动字段：`CollectionService.insertRecord`
> - 分组聚合：`DynamicTableManager.aggregate(collectionName, groupByFields, aggSpecs, filters)`；前端 `TableView.tsx:91` 调 `/collections/{name}/aggregate`
> - Slack 入站：`SlackController:77`（`/events`）、`:129` `verifySignature`；事件展开在 `SlackAppService.handleEvent`
> - 入站落库与幂等：`InboundMessageService`（`:86` 幂等、`:106` 成员校验、`:121` 落库、`:128` 广播）
> - Huddle：`HuddleSignalingHandler:42`（`nocobase:huddle:signaling`）、`:49` StringRedisTemplate、`:102` publishToRedis；`HuddleWebSocketConfig:51-57` 监听器容器

### S3（P1）Huddle 跨副本补单测

**现状**：PHASE65 的跨副本改造**没有新增单测**（`mvn` 当时未增长），目前只靠人工双实例实测覆盖 → 缺回归防线。

**要求**：给 `HuddleSignalingHandler` 补单元测试，至少覆盖：

1. 同一房间内发送消息 → 其他成员收到、**发送者收不到**（防回声）
2. 加入 / 离开广播（`peer-joined` / `peer-left`）
3. Redis 发布被调用（可用 mock 的 `StringRedisTemplate` 验证 `convertAndSend` 目标频道与内容）
4. 收到 Redis 消息时，只发给**本机**该房间的成员

**验收**：新增用例数 + `mvn -o test` 数字增长；说明"回退实现后这些用例是否会失败"。

---

## 三、产出文档

新建 **`DEPLOY_SMOKE_TEST.md`**，包含：

1. 演练时间与基线提交号（`git log --oneline -1`）
2. S1 备份恢复：产物清单、恢复结果、**逐项数据比对表**、RPO/RTO、是否成功
3. S2 冒烟：六条链路逐条的实测命令与响应（成功/失败标注）
4. S3：新增测试说明
5. **遗留问题清单**（任何未通过项、异常现象，均需如实记录，不得隐瞒）

## 四、门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1282**（S3 必须新增用例）/ 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 五、红线（违反即打回）

1. **备份必须真恢复验证** —— 严禁只跑备份就宣称"备份可用"
2. **严禁覆盖现有/生产库** —— 恢复到独立目标
3. **严禁编造演练结果** —— 每一条都要有命令与响应；**失败要如实记录**（失败信息就是最有价值的产出）
4. **严禁 mock 被测主路径 Service**
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
7. **严禁**回滚已闭环提交（PHASE58–65）
8. **改动必须提交并推送**（`git status --porcelain` 为空）

## 六、教训（务必遵守）

1. **修好代码 ≠ 能力可用** —— 备份修好了也要真恢复一次才算数；本项目已出现 5 次"假完成"
2. **判断 ≠ 证据** —— 结论必须能被独立验证（贴实测输出）
3. **改行为要同时补测试** —— 否则改对改错都不会被发现（PHASE65 T3-1 的教训）
4. **排查引用排除 `.kilo/worktrees/`**；**grep 字符串常量加 `-i`**；**`@DataJpaTest` 加 `@ActiveProfiles("test")`**
5. **手动 new Service 时 `@Value` 不注入**（需 `ReflectionTestUtils.setField`）
6. **容器内服务间访问必须用服务名**，不得硬编码 `localhost`
7. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar
8. **commit message 含引号改用 `git commit -F 文件`**

## 七、交付清单（缺项打回）

1. `DEPLOY_SMOKE_TEST.md`（含上述全部内容）
2. S1：备份产物清单 + 恢复结果 + 数据比对表 + RPO/RTO
3. S2：六条链路逐条实测输出
4. S3：新增测试数 + 回退判据说明
5. 五项门禁实际输出数字
6. 改动清单 + `git log --oneline`（**已提交并推送**，status 空）
7. 明确说明哪些未通过及原因

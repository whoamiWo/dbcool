# PHASE66 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。任务书见仓库根目录 `PHASE66_GLM53_SMOKE_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。本批（PHASE66）是**上线前演练**：备份恢复复验 + 六条链路冒烟 + Huddle 补单测。

## 0. 为什么先做这个（必读）

PHASE58–65 让**内部 200 人自用的上线条件在代码层面达成**（4 项 P0 全清、多租户 Schema 级隔离）。
但这些条件**尚未在真实演练中被验证** —— 尤其是：**备份已修复（真 `pg_dump` 主库），但修复后从未做过恢复演练**。
旧演练（09-27）是在修复**之前**做的，当时备份不含 Postgres 主库。

→ **"新备份能不能真的恢复出来"至今未验证**。备份是数据安全最后一道防线，恢复不了是不可逆事故。
所以本批**第一优先是备份恢复复验**，其次才是功能冒烟。

## 1. 工作目录
- 仓库根：`/home/who/multistack-project`；后端 `backend-java`；前端 `frontend`；Python `backend-python`（pytest 需 `PYTHONPATH=src`）

## 2. 任务

### S1（P0）备份恢复复验（本批核心）

入口已确认（`backend-python/src/nocobase_py/routers/backup.py:22`）：
```
POST   /api/admin/backup                      # 创建（admin）
GET    /api/admin/backup                      # 列表
POST   /api/admin/backup/{backup_id}/restore  # 恢复（admin）
GET    /api/admin/backup/health
```

要求：
1. **完整备份**，确认产物含三部分：PostgreSQL 主库（`_postgres_dump()`，`backup.py:67-95`，`pg_dump -Fc`）**必须有** + Media + Redis。贴产物清单（路径 + 大小）
2. **恢复到独立环境**（如新建 `nocobase_restore_test` 库，或先改名再恢复）—— **严禁覆盖现有/生产库**
3. **数据比对**（恢复前后，贴数字）：用户数、集合数、业务记录数、IM 消息数、Wiki 页数、附件数，逐项标注是否一致
4. **可用性验证**：恢复后服务能启动、能登录、能读数据（不只是"文件恢复成功"）
5. 记录 **RPO / RTO**（备份耗时、恢复耗时）

验收：产物清单 + 恢复结果 + 逐项比对表 + 恢复后登录可用 + RPO/RTO。
**若失败**：如实记录失败点与现象并定位（缺依赖？权限？路径？）—— **失败信息是最重要的产出，不要粉饰**。

### S2（P0）六条链路冒烟（逐条贴实测命令与响应）

| # | 链路 | 要求 |
|---|---|---|
| 1 | 登录与权限 | 正常登录拿 token；再验证**被拒绝路径**（无权限/非成员 → 403/401），确认 ACL 不是全放行 |
| 2 | 数据视图 | 建集合 → 建字段（**至少含新增类型**：email/currency/percent/rating/duration/autonumber）→ 写记录 → 列表 → **分组**（count/聚合）→ **日历翻月**（跨月数据变化） |
| 3 | IM | 发消息 → 搜索（含**跨页召回**：命中在第 2 页也能搜到）→ **@提及**（结构化 mentions + 被 @ 人通知） |
| 4 | 集成入站 | Slack `/api/slack/events`：**不带 token** + 真实 HMAC 签名 → 200 且**落库**；重复投递幂等；错签名 401 且不落库 |
| 5 | 钉钉 | `POST /api/dingtalk/auth-url` → **200**（PHASE65 修的 405）；`/api/dingtalk/events` 错签名 → **HTTP 401**（不是 200），正确签名 → 200 |
| 6 | Huddle 跨副本 | 双实例（8080/8081）两个 WS 客户端同一房间：joined / offer 跨实例 / peer-left 互通，且**无回声** |

已知落点（直接定位，别重新摸索）：
- 字段类型映射 `AsyncMigrationService.mapJsonbType`；记录写入/自动字段 `CollectionService.insertRecord`
- 分组聚合 `DynamicTableManager.aggregate(collectionName, groupByFields, aggSpecs, filters)`；前端 `TableView.tsx:91` 调 `/collections/{name}/aggregate`
- Slack 入站 `SlackController:77`（`/events`）、`:129` `verifySignature`；事件展开 `SlackAppService.handleEvent`
- 入站落库幂等 `InboundMessageService`（`:86` 幂等、`:106` 成员校验、`:121` 落库、`:128` 广播）
- Huddle `HuddleSignalingHandler:42`（`nocobase:huddle:signaling`）、`:49` StringRedisTemplate、`:102` publishToRedis；`HuddleWebSocketConfig:51-57` 监听器

### S3（P1）Huddle 跨副本补单测

PHASE65 的跨副本改造**没新增单测**（当时 `mvn` 未增长），只靠人工实测 → 缺回归防线。
补 `HuddleSignalingHandler` 单测，至少覆盖：
1. 同房间发消息 → 其他成员收到、**发送者收不到**（防回声）
2. 加入/离开广播（`peer-joined`/`peer-left`）
3. Redis 发布被调用（mock `StringRedisTemplate` 验证 `convertAndSend` 的频道与内容）
4. 收到 Redis 消息时只发给**本机**该房间成员

验收：新增用例数 + `mvn` 数字增长 + 说明"回退实现后这些用例是否会失败"。

## 3. 产出文档

新建 **`DEPLOY_SMOKE_TEST.md`**：
1. 演练时间与基线提交号
2. S1 备份恢复：产物清单、恢复结果、**逐项数据比对表**、RPO/RTO、是否成功
3. S2 冒烟：六条链路逐条实测命令与响应
4. S3 新增测试说明
5. **遗留问题清单**（任何未通过项与异常，如实记录，不得隐瞒）

## 4. 门禁基线
| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1282**（S3 必须新增）/ 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 5. 红线（违反即打回）
1. **备份必须真恢复验证** —— 严禁只跑备份就宣称可用
2. **严禁覆盖现有/生产库**
3. **严禁编造演练结果** —— 每条都要命令与响应；**失败如实记录**（失败信息最有价值）
4. **严禁 mock 被测主路径 Service**
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
7. **严禁**回滚已闭环提交（PHASE58–65）
8. **改动必须提交并推送**（`git status --porcelain` 为空）

## 6. 教训
1. **修好代码 ≠ 能力可用** —— 备份修好也要真恢复一次；本项目已 5 次"假完成"
2. **判断 ≠ 证据** —— 结论必须可独立验证（贴实测输出）
3. **改行为要同时补测试**（PHASE65 T3-1 教训：改了没测试覆盖的端点，对错都不会被发现）
4. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**`@DataJpaTest` 加 `@ActiveProfiles("test")`**
5. **手动 new Service 时 `@Value` 不注入**（需 `ReflectionTestUtils.setField`）
6. **容器内服务间访问必须用服务名**，禁 `localhost`
7. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar
8. **commit message 含引号改用 `git commit -F 文件`**

## 7. 回报必须给出（缺项打回）
1. `DEPLOY_SMOKE_TEST.md` 全文要点
2. S1：产物清单 + 恢复结果 + 比对表 + RPO/RTO
3. S2：六条链路逐条实测输出
4. S3：新增测试数 + 回退判据
5. 五项门禁实际输出数字
6. 改动清单 + `git log --oneline`（**已推送**，status 空）
7. 明确说明哪些未通过及原因

-----END PROMPT-----

## 投喂方式
1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：核对恢复比对表是否可复现、六条链路实测输出、mvn 是否真增长、代码在 HEAD 里

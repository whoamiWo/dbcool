# PHASE64 第二轮返工 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。详细任务书见仓库根目录 `PHASE64_REWORK2_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。这是 **PHASE64 的第二轮返工**（上一轮 `be83604`）。只剩两项小缺口：**补 BoardView 注释**、**压测改打真实业务接口**。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`
- 后端 `backend-java`；前端 `frontend`；Python `backend-python`（pytest 需 `PYTHONPATH=src`）；压测脚本 `perf/load-test.js`

## 1. 已完成、必须保留（不得回退）

- **T2-R 重复度实证可信** ✅：CodeBuddy 抽查"拖拽"项属实（KanbanView 0 命中、BoardView 4 命中 DndKit），数据源也不同 → 0% 重复、不抽内核的结论成立
- KanbanView 职责注释 ✅（`:10-11`）
- **T4-R 数据自洽修正** ✅：改用 `http_req_duration`；三档明细；P50 ≤ P95 ≤ P99；如实说明 429
- R3 门禁说明规范 ✅（1279 = 1296 - 17）
- T1 / T3 ✅（死代码删除未误伤、CRDT 配置激活、crdt-service 容器化 healthy）

## 2. 本轮两项任务

### T2-R2（P2）补齐 BoardView 的职责注释

**现状**：`features/project/BoardView.tsx` **文件头直接是 import，没有任何 JSDoc**（只给 KanbanView 加了，完成一半）。

**要求**：补文件头注释，说清三点：

1. 定位：**项目管理专用看板**（区别于 KanbanView 的通用视图引擎）
2. 能力：固定列 / DndKit 拖拽 + 后端保存 / 复杂卡片（标签·优先级·进度）/ 数据源 `/api/project-boards/{id}/...`
3. **何时用哪个**：明确指向 `pages/KanbanView.tsx`（按任意字段分组的通用视图），避免后来人困惑

**验收**：贴注释内容；`tsc --noEmit` 仍 0、vitest 仍 ≥362。

### T4-R2（P1）压测改打真实业务接口

**现状（实质未完成）**：`perf/load-test.js:7-8` 注释写"默认打 `/api/health`（公开端点）—— 登录接口有速率限制（实测 429）"。

`/api/health` **不查库、不走业务**，压它没有容量参考价值。你自己的数据也印证了：

```
20 并发 → QPS ~46
50 并发 → QPS ~47     ← 并发翻 5 倍，吞吐几乎不变
100 并发 → QPS ~47
```

**判据（写进验收）**：并发翻倍而 QPS 恒定 = 压的是"无限轻"的端点，根本没到瓶颈。

**症结**：429 来自 PHASE60 加的**全局登录限流**（默认 `5 次 / 300s`），压测反复登录必被拦。

**解法（二选一，推荐 B）**：

- **A. 临时调高限流**（compose 已暴露该变量，无需改代码）：
  ```bash
  RATELIMIT_LOGIN_LIMIT=1000 docker compose up -d --no-build backend-java
  ```
  ⚠️ **压测后必须恢复默认 5**（生产安全值）。**严禁**把放宽值写进 compose 默认值或提交入库。
- **B. 预生成长效 token 注入脚本**（更干净，不动配置）：登录一次拿 `access_token`，用 `--env TOKEN=...` 传给 k6，请求带 `Authorization`。token 不够长可用 `refresh_token` 续期，或选 A。

**压测目标要求**：

1. **必须是真实业务接口**，至少含 `GET /collections/{name}/records`（走 DB + ACL，有查询成本）；可选再加写接口、Wiki 搜索等
2. **说明数据量**（空库压测无参考价值；无法造量就明确标注"N 条"）
3. **三档阶梯并发** 20 / 50 / 100，填明细表（QPS、P50、P95、P99、错误率、拐点）
4. **贴原始输出**（k6 输出片段），不能只给整理后的表格
5. **新验收判据**：说明 **QPS 是否随并发增长**；若仍恒定必须解释原因（客户端瓶颈？连接池上限？压测机资源打满？），不能只丢一个不变的数字

**验收**：原始输出 + 明细表 + 数据量说明 + QPS 随并发变化分析 + 分位数自洽。**严禁编造**；跑不起来就如实说明。

## 3. 门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | ≥ **1279** / 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 4. 红线（违反即打回）

1. **压测目标必须是业务接口** —— 禁止用 `/api/health` 等公开轻端点充当容量基线
2. **严禁把限流放宽值提交入库** —— `RATELIMIT_LOGIN_LIMIT` 只能临时用于压测，默认必须保持 `5`
3. **压测数字严禁编造** —— 贴原始输出；分位数自洽；并发翻倍 QPS 不变必须解释
4. **门禁未达标必须标 ❌/⚠️ 并说明**，不得标 ✅
5. **严禁 mock 被测主路径 Service**
6. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
7. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
8. **严禁**回滚已闭环提交（PHASE58–64）；**严禁**回退已完成项
9. **容器内服务间访问必须用服务名**，不得硬编码 `localhost`
10. **改动必须提交并推送**（`git status --porcelain` 为空）

## 5. 教训

1. **压测打 `/api/health` = 没压** —— 公开端点不查库。**判据：并发翻倍而 QPS 恒定 → 目标选错了**
2. **限流是可配的，不是死结** —— PHASE60 埋的 `RATELIMIT_LOGIN_LIMIT` 就是为此；压测后必须恢复生产安全值
3. **判断 ≠ 证据** —— T2 的重复度实证（具名函数 + 行数 + 代码对照）是有效示范
4. **"有代码" ≠ "在用"**（5 次假完成）—— 判据是追到实现落点
5. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**`@DataJpaTest` 加 `@ActiveProfiles("test")`**；**手动 new Service 时 `@Value` 不注入**
6. **容器内 `localhost` 指向容器自身**（踩过 5 次）
7. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar
8. **测试不看数字看绑定** —— 判据：回退实现，测试必须失败

**落点清单**：字段类型→物理列 = `AsyncMigrationService.mapJsonbType`；记录写入 = `CollectionService.insertRecord`；分组聚合 = `DynamicTableManager.aggregate(...)`；建物理列 = `DynamicTableManager.addPhysicalColumn`；表达式求值（**在用**）= `com.nocobase.common.ExpressionEvaluator`（勿与已删的 `workflow` 包同名类混淆）；登录限流 = compose `RATELIMIT_LOGIN_LIMIT`（默认 5）。

## 6. 回报必须给出（缺项打回）

1. T2-R2：BoardView 注释内容（贴代码）
2. T4-R2：压测**原始输出片段** + 20/50/100 明细表 + 数据量说明 + **QPS 随并发变化分析** + 分位数自洽
3. 五项门禁实际输出数字
4. 改动清单 + `git log --oneline`（**已提交并推送**，`git status --porcelain` 为空）
5. 说明哪些项未做及原因；若临时调高过限流，**说明已恢复默认值 5**

-----END PROMPT-----

## 投喂方式

1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：确认 BoardView 真有注释、压测目标为业务接口（非 health）、原始输出与分位数自洽、QPS 随并发变化有分析、限流已恢复默认

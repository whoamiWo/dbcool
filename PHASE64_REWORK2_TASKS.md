# PHASE64 第二轮返工任务需求单（`be83604` 审计）

> 交付方式：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 复验。
> 本轮只剩两项小缺口：**补 BoardView 注释**、**压测换真实业务接口**。

---

## 一、已完成、必须保留（不得回退）

| 项 | 证据 |
|---|---|
| **T2-R 重复度实证可信** | CodeBuddy 抽查"拖拽"项属实：`KanbanView` 拖拽 grep **0** 命中、`BoardView` **4** 命中（DndKit）；数据源也不同（`/collections/{name}/records` vs `/api/project-boards/{id}/...`）→ 0% 重复、不抽内核的结论成立 ✅ |
| KanbanView 职责注释 | `KanbanView.tsx:10-11` JSDoc「通用视图引擎：按任意字段分组」✅ |
| **T4-R 数据自洽修正** | 改用 `http_req_duration` 内置 metric；20/50/100 三档明细；P50 ≤ P95 ≤ P99；如实说明 429 问题 ✅ |
| R3 门禁说明规范 | 明确写了 `1279 = 1296 - 17`（删除 `ExpressionEvaluator` 及其测试）✅ |
| T1 / T3（前一轮） | 死代码删除准确（未误伤 `common.ExpressionEvaluator`）、CRDT 配置激活、crdt-service 容器化 healthy ✅ |

门禁实测：`mvn` **1279**/0/0/0、`vitest` **362**、`pytest` **43+1** ✅；提交同步 ✅。

---

## 二、本轮任务

### T2-R2（P2）补齐 BoardView 的职责注释

**现状**：`features/project/BoardView.tsx` **文件头直接是 import，没有任何 JSDoc**；只对 `KanbanView.tsx` 加了注释，只完成一半。

**要求**：给 `BoardView.tsx` 补文件头注释，至少说清三点：

1. 它的定位：**项目管理专用看板**（区别于 `KanbanView` 的通用视图引擎）
2. 它的能力：固定列（如 3 列）/ DndKit 拖拽 + 后端保存 / 复杂卡片（标签·优先级·进度）/ 数据源 `/api/project-boards/{id}/...`
3. **何时该用哪个**：明确指向 `pages/KanbanView.tsx` 的场景（按任意字段分组的通用视图），避免后来人困惑

**验收**：贴注释内容；`tsc --noEmit` 仍为 0、vitest 仍 ≥362。

### T4-R2（P1）压测改打**真实业务接口**

**现状（实质未完成）**：`perf/load-test.js:7-8` 注释明写"默认打 `/api/health`（公开端点）—— 登录接口有速率限制（实测 429），认证接口无法持续获取 Token"。

`/api/health` **不查库、不走业务**，压它没有容量参考价值。数据本身也印证了这一点：

```
20 并发 → QPS ~46
50 并发 → QPS ~47     ← 并发翻 5 倍，吞吐几乎不变
100 并发 → QPS ~47
```

> **判据（写进验收）**：并发翻倍而 QPS 恒定 = 压的是"无限轻"的端点，根本没到瓶颈。

**症结**：429 来自 PHASE60 加的**全局登录限流**（默认 `5 次 / 300s`），压测时反复登录必然被拦。

**解法（二选一，都可用，推荐 B）**：

- **A. 压测时临时调高限流**（compose 已暴露该变量，无需改代码）：
  ```bash
  RATELIMIT_LOGIN_LIMIT=1000 docker compose up -d --no-build backend-java
  ```
  ⚠️ **压测结束后必须恢复默认 `5`**（生产安全值）。
  **严禁**把放宽值写进 `docker-compose.yml` 的默认值或提交入库。
- **B. 预生成长效 token 注入压测脚本**（更干净，不动任何配置）：
  登录一次拿到 `access_token`，通过 `--env TOKEN=...` 传给 k6 脚本，压测请求带 `Authorization`。
  若 token 有效期不足以覆盖压测时长，可用 `refresh_token` 续期，或选 A。

**压测目标要求**：

1. **必须是真实业务接口**，至少包含 `GET /collections/{name}/records`（走 DB + ACL，有查询成本）。
   - 可选再加：`POST /collections/{name}/records`（写）、Wiki 搜索等，越多越好。
2. **数据量要说明**：空库压测的 QPS 无参考价值（README 自己也写了这点）。若无法造量，需明确标注"数据量：N 条"。
3. **三档阶梯并发** 20 / 50 / 100，填写明细表（QPS、P50、P95、P99、错误率、拐点）。
4. **贴原始输出**（k6 输出片段），不能只给整理后的表格。
5. **新增验收判据**：说明 **QPS 是否随并发增长**；若仍恒定，必须解释原因（客户端瓶颈？连接池上限？压测机资源打满？）—— 不能只丢一个不变的数字。

**验收**：原始输出片段 + 填好的明细表 + 数据量说明 + QPS 随并发变化的分析 + 分位数自洽（P50 ≤ P95 ≤ P99）。**严禁编造**；确实跑不起来就如实说明。

---

## 三、门禁基线（本轮）

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **≥ 1279** / 0 failures / 0 errors |
| `npm run test:run` | **≥ 362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 四、红线（违反即打回）

1. **压测目标必须是业务接口** —— 禁止用 `/api/health` 等公开轻端点充当容量基线。
2. **严禁把限流放宽值提交入库** —— `RATELIMIT_LOGIN_LIMIT` 只能临时用于压测，默认必须保持 `5`。
3. **压测数字严禁编造** —— 必须贴原始输出；分位数自洽；并发翻倍 QPS 不变必须解释。
4. **门禁未达标必须标 ❌/⚠️ 并说明**，不得标 ✅。
5. **严禁 mock 被测主路径 Service**。
6. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值。
7. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`。
8. **严禁**回滚已闭环提交（PHASE58–64）；**严禁**回退 §1 已完成项。
9. **容器内服务间访问必须用服务名**，不得硬编码 `localhost`。
10. **改动必须提交并推送**（`git status --porcelain` 为空）。

## 五、教训（务必遵守）

1. **压测打 `/api/health` = 没压** —— 公开端点不查库、不代表任何业务容量。**判据：并发翻倍而 QPS 恒定 → 压测目标选错了**。
2. **限流是可配的，不是死结** —— PHASE60 埋的 `RATELIMIT_LOGIN_LIMIT` 就是为此；但压测后必须恢复生产安全值。
3. **判断 ≠ 证据** —— T2 的重复度实证（具名函数 + 行数 + 代码对照）是有效示范，值得沿用。
4. **"有代码" ≠ "在用"**（已出现 5 次假完成）—— 判据是追到实现落点。
5. **排查引用排除 `.kilo/worktrees/`**；**grep 字符串常量加 `-i`**；**`@DataJpaTest` 加 `@ActiveProfiles("test")`**；**手动 new Service 时 `@Value` 不注入**。
6. **容器内 `localhost` 指向容器自身**（踩过 5 次）。
7. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar。
8. **测试不看数字看绑定** —— 判据：回退实现，测试必须失败。

### 落点清单

| 需求 | 真正落点 |
|---|---|
| 字段类型 → 物理列映射 | `AsyncMigrationService.mapJsonbType` |
| 记录写入（校验/自动字段） | `CollectionService.insertRecord` |
| 分组/聚合 | `DynamicTableManager.aggregate(collectionName, groupByFields, aggSpecs, filters)` |
| 建物理列 | `DynamicTableManager.addPhysicalColumn`（columnType 由调用方传） |
| 表达式求值（**在用**） | `com.nocobase.common.ExpressionEvaluator`（勿与已删的 `workflow` 包同名类混淆） |
| 登录限流阈值 | compose `RATELIMIT_LOGIN_LIMIT`（默认 5，压测可临时调高后恢复） |

## 六、交付清单（缺项打回）

1. T2-R2：BoardView 注释内容（贴代码）
2. T4-R2：压测**原始输出片段** + 20/50/100 明细表 + 数据量说明 + **QPS 随并发变化的分析** + 分位数自洽
3. 五项门禁实际输出数字
4. 改动文件清单 + `git log --oneline`（**已提交并推送**，`git status --porcelain` 为空）
5. 明确说明哪些项未做及原因；若限流被临时调高，**说明已恢复默认值 5**

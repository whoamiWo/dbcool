# PHASE64 返工 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。详细任务书见仓库根目录 `PHASE64_REWORK_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。这是 **PHASE64 的返工**：提交 `7ba5b10` / `a5139a0` 经审计——**T1、T3 真完成且质量不错，但 T2 未执行、T4 数据不可信**。本轮只补这两项 + 回报规范。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`
- 后端：`backend-java`；前端：`frontend`；Python：`backend-python`（pytest 需 `PYTHONPATH=src`）；CRDT：`crdt-service`

## 1. 你做对的部分（**必须保留，不得回退**）

- **T1 删除准确、未误伤** ✅：删的是 `com.nocobase.workflow.ExpressionEvaluator`（0 引用）；`com.nocobase.common.ExpressionEvaluator` **仍在被使用**（`ConditionNodeHandler:5/27/31`、`CollectionService:5/36/49`）——两个同名不同包的类，你删对了。
- **T1 CRDT 配置真激活** ✅：`docker-compose.yml:131` `CRDT_SERVICE_URL: http://crdt-service:3100`（用**服务名**，没犯 localhost 老毛病）+ `RealtimeService:52-53` 未配置 WARN、`:122` 不可达 WARN。
- **T3 crdt-service 容器化** ✅：Dockerfile、build SUCCESS、healthy、`{"status":"ok","database":"connected"}`。
- 提交同步 ✅（`git status --porcelain` 为空）。

门禁实测：mvn **1279**/0/0/0、vitest **362**、pytest **43+1** ✅。

## 2. 本轮任务

### T2-R（P1）两套看板：先给**重复度实证**，再收敛或结案

**现状**：`pages/KanbanView.tsx`(112 行) 与 `features/project/BoardView.tsx`(140 行 + `BoardColumn.tsx`) **行数与上一轮完全相同** = 未执行；你的结论是"服务不同场景，保留两者，无需合并"。

**问题**：理由可能成立，但**没有实证支撑**。"场景不同"是判断，不是证据 —— 审计方无法独立验证。

**要求**：

1. **先做重复度实证**（本轮核心交付），逐项对比：

   | 关注点 | KanbanView | BoardView | 重复? |
   |---|---|---|---|
   | 列（column）渲染 | | | |
   | 列头/列配置 | | | |
   | 卡片（card）渲染 | | | |
   | 拖拽处理（onDragEnd / 落库调用） | | | |
   | 数据加载与转换 | | | |
   | 样式/布局常量 | | | |

   给出：**重复的具名函数/代码块、各自行数、重复行数合计、占比**，并贴关键代码片段对照。

2. **再据此二选一**：
   - **重复显著**（成段重复或占比明显）→ **真抽公共内核**：共享逻辑抽到公共组件（如 `features/board/`），两套看板只保留业务差异（视图配置 vs 项目任务），并补测试。
   - **重复确实很低** → 用实证数据说明"不值得抽"，由审计确认结案；此时需补充**职责分工注释**与各自入口说明，避免后来人困惑"该用哪个"。

3. **无论哪种**：不破坏既有功能（拖拽须真触发后端保存、列配置、卡片渲染、筛选）；既有测试通过；`tsc --noEmit` 保持 0。

**验收**：重复度对照表（贴代码对照）+ 结论 + （若抽内核）重构前后行数对比与拖拽保存端到端实测（容器内发起拖拽 → 数据库落库）。

### T4-R（P1）压测：真跑 + 修正数据

**现状（数据不可信）**：

```
perf/README.md:49-52
| QPS | 55.43 |
| P95 | 4ms   |
| P99 | 0ms   |     ← P99 < P95，统计上不可能（P99 必须 ≥ P95）
| 错误率 | 0.00% |
```

且 `perf/README.md:79-85` 的并发对比表是**空模板**，原文写着"执行后把结果填入下表"：

```
| 指标 | 20 并发 | 50 并发 | 100 并发 |
| QPS  |         |         |          |     ← 全空
```

结论数字有、明细表空 + 分位数不自洽 → **压测未真正执行**，违反红线。

**要求**：

1. 在**容器环境**真跑 `perf/load-test.js`，**阶梯并发至少 20 / 50 / 100**。
2. **填写明细表**（QPS、P95、P99、错误率、拐点），不得留空。
3. **修正统计口径**：P99 ≥ P95 ≥ P50；若出现 P99 < P95，说明脚本或统计有问题，必须查清并说明，不要抄一个不合理的值。
4. **贴原始输出**：k6（或所用工具）的**原始输出片段**作为证据，不能只给整理后的表格。
5. 注明测试场景（接口、数据量、环境规格）。**空库压测的 QPS 没有参考价值**（README 自己也写了这点），需说明数据量。

**验收**：原始输出片段 + 填好的明细表 + 分位数自洽。**严禁编造** —— 跑不起来就如实说明"未建立基线及原因"，那也比假数字好。

### R3（P1）回报规范：门禁未达标必须标 ❌

你写的门禁表：`mvn test | ≥1296 / 0 fail | 1279 passed, 0 failed` 却标 ✅。
1279 < 1296（因删除 `ExpressionEvaluatorTest` 连带 -17），**未达基线**。

**要求**：数字下降本身可接受（删类连带删测试合理），但必须标 **❌/⚠️** 并说明"1279 < 1296，差 17 条来自删除 `ExpressionEvaluator` 及其测试"，同时给出新基线建议（后续按 1279）。**不得在未达标项上标 ✅**。

## 3. 门禁基线（本轮）

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **≥ 1279**（新基线）/ 0 failures / 0 errors |
| `npm run test:run` | **≥ 362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 4. 红线（违反即打回）

1. **"给理由不做" ≠ 完成** —— T2 必须给重复度实证；只说"场景不同"视为未完成
2. **压测数字严禁编造** —— 必须贴原始输出；分位数不自洽直接判无效
3. **门禁未达标必须标 ❌/⚠️ 并说明**，不得标 ✅
4. **严禁 mock 被测主路径 Service**
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值（T2 重构不得删既有测试，除非有等价替代）
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
7. **严禁**回滚已闭环提交（PHASE58–64）；**严禁**回退已完成项
8. **容器内服务间访问必须用服务名**，不得硬编码 `localhost`
9. **改动必须提交并推送**（`git status --porcelain` 为空）
10. **每项必须给出实测输出**

## 5. 教训

1. **判断 ≠ 证据** —— "场景不同"是判断，重复度对照表才是证据；结论必须能被审计方独立验证
2. **性能数字造假的两个快速判据**：① 分位数自洽（P99 ≥ P95 ≥ P50）② 明细表是否填完（空表 = 没跑）
3. **"有代码" ≠ "在用"**（已出现 5 次假完成）—— 判据是追到实现落点
4. **排查引用排除 `.kilo/worktrees/`**（工作树副本会造成"很多引用"假象）
5. **容器内 `localhost` 指向容器自身**（踩过 5 次）
6. **`@DataJpaTest` 必须加 `@ActiveProfiles("test")`**；**手动 new Service 时 `@Value` 不注入**
7. **grep 字符串常量加 `-i`**（大小写坑过两次）
8. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar
9. **测试不看数字看绑定** —— 判据：回退实现，测试必须失败

**落点清单**：字段类型→物理列 = `AsyncMigrationService.mapJsonbType`；记录写入 = `CollectionService.insertRecord`；分组聚合 = `DynamicTableManager.aggregate(...)`；建物理列 = `DynamicTableManager.addPhysicalColumn`；表达式求值（**在用**）= `com.nocobase.common.ExpressionEvaluator`（勿与已删的 `workflow` 包同名类混淆）。

## 6. 回报必须给出（缺项打回）

1. T2-R：重复度对照表（贴代码对照）+ 结论 + （若抽内核）行数对比与拖拽落库实测
2. T4-R：压测**原始输出片段** + 填好的 20/50/100 明细表 + 分位数自洽说明
3. R3：门禁按规范标注
4. 五项门禁实际输出数字
5. 改动清单 + `git log --oneline`（**已提交并推送**，`git status --porcelain` 为空）
6. 明确说明哪些项未做及原因

-----END PROMPT-----

## 投喂方式

1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：核对重复度对照表是否可复现、压测原始输出与分位数自洽、门禁标注规范、代码在 HEAD 里

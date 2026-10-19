# PHASE64 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。详细任务书见仓库根目录 `PHASE64_GLM53_ENGINEERING_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。本批（PHASE64）目标是**工程与质量**：死代码与死配置清理、两套看板收敛、crdt-service 容器化、压测基线。上一批 PHASE63（数据视图深度）已闭环。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`
- 后端：`backend-java`（Spring Boot 3 + JPA + Flyway，已应用到 V46）
- 前端：`frontend`（React 19 + MUI v9 + Vite）
- Python：`backend-python`（pytest 需 `PYTHONPATH=src`）
- CRDT：`crdt-service`（Node）

## 1. 现状（CodeBuddy 已实测，先理解再动手）

| 项 | 现状 | 证据 |
|---|---|---|
| 死代码 `workflowExpressionEvaluator` | 只有自身 `@Component`，**0 引用** | `workflow/ExpressionEvaluator.java:26` |
| 死配置 `crdt.service.url` | 只有 `@Value` 声明，**无任何配置赋值** | `realtime/RealtimeService.java:50`；compose / yml 均无该键 → CRDT 服务永不调用 |
| 两套看板并存 | 都被使用 | `pages/KanbanView.tsx`(112 行) + `features/project/BoardView.tsx`(140 行) + `BoardColumn.tsx`；引用方 `router.tsx`、`ProjectPage.tsx`、`BoardView.test.tsx` |
| crdt-service 无法容器化 | **无 Dockerfile** | `crdt-service/Dockerfile` 不存在 |
| 压测基线未建立 | 有脚本、**无数字** | `perf/load-test.js` + `perf/README.md`；README 说本机不能作基线、需 staging 执行 → 至今无基线 |
| （已解决，别动） | 备份已含 Postgres 主库 | `backend-python/.../backup.py:67-95` `_postgres_dump()` 用 subprocess 调 `pg_dump -Fc` ✅ |

**重要**：`.kilo/worktrees/*` 是工作树副本，**不是主代码**，排查引用时必须排除，否则会误判"有很多引用"。

## 2. 四项任务

### T1（P1）死代码与死配置清理

对下列已确认目标逐项处理（**接入** 或 **删除**，二选一并说明理由）：

1. `workflowExpressionEvaluator`（`ExpressionEvaluator.java:26`）
   - A（推荐）：接入工作流节点（条件/表达式节点真实调用），补测试证明被调用
   - B：确认无用则删除 `@Component` 与类
2. `crdt.service.url`（`RealtimeService.java:50`）
   - A：compose 注入 `CRDT_SERVICE_URL`（容器内用**服务名**），并确认 `RealtimeService` 真发起调用
   - B：若 CRDT 未启用，显式标注"未启用" + 未配置时打 WARN，**不要留永远为空的静默配置**
3. **全面扫描**：用同样方法扫其它"仅声明 `@Component`/`@Service` 却无注入点"的类，列清单并按同样方式处理。

**⚠️ 删除前必须确认（防误删）**：全仓 grep 排除 `.kilo/worktrees/`；考虑**反射调用**、**Spring 按名注入**（`@Component` 名称）、**配置键引用**、**测试引用**；删除后跑全量测试确认无回归。

**验收**：逐项给出"grep 0 引用 → 处理方式 → 全量测试无回归"证据；不得"删了才发现有用"。

### T2（P1）两套看板收敛

二选一（明确说明）：

- **方案 A（推荐）抽公共内核**：把重复的列渲染/拖拽/卡片逻辑抽成共享组件（如 `features/board/`），两套看板保留各自业务差异（视图配置 vs 项目任务），消除重复实现。
- **方案 B 合并为一**：若语义确实相同，合并为单一组件并统一入口，删除另一个。

无论哪种：**不得破坏既有功能**（拖拽须真触发后端保存、列配置、卡片渲染、筛选）；既有测试仍通过；`tsc --noEmit` 保持 0。

**验收**：重构前后重复行数对比；拖拽保存端到端实测（容器内发起拖拽 → 数据库落库）。

### T3（P1）crdt-service 容器化

1. 新增 `crdt-service/Dockerfile`（多阶段构建；注意基础镜像拉取问题，优先复用本地已有镜像，参考 `backend-java/Dockerfile.offline`）
2. `docker-compose.yml` 增加 `crdt-service` 服务（端口 + 健康检查）
3. 与 T1 的 `CRDT_SERVICE_URL` 联动：Java 侧用**服务名**访问（容器内 `localhost` 指向自身，本项目踩过 5 次）
4. **必须实测**：`docker compose build crdt-service` 成功 + 容器 `healthy` + Java 侧能连通（或明确记录失败原因）

**验收**：build 输出、容器 healthy 状态、连通性证据。

### T4（P2）压测基线

在**容器环境**跑一次 `perf/load-test.js`，产出真实数字：RPS、p50/p95/p99、错误率、并发数、时长、环境规格；写入 `perf/README.md`，并明确标注**这是 lab 参考值、不是生产容量基线**，注明测试场景（哪些接口、数据量）。脚本需可复现。

**验收**：贴实际跑测输出 + README 更新。**严禁编造数字** —— 跑不出来就如实说明"未建立基线及原因"。

## 3. 范围边界（不要越界）

- 不做新功能（视图/IM/集成增强）
- 不做多租户隔离改造、合规/等保（属"对外前必补"）
- 不改备份（已含 pg_dump）
- 不改 PHASE58–63 已闭环能力

## 4. 门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | ≥ **1296** / 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 5. 红线（违反即打回）

1. **严禁误删在用代码** —— 按 T1 确认清单验证 + 全量测试兜底
2. **严禁为"清理"而删测试**
3. **严禁 mock 被测主路径 Service**
4. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
5. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
6. **严禁**回滚已闭环提交（PHASE58–63）
7. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar
8. **容器内服务间访问必须用服务名**，不得硬编码 `localhost`
9. **每项必须给出实测输出**（grep / build / 容器状态 / 压测数字）
10. **压测数字严禁编造**

## 6. 本项目教训（血泪）

1. **"有代码" ≠ "在用"** —— 已出现 5 次假完成（过滤器没进链、mock 被测 Service、handler 只打日志、只改类型定义、事件格式不处理）。判据：**追到实现落点**
2. **排查引用必须排除 `.kilo/worktrees/`**（工作树副本会造成"很多引用"假象）
3. **容器内 `localhost` 指向容器自身**（踩过 5 次）—— 服务间地址 `@Value` 可配 + compose 服务名
4. **`@DataJpaTest` 必须加 `@ActiveProfiles("test")`**（否则 Flyway 在 H2 跑 PG 迁移失败）
5. **手动 `new Service` 时 `@Value` 不注入**（需 `ReflectionTestUtils.setField`）
6. **grep 字符串常量加 `-i`**（大小写敏感已坑过两次）
7. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar
8. **测试不看数字看绑定** —— 判据：回退实现，测试必须失败

**落点清单（相关功能按这个找）**：字段类型→物理列 = `AsyncMigrationService.mapJsonbType`；记录写入 = `CollectionService.insertRecord`；分组聚合 = `DynamicTableManager.aggregate(collectionName, groupByFields, aggSpecs, filters)`；建物理列 = `DynamicTableManager.addPhysicalColumn`（columnType 由调用方传）。

## 7. 回报必须给出（缺项打回）

1. 每项实测输出：
   - T1：逐项"grep 0 引用 → 处理方式 → 测试无回归"
   - T2：重复行数对比 + 拖拽保存端到端实测
   - T3：build 输出 + healthy + 连通性证据
   - T4：压测实际输出 + README 更新
2. 五项门禁实际输出数字
3. 改动文件清单 + `git log --oneline`（**必须已提交**）
4. 明确说明哪些项未做及原因

-----END PROMPT-----

## 投喂方式

1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：复跑门禁 + 抽查删除项是否真无引用（排除 worktrees）+ 实测 crdt-service 容器起得来

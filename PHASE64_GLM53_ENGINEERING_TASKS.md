# PHASE64 任务需求单：工程与质量（交 Kilo Code + GLM-5.3 执行，CodeBuddy 审计）

> 交付方式沿用 PHASE58–63：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 按审计口径复验（复跑门禁 + 追调用链 + 实测）。

---

## 一、现状审计（CodeBuddy 实测，含行号证据）

| 项 | 现状 | 证据 |
|---|---|---|
| **死代码：`workflowExpressionEvaluator`** | 只有自身 `@Component` 声明，**0 引用** | `workflow/ExpressionEvaluator.java:26` `@Component("workflowExpressionEvaluator")`；全仓 grep（排除 `.kilo/worktrees/` 副本）无任何注入/调用点 |
| **死配置：`crdt.service.url`** | 只有 `@Value` 声明，**无任何配置赋值** | `realtime/RealtimeService.java:50` `@Value("${crdt.service.url:}") String crdtServiceUrl`；`docker-compose.yml` / `application*.yml` 均无该键 → CRDT 服务**永远不会被调用** |
| **两套看板并存** | 同时存在且都被使用 | `pages/KanbanView.tsx`（112 行）+ `features/project/BoardView.tsx`（140 行）+ `BoardColumn.tsx`；引用方：`router.tsx`、`features/project/ProjectPage.tsx`、`BoardView.test.tsx` |
| **crdt-service 无法容器化** | **无 Dockerfile** | `crdt-service/Dockerfile` 不存在 |
| **压测基线未建立** | 有脚本、**无基线数字** | `perf/load-test.js` + `perf/README.md` 存在；README 明确"本机数字不能作为生产容量基线，请在 staging 执行" → 至今无基线数据 |
| （已解决，本批不做） | 备份已含 Postgres 主库 | `backend-python/src/nocobase_py/services/backup.py:67-95` `_postgres_dump()` 用 `subprocess` 调 `pg_dump -Fc` ✅ |

> 注：`.kilo/worktrees/*` 是 Kilo Code 的工作树副本，**不是主代码**，排查引用时须排除，避免"看起来有很多引用"的误判。

---

## 二、本批任务（T1–T4）

### T1（P1）死代码与死配置清理

对下列**已确认**的目标逐项处理（二选一：**接入** 或 **删除**），并在回报中说明选择理由：

1. **`workflowExpressionEvaluator`**（`ExpressionEvaluator.java:26`）
   - 方案 A（推荐）：接入工作流节点（条件分支/表达式节点真实调用它），并补测试证明被调用
   - 方案 B：确认无用则**删除** `@Component` 与类（若类本身无其它用途）
2. **`crdt.service.url`**（`RealtimeService.java:50`）
   - 方案 A：在 `docker-compose.yml` 注入 `CRDT_SERVICE_URL`（容器内用服务名，如 `http://crdt-service:1234`），并确认 `RealtimeService` 真的发起调用
   - 方案 B：若 CRDT 尚未启用，则**显式标注**（注释 + 文档说明"未启用"），并在未配置时打 WARN，**不要留一个永远为空的静默配置**
3. **全面扫描**：用同样方法扫一遍其它"定义后从未被调用"的 Service/端点（例如仅声明 `@Component`/`@Service` 却无注入点的类），列出清单并同样按"接入或删除"处理。

**⚠️ 删除前必须确认（防误删）**：

- 全仓 grep 排除 `.kilo/worktrees/`
- 考虑**反射调用**、**Spring 组件扫描**（`@Component` 名称被按名字注入）、**配置键引用**（yml/properties）、**测试引用**
- 删除后必须跑全量测试确认无回归

**验收**：逐项给出"引用点 grep 结果（0 命中）→ 处理方式 → 全量测试无回归"的证据；不得出现"删了之后才发现有用"。

### T2（P1）两套看板收敛

**现状**：`pages/KanbanView.tsx`（112 行，视图看板）与 `features/project/BoardView.tsx`（140 行 + `BoardColumn.tsx`，项目看板）并存，且**都被使用**（`router.tsx` → KanbanView；`ProjectPage.tsx` → BoardView）。

**要求**（二选一，明确说明选择）：

- **方案 A（推荐）：抽公共内核** —— 将重复的列渲染/拖拽/卡片逻辑抽成共享组件（如 `features/board/`），两套看板各自保留业务差异（视图配置 vs 项目任务），消除重复实现。
- **方案 B：合并为一** —— 若两者业务语义确实相同，合并为单一组件并统一入口，删除另一个。

无论哪种：

- **不得破坏既有功能**：拖拽（须真触发后端保存）、列配置、卡片渲染、筛选
- 既有测试（`BoardView.test.tsx` 等）必须仍通过，且按需补充
- `tsc --noEmit` 保持 0

**验收**：给出重构前后对比（重复代码行数下降）；拖拽保存的端到端实测（容器内发起拖拽请求 → 数据库落库）；前端测试通过。

### T3（P1）crdt-service 容器化

**现状**：`crdt-service/` 无 Dockerfile，无法随 compose 部署。

**要求**：

1. 新增 `crdt-service/Dockerfile`（多阶段构建，生产镜像只保留运行时依赖；与 `backend-java/Dockerfile.offline` 同样注意**基础镜像拉取**问题，优先复用本地已有镜像）。
2. 在 `docker-compose.yml` 增加 `crdt-service` 服务，配置端口与健康检查。
3. 与 T1 的 `CRDT_SERVICE_URL` 联动：Java 侧通过**服务名**访问（容器内 `localhost` 指向自身，这是本项目踩过 5 次的坑）。
4. **必须实测**：`docker compose build crdt-service` 成功 + 容器能启动并通过健康检查 + Java 侧能连通（或明确记录"连通失败及原因"）。

**验收**：build 输出、容器 `healthy` 状态、连通性实测（curl 或日志）。

### T4（P2）压测基线

**现状**：`perf/load-test.js` 存在，但 README 说明本机数字不能作为基线，**至今没有基线数据**。

**要求**：

1. 在**容器环境**（compose 起的那一套）跑一次 `load-test.js`，产出真实数字：RPS、p50/p95/p99 延迟、错误率、并发数、测试时长、环境规格（CPU/内存）。
2. 将结果写入 `perf/README.md`，明确标注**这是 lab 环境的参考值，不是生产容量基线**，并注明测试场景（哪些接口、数据量）。
3. 脚本需可复现（参数、预热、数据量说明清楚）。

**验收**：贴实际跑测输出；README 中有数字与场景说明。**严禁编造数字**——跑不出来就如实说明"未建立基线及原因"。

---

## 三、范围边界（明确排除，防越界）

- **不做**新功能（数据视图、IM、集成能力的增强）
- **不做**多租户隔离改造、合规/等保（属"对外前必补"，不在本批）
- **不做**备份改造（已含 pg_dump，无需改）
- **不改**已闭环的 PHASE58–63 能力

## 四、门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | **≥ 1296** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | **≥ 362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | **≥ 64** passed |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 五、红线（违反即整批打回）

1. **严禁误删在用代码** —— 删除前必须按 T1 的确认清单逐项验证，并有全量测试兜底。
2. **严禁为了"清理"而删测试** —— 测试是确认"有没有用"的证据，不是负担。
3. **严禁 mock 被测主路径 Service**。
4. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值。
5. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`。
6. **严禁**回滚已闭环提交（PHASE58–63）。
7. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar。
8. **容器内服务间访问必须用服务名**，不得硬编码 `localhost`。
9. **每项必须给出实测输出**（grep 结果 / build 输出 / 容器状态 / 压测数字），不接受"配置了应该就好了"。
10. **压测数字严禁编造** —— 跑不出来如实说明。

## 六、本项目教训（血泪，务必遵守）

1. **"有代码" ≠ "在用"** —— 本项目已出现 5 次假完成（过滤器没进链、mock 被测 Service、handler 只打日志、只改类型定义、事件格式不处理）。判据永远是**追到实现落点**。
2. **排查引用必须排除 `.kilo/worktrees/`** —— 那是工作树副本，会造成"很多引用"的假象。
3. **容器内 `localhost` 指向容器自身**（已踩 5 次）—— 服务间地址必须 `@Value` 可配 + compose 服务名。
4. **`@DataJpaTest` 必须加 `@ActiveProfiles("test")`**（否则 Flyway 在 H2 跑 PG 迁移失败）。
5. **手动 `new Service` 时 `@Value` 不注入**（需 `ReflectionTestUtils.setField`）。
6. **grep 字符串常量加 `-i`**（大小写敏感已坑过两次）。
7. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar。
8. **测试不看数字看绑定** —— 判据：回退实现，测试必须失败。

### 落点清单（后续改相关功能直接按这个找）

| 需求 | 真正落点 |
|---|---|
| 字段类型 → 物理列映射 | `AsyncMigrationService.mapJsonbType`（`backend-java`） |
| 记录写入（校验/自动字段） | `CollectionService.insertRecord` |
| 分组/聚合 | `DynamicTableManager.aggregate(collectionName, groupByFields, aggSpecs, filters)` |
| 建物理列 | `DynamicTableManager.addPhysicalColumn`（columnType 由调用方传） |

## 七、交付清单（回报时必须给出，缺项打回）

1. 每项实测输出：
   - T1：逐项"grep 0 引用 → 处理方式 → 全量测试无回归"
   - T2：重构前后重复行数对比 + 拖拽保存端到端实测
   - T3：`docker compose build` 输出 + 容器 `healthy` + 连通性证据
   - T4：压测实际输出（RPS / p95 / 错误率）+ README 更新
2. 五项门禁实际输出数字
3. 改动文件清单 + `git log --oneline`（**必须已提交，不得留未提交改动**）
4. 明确说明哪些项未做及原因

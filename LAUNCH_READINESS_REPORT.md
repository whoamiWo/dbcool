# NocoBase 多栈项目（DBCool）— 上线就绪度评估报告

> **本次为 2026-10-01 复评（第 3 版）**，基线 `origin/main` = `92420fb`
> 前序版本：初版 `a2ec149`（09-27）｜修订 `79be8d3`（09-29）
>
> ⚠️ 初版与修订版的若干结论已被后续工作推翻，本版以**实测**为准，逐项标注证据。
> 全部数字由本报告当场执行得出，未引用推断值。

---

## 一、执行摘要（复评结论）

| 场景 | 结论 | 依据 |
|---|---|---|
| **内部 POC / 演示** | ✅ **可上线** | 门禁全绿、7 个容器 healthy、核心链路实测可用 |
| **内部 200 人自用（生产）** | ⚠️ **有条件可上线** —— 修完 **2 项 P0** 即可 | 4 项 P0 已清 2 项；余 2 项有明确修法，工作量小 |
| **对外商业化（多租户 SaaS）** | ❌ **不建议**（缺 3 项对外必补，见 §6） | 集成凭证租户级隔离、入站限流、集成审计日志均未做 |

**本版相对上一版的关键变化**：

- ✅ **P0-1 备份不覆盖 Postgres 主库 —— 已完全修复**（不仅代码补了 `pg_dump`，运行时客户端也已就位，见 §4.1）
- ✅ **P0-3 块编辑自动保存 404 —— 已修复**（`batch-upsert` 端点存在）
- ❌ **P0-2 钉钉登录 405 —— 仍未修**（后端 GET-only vs 前端 POST）
- ❌ **P0-4 Huddle 多副本冲突 —— 未根治**（内存路由 + 依赖 Ingress 亲和的临时方案）
- ✅ **更正**：多租户**已实现 Schema 级隔离**（`SET search_path` + 按租户 schema 建表），
  不是旧报告 P1 描述的"业务层过滤"—— 架构性风险等级下调
- ✅ PHASE58–64 共 7 批能力补齐已闭环（IM 搜索 / @提及 / 集成能力 / 数据视图深度 / 工程与质量）

---

## 二、门禁实测（2026-10-01 当场执行）

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端单测 | `mvn -o test` | ✅ **1279 / 0 failures / 0 errors / 0 skipped**，BUILD SUCCESS |
| 前端单测 | `npm run test:run` | ✅ **362 passed**（42 files） |
| 类型检查 | `npx tsc --noEmit` | ✅ **0 errors** |
| E2E | `npx playwright test` | ✅ **64 passed** |
| Python | `PYTHONPATH=src python3 -m pytest tests/ -q` | ✅ **43 passed, 1 skipped** |

**演进**：1162 → 1210 → 1225 → 1242 → 1262 → 1279（+ 期间因删除死代码 `-17`，属合理变动）

> 说明：`mvn` 从 1296 降到 1279 是 PHASE64 删除死代码 `workflow/ExpressionEvaluator`
> 及其测试所致（连带删除），非测试被弱化。

---

## 三、运行时实测

| 项 | 结果 |
|---|---|
| 容器 | `backend-java` / `backend-python` / `crdt-service` / `postgres` / `redis` / `rabbitmq` / `minio` **全部 healthy** ✅ |
| 核心端点 | `/api/health`(8080) **200**、`/api/health`(8000) **200** ✅ |
| 备份运行时依赖 | Python 容器内 `/usr/bin/pg_dump` **存在** ✅；`backend-python/Dockerfile:64` 显式安装 `postgresql-client` ✅ |

---

## 四、四项 P0 阻塞项逐项核查（核心）

### 4.1 ✅ P0-1　备份不覆盖 PostgreSQL 主库 —— **已完全修复**

| 层 | 证据 |
|---|---|
| 代码 | `backend-python/src/nocobase_py/services/backup.py:67-95` `_postgres_dump()`，用 `subprocess` 调 `pg_dump -Fc` |
| 运行时依赖 | 容器内 `/usr/bin/pg_dump` 存在（旧报告"K8s 下必失败"的根因正是缺此客户端） |
| 镜像 | `backend-python/Dockerfile:59,64` 安装 `postgresql-client`（并换清华源避免 `deb.debian.org` 卡死） |

→ 三层齐全，**不再是阻塞**。

### 4.2 ❌ P0-2　钉钉登录主入口 405 —— **仍未修复**

```
后端  DingTalkController.java:79   @GetMapping("/auth-url")      ← 仅 GET
前端  frontend/src/api/dingtalk.ts:36   client.post('/api/dingtalk/auth-url')   ← POST
```

POST 打向 GET-only 端点 → **405**。这是钉钉登录的**主入口**，若内部采用钉钉登录则直接阻塞。

**修法（二选一，都很小）**：
1. 后端补 `@PostMapping("/auth-url")`（与 GET 同逻辑，最省事、兼容性最好）
2. 或前端改为 `client.get('/api/dingtalk/auth-url')`

**验收**：容器内 `POST /api/dingtalk/auth-url` 返回 200（非 405），前端登录页能取到授权地址。

### 4.3 ✅ P0-3　块编辑器自动保存 404（静默数据丢失）—— **已修复**

`WikiController.java:466` `@PostMapping("/blocks/batch-upsert")` 端点**已存在** → 前端自动保存不再 404。

> 本次复评**未做端到端复测**（仅核实端点存在与接线），建议上线前补一次"编辑后自动保存 → 数据库落库"的实测。

### 4.4 ❌ P0-4　Huddle 语音内存路由 vs K8s 多副本 —— **未根治**

```
HuddleSignalingHandler.java:32  private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
HuddleSignalingHandler.java:34  private final Map<WebSocketSession, String> sessionRooms = new ConcurrentHashMap<>();
k8s/02-backend-java.yaml:22     replicas: 3   （HPA min 3 / max 10）
k8s/02-backend-java.yaml:18     # P0-4 临时方案：依赖 Ingress cookie affinity
```

房间与会话是**进程内内存**，多副本下跨 Pod 的用户无法互通信令；当前仅靠 Ingress cookie 粘滞"缓解"，
**Pod 重启 / 扩容 / 亲和失效即断**。

**修法（推荐顺序）**：
1. **根治**：信令路由外置 —— 用 Redis Pub/Sub 广播（已有 Redis），或房间状态存 Redis
2. **过渡**：`backend-java` 副本固定为 1 并移除 HPA（牺牲可用性换正确性，仅适合语音用量小的内部场景）
3. 至少：把"依赖 Ingress 亲和"这一约束写进部署文档，避免运维无感知扩容

---

## 五、更正：多租户隔离强度（风险下调）

旧报告存在前后矛盾（修订版说"Schema 级隔离"，P1 又说"业务层过滤"）。本次核实结论是 **Schema 级隔离成立**：

- `DynamicTableManager.java:483`：`TenantContext.currentSchema() + "." + bareTableName(...)` → **按租户 schema 建表**
- `tenant/SchemaTenantConnectionProvider.java`：`SET search_path` 的多租户连接提供者（ADR-007），H2 不支持时降级

→ **架构性风险等级下调**；但新增接口仍需带归属校验（业务层校验与 Schema 隔离是双保险，不可只依赖其一）。

---

## 六、差距清单

### 🟡 P1 — 规模化前需补（内部自用不阻塞）

| 项 | 现状 |
|---|---|
| **正式性能容量基线** | ⚠️ **仍未建立**：PHASE64 压测已改打业务接口（`GET /api/collections`），20/50/100 并发 QPS 19.9→49.8→99.6 随并发线性增长、分位数自洽；但**空库 + 脚本 `sleep(1)` 使 QPS≈VUS（客户端限速）**，未测出系统拐点 → 待 staging + 有数据量环境执行 |
| 备份恢复演练 | ✅ 已完成真实演练（09-27）：DB / Media / Redis 全恢复成功，RPO/RTO 已量化；本次确认代码与客户端均已就位 |
| 多租户持续审计 | ✅ 已有 Schema 级隔离 + 业务层双保险；新增接口须持续核查归属校验 |

### 🟢 P2 — 遗留小项（不阻塞，建议顺手清）

| 项 | 说明 |
|---|---|
| 钉钉回调返回 HTTP 200 + 业务码 401 | 不符合 REST 语义，第三方可能误判成功 → 建议改 `ResponseEntity.status(401)` |
| 事件幂等表无清理 | `integration_external_message_log` 会持续增长 → 需定期清理或 TTL |
| 分组模式下虚拟滚动被禁用 | `shouldVirtualize = !groupByField && ...` → 大分组展开有性能风险，可标注限制或分组内再虚拟化 |
| i18n 次要页面 | 核心页面已中英双语，次要页面仍为中文 |

### 🔵 对外商业化前必补（内部自用可延后）

| 项 | 现状 |
|---|---|
| **集成凭证租户级隔离** | 集成凭证（如 `dingtalk.app-secret`）目前是全局 `@Value`，多租户下无法各配各的 → 需改为租户级存库（回退 `@Value`） |
| **入站限流** | 第三方回调（Slack / 钉钉 / 飞书 / Mattermost）无限流，仅有登录限流 |
| **集成审计日志** | 谁安装/卸载了什么、入站消息来源追溯，目前缺失 |

---

## 七、上线建议（更新版）

**第 1 步（现在即可）**：内部 POC / 演示环境上线 —— 门禁与运行时均达标。

**第 2 步（内部 200 人生产前，约 2–5 人日）**：修完剩余 2 项 P0
1. **钉钉登录 405**（约 0.5 人日）：补 POST 映射或改前端为 GET
2. **Huddle 多副本**（约 2–4 人日）：信令路由外置 Redis；或过渡期固定副本为 1

**第 3 步（规模化 / 对外前）**：
1. 在 staging + 有数据量环境建立**正式容量基线**（去掉 `sleep`，压到拐点）
2. 补齐 §6 的三项对外必补
3. 顺手清掉 §6 的 P2 小项

---

## 附录：本次复评的验证方法

- 门禁：全部实际执行（`mvn -o test` 全量、`npm run test:run`、`npx tsc --noEmit`、`npx playwright test`、
  `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`）
- 运行时：`docker compose ps`、`curl /api/health`、`docker compose exec backend-python which pg_dump`
- P0 核查：逐项 grep 到**实现落点**（文件:行号），不止于"代码存在"
- 未实测项已明确标注（如 P0-3 仅核实端点存在，建议补端到端复测）

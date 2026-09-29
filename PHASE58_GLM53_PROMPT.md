# Phase 58 投喂提示词：清偿 4 项 P0 上线阻塞项（Kilo Code + GLM-5.3）

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3（Kilo Code）**
> 基线提交：`79be8d3`（门禁实测 `mvn -o test` **1210/0/0**、`npm run test:run` **267**、tsc **0**、playwright **64**，6 容器全 healthy）
> 创建日期：2026-09-29
> **用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行**
> 详细规格（含文件行号证据）见同目录 `PHASE58_GLM53_P0_LAUNCH_BLOCKERS.md`；评估依据见 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md`

---

## 背景：为什么是这 4 项

2026-09-29 实证审计结论：**平台其余部分已达内部自用标准，仅剩 4 项 P0 阻塞上线**，合计约 9–14 人日。

| P0 | 一句话 | 危害 |
|---|---|---|
| P0-1 | 备份不含 PostgreSQL 主库 | 业务数据无备份，误删不可恢复；且 `docker exec` 硬编码致 K8s 下备份必失败 |
| P0-2 | 钉钉登录主入口 405 | 前端 POST 打后端 `@GetMapping` → 用户点登录必然失败（而钉钉嵌入是本平台核心定位） |
| P0-3 | 块编辑自动保存 404 且静默失败 | `POST /api/wiki/blocks/batch-upsert` 后端 0 命中，每 2 秒 404 仅 `console.error` → Block 树从不落库 |
| P0-4 | Huddle 语音内存路由 vs K8s 3 副本 | 进程内 `Map` 路由 → 同房间用户连到不同副本互相看不见，语音随机失效 |

**本轮翻车模式警示**：本项目多次出现「有代码但从未调用」的假完成（`MessageSearchService` 0 注入、`workflow/ExpressionEvaluator` 0 引用、钉钉 `syncOrganization` 只打日志）。因此本轮特别要求：**真修 + 可复现证据**。

---

## 投喂提示词（以下整段复制）

```
请阅读并严格执行以下 Phase 58 任务：清偿 4 项 P0 上线阻塞项。

项目：/home/who/multistack-project（DBCool 综合企业协作平台）
栈：Java Spring Boot + Python FastAPI + React 19 + Node CRDT 服务；PostgreSQL(pgvector)+Redis+RabbitMQ+MinIO
基线：origin/main = 79be8d3
门禁基线（你执行前须复跑，最终须 ≥ 基线且 0 失败）：
  mvn -o test = 1210/0/0 ｜ npm run test:run = 267 ｜ npx tsc --noEmit = 0 ｜ npx playwright test = 64

执行前必读：同目录 PHASE58_GLM53_P0_LAUNCH_BLOCKERS.md（每项含文件行号证据与验收标准）。
若提示词与该文件冲突，以该文件的行号证据为准。

============================================================
§0 全局红线（违反即打回）
============================================================
1. 严禁臆造 API：任何端点路径、方法名、字段名，必须先 grep/读源码确认存在再使用。
2. 严禁 it.skip / @Disabled / 删测试 / 弱化断言 / 改断言阈值。门禁只增不减。
3. 改方法签名必须同步既有测试（否则编译失败）。
4. 严禁「只打日志」式修复（如 log.info + return "已触发" 的桩）。本项目 DingTalkAppService#syncOrganization 就是这么翻车的。
5. 严禁静默吞异常：catch 后仅 console.error 或空 catch 一律视为未完成。P0-3 就是这么翻车的。
6. 严禁提交 .env 或任何密钥明文（.env 含真实 JWT_SECRET / INTERNAL_SERVICE_TOKEN）。
7. 每个新增/修复的端点必须配反向用例（401/403/400/404）。
8. 环境限制无法完成时，明确说明并给替代方案，不许静默跳过、不许假装完成。
9. 严禁扩大范围：任务书 §6 之外的改动（哪怕是好意优化）一律打回。

============================================================
§1 P0-1：备份必须覆盖 PostgreSQL 主库（优先级最高）
============================================================
现状（已核实）：backend-python/src/nocobase_py/services/backup.py
  L36-38：_DB_PATHS = [Path("alerts.db")]（只备份 SQLite 告警库）+ media + Redis
  L66-72：_redis_snapshot() 硬编码 docker exec nocobase-redis redis-cli --rdb -
  → Postgres 主库（存 collections/records/wiki/IM/workflow 全部业务数据）不在备份范围；
    K8s 下无 docker 命令 → FileNotFoundError → 整个备份任务失败。
  既有 test_backup_restore.py 只覆盖 SQLite/文件/Redis，掩盖了主库缺失。

要求：
1. 用 pg_dump（推荐 -Fc 自定义格式）把主库纳入备份；连接参数全部来自环境变量
   （POSTGRES_HOST/PORT/USER/PASSWORD/DB），禁止硬编码；容器内 POSTGRES_HOST=postgres、本地为 localhost，两边都要能跑。
2. 移除 docker exec 依赖：Redis 快照改直连（REDIS_HOST/PORT/PASSWORD），K8s 下必须可用。
3. 主库恢复路径真可用（pg_restore / psql < dump），不许 TODO。
4. 关键组件（尤其主库）失败必须让任务整体失败并报错，不许吞异常后返回"成功"。
5. 补测试：主库 dump 生成且大小 > 0；恢复后记录数一致；主库失败时整体失败（反向用例）。
6. 必须做真实演练：对本地 docker postgres 执行「备份 → 破坏 → 恢复 → 校验」，
   回报中给出 dump 字节数、恢复后记录数、耗时、RPO/RTO。

验收：python 测试全绿；主库真实恢复演练成功（硬性，只跑单测不算完成）；
      grep -rn "docker exec" backend-python/src 应为 0。

============================================================
§2 P0-2：钉钉登录主入口 405（成本最低、收益最高）
============================================================
现状（已核实）：
  frontend/src/pages/auth/DingTalkLoginPage.tsx L27-30：fetch('/api/dingtalk/auth-url', { method: 'POST' })
  backend-java/.../integration/dingtalk/DingTalkController.java L69：@GetMapping("/auth-url")
  → 405。该页面是钉钉登录主入口之一（路由 /auth/dingtalk）。
  而同功能另一条路径 frontend/src/pages/dingtalk/DingTalkPage.tsx L40 用 GET 跳转 —— 是正确的。
  → 同一功能两条路径、一条坏，属回归遗漏。

要求：
1. 先读 DingTalkController 确认 /auth-url 真实签名与参数，再二选一（不许两边各改各的）：
   A）推荐：前端改 GET（与 DingTalkPage 已验证正确的写法对齐），需传参用 query string；
   B）仅当有必须走 body 的参数时才改后端为 POST，且须同步改 DingTalkPage。
2. 必须补回归测试防再次错配：前端单测 mock fetch 断言 method 与后端一致，
   或后端契约测试断言 method —— 能真实拦截 POST/GET 不一致。
3. 必须自查并报告企微是否同病：WeComLoginPage.tsx 与 wecom/WeComController.java 的
   auth-url method 是否一致；不一致则一并修，一致则明确说明"已核对一致"。不许不查就跳过。
4. useDingTalkAuth.ts 的 isInDingTalk() 若依赖 UA 嗅探而非钉钉 JS-SDK，只需报告，本轮不改（属 P1）。

验收：vitest / tsc / playwright 全绿；回报写明后端实际 method、前端修改后 method、企微核对结论。

============================================================
§3 P0-3：块编辑器自动保存 404 且静默失败
============================================================
现状（已核实）：frontend/src/components/wiki/NotionStyleEditor.tsx
  L264-265：每 2 秒防抖 POST /api/wiki/blocks/batch-upsert
  L281：失败仅 console.error('保存失败:') → 静默吞掉
  后端 grep -rn "batch-upsert" backend-java/src = 0 命中；
  现有块端点（WikiController）：L410 POST /pages/{id}/blocks、L423 PUT /blocks/{blockId}、
                                L438 DELETE /blocks/{blockId}、L449 PUT /pages/{id}/blocks/reorder
  → 每次自动保存必然 404，Block 树从不落库；用户以为在用 Notion 式块编辑并已保存。
  同文件另两处已核实缺陷：
    L319 fetch('/api/wiki/templates')，后端只有 /kb/{kbId}/templates（L604）→ 模板永远空
    L306-307 setBacklinks(data || []) 未 unwrap {code:0,data:[...]} 信封 → 随后 .map() 抛错

要求：
1. 二选一但必须真落库：A）推荐：后端新增 POST /api/wiki/blocks/batch-upsert（批量 upsert+可选 reorder，单事务）；
   B）前端改调用现有 POST /pages/{id}/blocks + PUT /pages/{id}/blocks/reorder。
2. 保存失败必须有 UI 可见提示（Snackbar/Alert 等），禁止仅 console.error。这是核心验收点。
3. 修模板端点（L319）：改 /kb/{kbId}/templates 或后端补端点，保证模板真能加载。
4. 修反向链接 unwrap（L306-307）：取 data.data；可参考 frontend/src/api/endpoints.ts L36 已有 wikiBacklinks 定义。
5. 补测试：后端 batch-upsert 成功/重排/越权 403/跨租户隔离/非法参数 400；
   前端：保存失败时显示错误提示（断言 UI 出现错误元素，不是断言 console 被调用）+ 模板加载成功用例。
6. 端到端自证：回报给出「块编辑保存 → 查库/查 API 确认 Block 记录存在」的证据。

验收：mvn / vitest / tsc / playwright 全绿；Block 树真落库（有证据）；保存失败有 UI 提示（有用例）。

============================================================
§4 P0-4：Huddle 语音内存路由与 K8s 多副本冲突
============================================================
现状（已核实）：
  backend-java/.../im/HuddleSignalingHandler.java L32：Map<String, Set<WebSocketSession>> rooms（进程内内存路由）
  类注释自认"多实例部署需迁移到 Redis pub/sub"
  k8s/02-backend-java.yaml：replicas: 3 + HPA 3→10 + PDB minAvailable 2
  → 同房间用户连到不同副本互相看不见，WebRTC 信令随机失效。

要求（二选一，必须说明选了哪个及理由）：
A）正解（推荐）：迁移到 Redis pub/sub 跨实例广播信令。
   先读同目录 RedisStompBridge.java 参考写法；保持 /ws/huddle 端点与 JWT 握手鉴权不变（这块真接真，别改坏）；
   补测试：两个 handler 实例 + mock/真实 Redis，证明 A 实例的信令能被 B 实例成员收到。
B）短期方案：不改代码，K8s Ingress 加会话粘滞（affinity cookie），
   并在 02-backend-java.yaml 注释中明确标注"临时方案，正解为 Redis pub/sub（TODO 指向本任务）"。
   仅当 A 工作量 > 5 人日才可选 B；选 B 必须写明"未做 A"及原因。
验收：mvn 全绿；所选方案有可验证证据；HuddlePanel.tsx + useHuddle.ts 不被改坏。

============================================================
§5 环境说明（执行前必读）
============================================================
- 6 个服务 postgres/redis/rabbitmq/minio/backend-java/backend-python 均 healthy。
- Java 镜像必须用离线方式重建（主 Dockerfile 拉 eclipse-temurin 极慢，实测 597s 仅 8MB/53MB，禁用）：
    cd backend-java && mvn -o package -DskipTests
    docker build -f Dockerfile.offline -t nocobase-backend-java:latest .
    cd .. && docker compose up -d --no-build backend-java
- Python 改代码后：docker compose up -d --no-build backend-python
- docker profile 下 KeyRingService 会拒绝 dev 占位 JWT secret（正确的安全设计），不要绕过它。
- Python 容器已设 HF_HUB_OFFLINE=1（模型走构建期缓存），勿删，否则启动卡死。
- 验证：curl localhost:8080/api/health、curl localhost:8000/api/health、docker compose ps

============================================================
§6 提交与回报
============================================================
按栈分开提交，信息写清「现状 → 改动 → 门禁实测数字」：[python] / [java] / [frontend] / [infra]，
每提交一次都跑对应门禁，禁止攒到最后一次性提交。

回报必须包含：
1. 四项门禁实测数字，并与基线对比。
2. P0-1 真实演练证据（命令 + 输出片段：dump 大小、恢复后记录数）。
3. P0-3 落库证据（保存后查询 Block 的结果）。
4. P0-2 企微 auth-url 核对结论（明确写）。
5. P0-4 所选方案及理由；选 B 须写明未完成 A 的原因。
6. 未完成项明确写"未做"及原因，不许虚报。

============================================================
§7 审计口径（提前告知，请勿抱侥幸）
============================================================
我会：① 追溯调用链而非看文件是否存在（grep 引用点，查是否被真实调用）；
② 复跑四项门禁，数字须 ≥ 基线；
③ 逐项抽查证据（亲自复现备份恢复、核对钉钉 method、改一个块保存后查库、验证 Huddle 方案）；
④ 反作弊检查（新增 skip / 删测试 / 弱化断言 / 改阈值，任一命中即打回）；
⑤ 范围检查（任务书 §6 之外的擅自改动一律打回）。

一句话：要真修，不要让它看起来修好了。
```

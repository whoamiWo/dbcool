# PHASE65 任务需求单：打通内部上线最后 2 项 P0（交 Kilo Code + GLM-5.3，CodeBuddy 审计）

> 背景：2026-10-01 上线就绪度复评（`LAUNCH_READINESS_REPORT.md`，基线 `92420fb`）结论为
> **内部 200 人自用 = 有条件可上线**，条件就是修完剩余 2 项 P0。本批即做这两项（+ 顺手清 P2 小项）。

---

## 一、现状（复评实测，含行号证据）

### T1　钉钉登录主入口 405

```java
// backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkController.java:79
@GetMapping("/auth-url")          // ← 仅 GET
```
```ts
// frontend/src/api/dingtalk.ts:36
return client.post('/api/dingtalk/auth-url');   // ← POST
```
POST 打向 GET-only → **405**。这是钉钉登录的**主入口**。

### T2　Huddle 语音信令内存路由 vs K8s 多副本

```java
// backend-java/src/main/java/com/nocobase/im/HuddleSignalingHandler.java（145 行）
:32  private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
:34  private final Map<WebSocketSession, String> sessionRooms = new ConcurrentHashMap<>();
:67  private void handleJoin(...)        // 加入房间（写内存 rooms）
:83  private void handleLeave(...)       // 离开（broadcastOthers）
:91  private void relay(...)             // 转发 offer/answer/candidate
:102 private void broadcastOthers(...)   // 只发给**本进程**的房间成员
:115 public void afterConnectionClosed(...)  // 清理内存
```
```yaml
# k8s/02-backend-java.yaml
:22  replicas: 3
:116 minReplicas: 3
:117 maxReplicas: 10
:18  # P0-4 临时方案：HPA + 多副本下 WebSocket 信令依赖会话粘滞（Ingress cookie affinity）
```

房间与会话是**进程内内存**，跨 Pod 的用户无法互通信令；现仅靠 Ingress cookie 粘滞缓解，
**Pod 重启 / 扩容 / 亲和失效即断**。

**可复用基础设施（已确认存在）**：
- `realtime/RedisStompBridge.java:29` `REDIS_CHANNEL = "nocobase:stomp:broadcast"`、`:51 broadcast(destination, payload)` → 已有 Redis 发布/订阅模式可参照
- 多个类已注入 `RedisTemplate` / `StringRedisTemplate`（`ratelimit/GlobalRateLimiter`、`workflow/WorkflowScheduler`、`auth/RefreshTokenService` 等）→ Redis 可用

---

## 二、任务

### T1（P0）修钉钉登录 405

**要求**（二选一，说明选择）：

- **方案 A（推荐）**：后端补 `@PostMapping("/auth-url")`，与 GET 共用同一处理逻辑（兼容性最好，前端不用改）
- **方案 B**：前端 `frontend/src/api/dingtalk.ts:36` 改为 `client.get('/api/dingtalk/auth-url')`

**验收（必须实测）**：

- 容器内 `POST /api/dingtalk/auth-url` 返回 **200**（不是 405），且响应体含授权地址字段
- `GET /api/dingtalk/auth-url` 仍可用（若走方案 A）
- 前端登录页能正常取到授权地址（贴页面或接口证据）

### T2（P0）Huddle 信令跨副本（Redis 外置）

**要求**：

1. **跨副本转发**：把信令广播从"只发本进程"改为 **Redis Pub/Sub 跨副本**：
   - 本副本：仍直接发给本机的房间成员（保留现有逻辑）
   - 同时：把消息发布到 Redis 频道（携带 roomId、payload、发送者标识）
   - 各副本订阅该频道，收到后发给**本机**该房间的成员（排除发送者，避免回声）
   - 参照 `RedisStompBridge` 的频道与序列化方式，不要另起一套 Redis 连接配置
2. **覆盖的信令类型**：`peer-joined` / `peer-left` / `offer` / `answer` / `candidate`（即 `handleJoin`、`handleLeave`、`relay`、`broadcastOthers` 全链路）
3. **正确性**：
   - 不能产生回声/重复（发送者必须被排除）
   - 连接关闭 / 异常断开时清理本机与房间状态（`afterConnectionClosed`）
   - 单个副本内行为与改造前一致（回归）
4. **K8s 侧**：若改造完成后不再强依赖会话粘滞，请更新 `k8s/02-backend-java.yaml:18` 的注释说明；若仍需亲和，明确写出约束。

**验收（必须实测，这是本批难点）**：

- **双实例实测**：用 compose 起**两个 backend-java 实例**（如 8080 / 8081），两个 WS 客户端分别连到不同实例、加入**同一 roomId**，验证：
  - A 加入 → B 收到 `peer-joined`
  - A 发 `offer` → B 收到该 offer（跨实例）
  - A 断开 → B 收到 `peer-left`
  - **无回声**（发送方不会收到自己发的消息）
- 给出实测输出（WS 客户端收发记录）
- 单实例回归：既有 Huddle 相关测试通过

> 若双实例实测环境确有困难，必须在回报中说明具体卡点；**不接受"代码写了就算完成"**。

### T3（P2，顺手清）两项小遗留

1. **钉钉回调 HTTP 状态码**：当前返回 HTTP 200 + 业务码 401（`DingTalkController` events）→ 改为 `ResponseEntity.status(401)`，避免第三方误判成功。
2. **事件幂等表清理**：`integration_external_message_log` 无清理机制会持续增长 → 加定期清理（定时任务）或 TTL，并说明保留期。

---

## 三、范围边界（不要越界）

- 不做新功能、不做多租户凭证隔离改造（属"对外必补"，不在本批）
- 不改 Huddle 的 WebRTC 媒体链路（只改**信令**路由）
- 不重构 `RedisStompBridge`（可复用，不得破坏既有 STOMP 广播）

## 四、门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线 |
|---|---|
| `mvn -o test` | **≥ 1279** / 0 failures / 0 errors |
| `npm run test:run` | **≥ 362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 五、红线（违反即打回）

1. **T1 必须实测 POST 返回 200**（不得只改代码不验证）
2. **T2 必须做双实例跨副本实测**（这是本批唯一能证明"真的跨副本"的方式）
3. **严禁 mock 被测主路径 Service**
4. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
5. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
6. **严禁**回滚已闭环提交（PHASE58–64）
7. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar
8. **容器内服务间访问必须用服务名**，不得硬编码 `localhost`
9. **改动必须提交并推送**（`git status --porcelain` 为空）
10. **每项必须给出实测输出**

## 六、教训（务必遵守）

1. **P0 是否真清要查三层**：① 代码实现 ② **运行时依赖**（如 pg_dump 客户端在不在容器里）③ **部署形态匹配**（内存路由 vs 副本数）。P0-4 正是卡在第 ③ 层——代码能用但部署形态不匹配。
2. **"有代码" ≠ "在用"**（已出现 5 次假完成）—— 判据是追到实现落点。
3. **判断 ≠ 证据** —— 结论必须能被审计方独立验证（如贴实测输出）。
4. **排查引用排除 `.kilo/worktrees/`**；**grep 字符串常量加 `-i`**；**`@DataJpaTest` 加 `@ActiveProfiles("test")`**；**手动 new Service 时 `@Value` 不注入**。
5. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar（`target/classes` 残留会让应用起不来）。
6. **commit message 含引号时改用 `git commit -F 文件`**，不要直接放 `-m "..."`。
7. **测试不看数字看绑定** —— 判据：回退实现，测试必须失败。

### 落点清单

| 需求 | 真正落点 |
|---|---|
| 字段类型 → 物理列映射 | `AsyncMigrationService.mapJsonbType` |
| 记录写入（校验/自动字段） | `CollectionService.insertRecord` |
| 分组/聚合 | `DynamicTableManager.aggregate(...)` |
| 表达式求值（**在用**） | `com.nocobase.common.ExpressionEvaluator` |
| 登录限流 | compose `RATELIMIT_LOGIN_LIMIT`（默认 5） |
| Huddle 信令 | `HuddleSignalingHandler`（rooms/sessionRooms/relay/broadcastOthers） |
| Redis 广播参照 | `RedisStompBridge`（`nocobase:stomp:broadcast`） |

## 七、交付清单（缺项打回）

1. T1：`POST /api/dingtalk/auth-url` 返回 200 的实测输出
2. T2：**双实例跨副本 WS 实测记录**（加入/offer/离开/无回声）+ 单实例回归结果
3. T3：两项小改动的说明与验证
4. 五项门禁实际输出数字
5. 改动文件清单 + `git log --oneline`（**已提交并推送**，`git status --porcelain` 为空）
6. 明确说明哪些项未做及原因

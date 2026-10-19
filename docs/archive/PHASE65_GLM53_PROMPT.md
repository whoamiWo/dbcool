# PHASE65 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。任务书见仓库根目录 `PHASE65_GLM53_P0_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。本批（PHASE65）要打通**内部上线最后 2 项 P0**：钉钉登录 405、Huddle 信令跨副本。来源：2026-10-01 上线就绪度复评结论为「内部 200 人自用 = 有条件可上线」，条件就是这两项。

## 0. 工作目录

- 仓库根：`/home/who/multistack-project`；后端 `backend-java`；前端 `frontend`；Python `backend-python`（pytest 需 `PYTHONPATH=src`）

## 1. 现状（复评实测，含行号）

### T1　钉钉登录主入口 405
```java
// DingTalkController.java:79
@GetMapping("/auth-url")          // ← 仅 GET
```
```ts
// frontend/src/api/dingtalk.ts:36
return client.post('/api/dingtalk/auth-url');   // ← POST → 405
```

### T2　Huddle 信令内存路由 vs K8s 多副本
```java
// im/HuddleSignalingHandler.java（145 行）
:32  Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
:34  Map<WebSocketSession, String> sessionRooms = new ConcurrentHashMap<>();
:67  handleJoin / :83 handleLeave / :91 relay / :102 broadcastOthers（只发本进程）/ :115 afterConnectionClosed
```
```yaml
# k8s/02-backend-java.yaml
:22 replicas: 3  ｜ :116 minReplicas: 3 ｜ :117 maxReplicas: 10
:18 # P0-4 临时方案：依赖 Ingress cookie affinity
```
跨 Pod 用户无法互通信令，现仅靠 Ingress 粘滞缓解，Pod 重启/扩容/亲和失效即断。

**可复用（已确认存在）**：`realtime/RedisStompBridge.java:29` `REDIS_CHANNEL = "nocobase:stomp:broadcast"`、`:51 broadcast(...)`；多个类已注入 `RedisTemplate`（GlobalRateLimiter / WorkflowScheduler / RefreshTokenService）。

## 2. 任务

### T1（P0）修钉钉登录 405
二选一（说明选择）：
- **A（推荐）**：后端补 `@PostMapping("/auth-url")`，与 GET 共用逻辑（前端不用改）
- **B**：前端 `dingtalk.ts:36` 改 `client.get(...)`

**验收**：容器内 `POST /api/dingtalk/auth-url` 返回 **200**（非 405），响应含授权地址字段；若走 A，GET 仍可用。贴实测输出。

### T2（P0）Huddle 信令跨副本（Redis 外置）
1. **跨副本转发**：本副本仍直发本机成员；同时把消息发布到 Redis 频道（带 roomId、payload、发送者标识）；各副本订阅后发给**本机**该房间成员（**排除发送者，防回声**）。参照 `RedisStompBridge` 的频道与序列化方式，不要另起一套 Redis 连接配置。
2. **覆盖类型**：`peer-joined` / `peer-left` / `offer` / `answer` / `candidate`（即 handleJoin / handleLeave / relay / broadcastOthers 全链路）。
3. **正确性**：无回声与重复；连接关闭/异常断开要清理；单副本内行为与改造前一致（回归）。
4. **K8s 侧**：更新 `k8s/02-backend-java.yaml:18` 的注释说明（是否仍依赖粘滞）。

**验收（本批难点，必须实测）**：
- **双实例实测**：compose 起**两个 backend-java 实例**（如 8080 / 8081），两个 WS 客户端分别连不同实例、加入**同一 roomId**，验证：
  - A 加入 → B 收到 `peer-joined`
  - A 发 `offer` → B 收到（跨实例）
  - A 断开 → B 收到 `peer-left`
  - **无回声**（发送方收不到自己发的）
- 贴 WS 收发记录；单实例回归通过
- 若双实例环境确有困难，必须说明具体卡点；**不接受"代码写了就算完成"**

### T3（P2，顺手清）
1. 钉钉回调当前返回 HTTP 200 + 业务码 401 → 改 `ResponseEntity.status(401)`，避免第三方误判成功
2. `integration_external_message_log` 无清理会持续增长 → 加定期清理或 TTL，并说明保留期

## 3. 范围边界
- 不做新功能、不做多租户凭证隔离（属对外必补，不在本批）
- 不改 WebRTC 媒体链路（**只改信令路由**）
- 不重构 `RedisStompBridge`（可复用，不得破坏既有 STOMP 广播）

## 4. 门禁基线
| 门禁 | 基线 |
|---|---|
| `mvn -o test` | ≥ **1279** / 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 5. 红线（违反即打回）
1. **T1 必须实测 POST 返回 200**
2. **T2 必须做双实例跨副本实测**
3. **严禁 mock 被测主路径 Service**
4. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
5. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
6. **严禁**回滚已闭环提交（PHASE58–64）
7. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar
8. **容器内服务间访问必须用服务名**，不得硬编码 `localhost`
9. **改动必须提交并推送**（`git status --porcelain` 为空）
10. **每项必须给出实测输出**

## 6. 教训
1. **P0 是否真清要查三层**：① 代码实现 ② 运行时依赖 ③ **部署形态匹配**（内存路由 vs 副本数）—— P0-4 就卡在第 ③ 层
2. **"有代码" ≠ "在用"**（5 次假完成）—— 判据是追到实现落点
3. **判断 ≠ 证据** —— 结论必须可独立验证（贴实测输出）
4. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**`@DataJpaTest` 加 `@ActiveProfiles("test")`**；**手动 new Service 时 `@Value` 不注入**
5. **改迁移必须 `mvn -o clean package`** + `zipfile` 校验 jar
6. **commit message 含引号时改用 `git commit -F 文件`**
7. **测试不看数字看绑定** —— 判据：回退实现，测试必须失败

**落点清单**：字段类型→物理列 = `AsyncMigrationService.mapJsonbType`；记录写入 = `CollectionService.insertRecord`；分组聚合 = `DynamicTableManager.aggregate(...)`；表达式求值（在用）= `com.nocobase.common.ExpressionEvaluator`；登录限流 = compose `RATELIMIT_LOGIN_LIMIT`；Huddle 信令 = `HuddleSignalingHandler`；Redis 广播参照 = `RedisStompBridge`。

## 7. 回报必须给出（缺项打回）
1. T1：`POST /api/dingtalk/auth-url` 返回 200 的实测输出
2. T2：**双实例跨副本 WS 实测记录**（加入 / offer / 离开 / 无回声）+ 单实例回归
3. T3：两项小改动说明与验证
4. 五项门禁实际输出数字
5. 改动清单 + `git log --oneline`（**已提交并推送**，status 空）
6. 说明哪些项未做及原因

-----END PROMPT-----

## 投喂方式
1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：实测 POST auth-url 是否 200、双实例 WS 跨副本是否真互通（无回声）、门禁数字、代码在 HEAD 里

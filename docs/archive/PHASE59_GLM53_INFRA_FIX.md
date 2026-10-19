# Phase 59 任务书：修复 RabbitMQ 连接与跨服务 JWT 算法不匹配（投喂 GLM-5.3）

**编排方**：CodeBuddy (HY4)　**执行方**：GLM-5.3（Kilo Code）　**审计/验收方**：CodeBuddy
**基线提交**：`809eba5`（PHASE 58 四项 P0 已全部闭环并推送）
**创建日期**：2026-09-29

> 本任务书每项「现状」均由 CodeBuddy 于 2026-09-29 **实际读源码 + 实测命令核实**（附文件与行号），
> 可直接采信，**无需重新排查**。但**未列出的字段名/配置项，动手前必须自己读代码确认**。

---

## §0 背景：已完成的工作（严禁回滚）

PHASE 58 已完成并推送，**禁止删除、回退、弱化**：

| 提交 | 内容 |
|---|---|
| `3d06532` | [java] P0-3 块编辑：`batch-upsert` 端点 + jsonb 合法序列化 + 4 用例 |
| `6441307` | [python] P0-1 备份覆盖 Postgres 主库 + Dockerfile 装客户端换清华源 |
| `17de634` | [frontend] P0-2 钉钉/企微 405 修复（含真回归测试）+ P0-3 前端 |
| `b3afd73` | [infra] P0-4 Huddle 会话粘滞 + **compose 注入 POSTGRES_*/REDIS_*** |
| `0c8785b` | [docs] 综合平台上线评估 + PHASE58 任务书/提示词 |
| `809eba5` | [infra] 全局排查硬编码 localhost，修第三处 `AI_PYTHON_URL` |

**当前门禁基线（执行前须复跑，最终 ≥ 基线且 0 失败）**：

| 门禁 | 命令 | 基线实测 |
|---|---|---|
| 后端 | `cd backend-java && mvn -o test` | **1214 / 0 / 0** |
| 前端单测 | `cd frontend && npm run test:run` | **271 passed** |
| 前端类型 | `cd frontend && npx tsc --noEmit` | **0** |
| E2E | `cd frontend && npx playwright test` | **64 passed** |
| Python | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | **8 passed / 1 skipped**（备份测试） |

**重要方法论（本项目已踩 4 次）**：容器内 `localhost` 指向容器自身。
任何「容器 A 调容器 B」的地址，必须 **`@Value` 可配 + compose 用服务名注入**。
本项目已修复四处：`AI_SERVICE_URL`、`backup.py` 的 POSTGRES/REDIS 连接、`AI_PYTHON_URL`。
**本任务书的 F1 是第五处**（Spring 不读 `RABBITMQ_HOST`，因缺 `SPRING_` 前缀）。

---

## §1 全局红线（违反即打回）

1. 严禁 `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改断言阈值。
2. 严禁回滚 §0 任何已完成修复。
3. 严禁臆造配置项/字段名：必须先确认 Spring Boot relaxed binding 规则或读源码确认。
4. **严禁「只打日志」式修复**；严禁 `catch` 后仅 `console.error` 或空 catch。
5. 严禁提交 `.env` 或任何密钥明文（`.env` 含真实 `JWT_SECRET`/`INTERNAL_SERVICE_TOKEN`/`POSTGRES_PASSWORD`）。
6. **严禁扩大范围**：除 F1、F2 外不要动其他文件。
7. 每个修复必须给出**实测输出**（不是"应该能连上"）。

---

## §2 F1：RabbitMQ 连接失败（容器内 Java 连不上 MQ）

### 现状（已核实）

1. `backend-java/src/main/java/com/nocobase/config/AmqpConfig.java`
   **只有 exchange/queue/binding 的 `@Bean`**（L3-11 的 import 全为 AMQP 领域对象），
   **没有任何连接工厂/地址配置**。
2. `backend-java/src/main/resources/application.yml` 中 **`grep -n "rabbit"` 为 0 命中**
   → 没有 `spring.rabbitmq.*` 显式配置
   → Spring Boot 使用**自动配置默认值：`localhost:5672`，用户 `guest`/`guest`**
3. `docker-compose.yml` 给 backend-java 注入的是：
   ```
   RABBITMQ_HOST: rabbitmq
   RABBITMQ_PORT: 5672
   ```
   **但 Spring Boot 只识别 `SPRING_RABBITMQ_*` 前缀的环境变量**（relaxed binding），
   `RABBITMQ_HOST` **不会被 Spring 读取** → Java 仍连 `localhost:5672` → 连不上。
4. compose 的 rabbitmq 服务默认用户为 `${RABBITMQ_USER:-guest}` / `${RABBITMQ_PASSWORD:-guest}`
   → 即使地址对了，用 `guest` 连接也会被 RabbitMQ 默认策略拒绝（guest 禁止远程连接）。

### 实测证据

```
Java 日志：AmqpConnectException: java.net.ConnectException: Connection refused
           （SimpleMessageListenerContainer 反复重连）
RabbitMQ 日志：closing AMQP connection ... user: 'guest'
```

### 要求

1. **compose 给 backend-java 注入 Spring 能识别的 MQ 配置**（关键，缺 `SPRING_` 前缀是当前根因）：
   ```
   SPRING_RABBITMQ_HOST: rabbitmq
   SPRING_RABBITMQ_PORT: 5672
   SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-guest}
   SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-guest}
   ```
   保留原有 `RABBITMQ_HOST/PORT`（其它脚本可能引用），**不要删**。
2. **替换 guest 默认用户**（安全项）：
   - 在 `.env.example`（若存在）或 compose 注释中说明应配置非 guest 用户；
   - 若你要让当前环境真的可用，需在 `.env` 设置 `RABBITMQ_USER` / `RABBITMQ_PASSWORD`（非 guest），
     **并把值写进 compose 的 rabbitmq 与 backend-java 两处**（保持一致）；
   - **注意**：修改 `RABBITMQ_DEFAULT_USER/PASS` 后，已有 volume（`rabbitmqdata`）中的用户不会自动更新，
     必要时需重建 rabbitmq 数据卷或在容器内 `rabbitmqctl add_user` —— 若遇到，明确报告。
3. 若你判断应在 `application.yml` 显式写 `spring.rabbitmq.*`（而非纯环境变量）也可以，
   但**必须保证容器内 host 是服务名 `rabbitmq` 而非 localhost**。
4. 补一条**证明 MQ 真连上**的验证：
   - 重启后 `docker compose logs backend-java` 中**不再出现** `AmqpConnectException`；
   - （推荐）在 RabbitMQ 管理界面/日志中看到来自 Java 容器的**成功**连接（user 为非 guest 或已放行的用户）。

### 验收
- `docker compose config -q` 通过
- 重启 backend-java 后，日志 2 分钟内**无** `AmqpConnectException`
- 贴出「修复前 / 修复后」的日志对比（证明真的连上了）

---

## §3 F2：跨服务 JWT 算法不匹配（AI 链路被拒）

### 现状（已核实）

**Java 侧（签发）**：
- `backend-java/src/main/java/com/nocobase/auth/keystore/KeyRingService.java`
  L59-60：`@Value("${app.jwt.secret}") String initialSecret` → 作为初始 active key（kid=k-0）
- `backend-java/src/main/resources/application.yml` **L77**：
  ```
  jwt:
    secret: ${JWT_SECRET:dev_jwt_secret_at_least_32_characters_long_for_hs256}
  ```
- `backend-java/src/main/java/com/nocobase/auth/JwtService.java` **L57-73**：
  `SecretKey signingKey = toHmacKey(entry.secret()); ... .signWith(signingKey)`
  → jjwt 根据密钥长度**自动选择算法**，实测签发出的 token 头为 **`{"kid":"k-0","alg":"HS384"}`**

**Python 侧（校验）**：
- `backend-python/src/nocobase_py/config.py` **L36**：`jwt_algorithm: str = "HS256"`（**固定单值**）
- `backend-python/src/nocobase_py/security.py` **L24-38** `_decode_token()`：
  ```python
  payload = jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
  ```
  → 只接受 HS256，**HS384 的 token 必然 `JWTError` → 返回 None → 401「token 无效或已过期」**

**密钥是否同源**：是。compose 两边都注入同一个 `JWT_SECRET`（L27），因此**只是算法不一致**，不需要同步密钥。

### 实测证据

```
拿 Java 签发的用户 JWT 调 Python：
POST http://localhost:8000/api/ai/chat  → {"detail":"token 无效或已过期"}
容器内 AI 端点返回：「（AI 服务不可用，以下为知识库检索结果）」
```

> 注：`/api/ai/embedding` 走的是**服务间 token**（`INTERNAL_SERVICE_TOKEN`，
> 由 `get_service_or_user` 先比对 internal token），**不受影响，仍 200** ——
> 这解释了为什么向量检索正常、只有 AI 聊天/大纲/润色降级。

### 要求

1. **Python 侧支持多算法**（推荐，最小改动）：
   - `config.py` 增加 `jwt_algorithms: list[str] = ["HS256", "HS384", "HS512"]`
     （保留 `jwt_algorithm` 以兼容既有引用，或一并替换为列表）
   - `security.py` 的 `jwt.decode(..., algorithms=settings.jwt_algorithms)`
   - **注意**：jjwt 按密钥长度自动选算法，因此接受三者是合理的；
     但**不要**无脑放宽到允许 `none`。
2. 或者（备选）：让 Java 固定签发 HS256 —— 需改 `JwtService` 显式指定算法。
   **二选一并在回报中说明理由**。推荐方案 1（不动 Java 签名链路，风险低）。
3. 补测试（Python）：
   - 用**HS384** 签发的 token 能被 `_decode_token` 正确解析（证明主路径真被执行）
   - 用**错误密钥**签发的 token 返回 None（反向用例）
   - 用 HS256 签发的 token 仍可解析（向后兼容）
4. 端到端验证：容器内调 `POST /api/wiki/pages/{id}/ask`，
   确认**不再返回**「AI 服务不可用」的降级文案（若 Python 侧 LLM 未配置而走 simulated，
   也应返回 simulated 答案而非"不可用"）。

### 验收
- Python 测试全绿且含 HS384 用例
- 容器内 AI 端点实测输出（贴命令与响应片段）
- `mvn -o test` 未受影响（若你没改 Java，应仍为 1214）

---

## §4 已核实为「无害」，禁止重复劳动

| 项 | 核实结论 |
|---|---|
| CORS 白名单 `localhost:5173/3000` | 浏览器来源本就是 localhost，**无需改** |
| `application.yml` 中 `POSTGRES_HOST`/`REDIS_HOST` 默认 localhost | compose env 已覆盖，**无需改** |
| LDAP 占位 `ldap://localhost:389` | 未启用 LDAP，**无需改** |
| OTEL 默认端点 `localhost:4318` | K8s 由 env 注入，compose 无 Jaeger，**无需改** |
| Python `config.py` L29 `java_backend_url` 默认 localhost | compose 已注入 `JAVA_BACKEND_URL: http://backend-java:8080` 覆盖，**无需改** |
| `embedding_service.py` 注释提到 8001 | 仅注释（该服务不存在），**无需改** |

---

## §5 交付自检清单

**F1 RabbitMQ**
- [ ] compose 注入了 `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD`
- [ ] 原 `RABBITMQ_HOST/PORT` 保留未删
- [ ] guest 用户问题已处理（换用户或明确说明）
- [ ] 重启后日志无 `AmqpConnectException`（贴修复前后对比）

**F2 JWT**
- [ ] Python 支持 HS384（并保留 HS256 兼容）
- [ ] 补了 HS384 正向用例 + 错误密钥反向用例
- [ ] 容器内 AI 端点实测不再降级（贴输出）

**门禁与红线**
- [ ] `mvn -o test` ≥ 1214 且 0 失败
- [ ] `npm run test:run` ≥ 271、`tsc` 0、`playwright` ≥ 64
- [ ] Python 测试全绿
- [ ] 新增 skip 0 / 删测试 0 / 弱化断言 0
- [ ] 未提交 `.env` 或密钥明文
- [ ] 无 §4 之外的越界改动

---

## §6 提交规范

按栈分开提交，信息写清「现状 → 改动 → 实测数字」：
- `[infra]` — compose 的 SPRING_RABBITMQ_* 注入、rabbitmq 用户
- `[python]` — JWT 多算法支持 + 测试

每提交一次都跑对应门禁。

---

## §7 回报要求

1. 按 §5 逐项勾选，未完成明确写"未做"及原因
2. F1：**修复前后日志对比**（证明真的连上了）
3. F2：容器内 AI 端点实测响应片段（证明不再降级）
4. 四项门禁 + Python 测试数字
5. 若选择"备选方案"（改 Java 签发算法），说明理由与影响面

---

## §8 审计口径（提前告知）

1. 复跑门禁 + Python 测试
2. 亲自 `docker compose logs backend-java` 查是否还有 `AmqpConnectException`
3. 亲自用 Java 签发 token 调 Python，确认 HS384 被接受；并确认错误密钥仍被拒
4. 反作弊：skip / 删测试 / 弱化断言，任一命中即打回
5. 范围检查：F1/F2 之外改动一律打回

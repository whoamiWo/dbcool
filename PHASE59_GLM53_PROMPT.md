# Phase 59 投喂提示词：修复 RabbitMQ 连接与跨服务 JWT 算法不匹配（Kilo Code + GLM-5.3）

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3（Kilo Code）**
> 基线提交：`809eba5`（PHASE 58 四项 P0 已闭环并推送）
> 创建日期：2026-09-29
> **用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行**
> 详细规格（含文件行号证据）见同目录 `PHASE59_GLM53_INFRA_FIX.md`

---

## 背景：为什么是这两项

PHASE 58 收尾时做「全局排查硬编码 localhost」，除已修的三处外，又实测发现两个**影响真实功能**的缺陷：

| 项 | 现象 | 影响 |
|---|---|---|
| **F1** RabbitMQ | Java 日志反复 `AmqpConnectException: Connection refused` | 异步任务、死信、MQ 触发的工作流**全部失效** |
| **F2** JWT 跨服务 | 容器 AI 端点返回「（AI 服务不可用，以下为知识库检索结果）」 | AI 聊天/大纲/润色**全部降级为检索** |

其中 **F1 本质是 localhost 问题的第五处**（Spring 不读缺 `SPRING_` 前缀的环境变量）。

---

## 投喂提示词（以下整段复制）

```
请阅读并严格执行以下 Phase 59 任务：修复 RabbitMQ 连接与跨服务 JWT 算法不匹配。

项目：/home/who/multistack-project（DBCool 综合企业协作平台）
基线：origin/main = 809eba5（PHASE 58 的 4 项 P0 已全部闭环并推送）
门禁基线（执行前复跑，最终须 ≥ 基线且 0 失败）：
  mvn -o test = 1214/0/0 ｜ npm run test:run = 271 ｜ npx tsc --noEmit = 0 ｜ npx playwright test = 64
  cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q = 8 passed / 1 skipped

执行前必读：同目录 PHASE59_GLM53_INFRA_FIX.md（每项含文件行号证据）。
若提示词与该文件冲突，以该文件的行号证据为准。

============================================================
§0 严禁回滚（PHASE 58 已完成并推送）
============================================================
3d06532 [java] P0-3 batch-upsert + jsonb 序列化
6441307 [python] P0-1 备份覆盖 Postgres 主库 + Dockerfile 装客户端
17de634 [frontend] P0-2 钉钉/企微 405 + 真回归测试
b3afd73 [infra] P0-4 粘滞 + compose 注入 POSTGRES_*/REDIS_*
809eba5 [infra] 全局排查 localhost（修 AI_PYTHON_URL）

方法论（本项目已踩 4 次）：容器内 localhost 指向容器自身。
容器 A 调容器 B 的地址必须 @Value 可配 + compose 用服务名注入。
已修四处：AI_SERVICE_URL、backup.py 的 POSTGRES/REDIS、AI_PYTHON_URL；本次 F1 是第五处。

============================================================
§1 红线（违反即打回）
============================================================
1. 严禁 it.skip / @Disabled / 删测试 / 弱化断言 / 改断言阈值。
2. 严禁臆造配置项：Spring Boot relaxed binding 规则必须先确认再写。
3. 严禁「只打日志」式修复；严禁 catch 后仅 console.error 或空 catch。
4. 严禁提交 .env 或密钥明文。
5. **严禁扩大范围**：除 F1、F2 外不要动其他文件。
6. 每个修复必须给出实测输出，不许只说"应该能连上"。

============================================================
§2 F1：RabbitMQ 连接失败
============================================================
现状（我已核实，勿重复排查）：
- AmqpConfig.java 只有 exchange/queue/binding 的 @Bean，**无连接配置**
- application.yml 中 grep "rabbit" = 0 命中 → 无 spring.rabbitmq.* 配置
  → Spring Boot 用默认值：localhost:5672，用户 guest/guest
- compose 给 backend-java 注入的是 RABBITMQ_HOST=rabbitmq / RABBITMQ_PORT=5672
  **但 Spring 只识别 SPRING_RABBITMQ_* 前缀**，RABBITMQ_HOST 不会被读取
  → Java 仍连 localhost:5672 → Connection refused
- compose 的 rabbitmq 默认用户 ${RABBITMQ_USER:-guest}，而 guest 禁止远程连接

实测证据：
  Java 日志：AmqpConnectException: java.net.ConnectException: Connection refused
  RabbitMQ 日志：closing AMQP connection ... user: 'guest'

要求：
1. compose 给 backend-java 注入 Spring 能识别的配置（关键）：
     SPRING_RABBITMQ_HOST: rabbitmq
     SPRING_RABBITMQ_PORT: 5672
     SPRING_RABBITMQ_USERNAME: ${RABBITMQ_USER:-guest}
     SPRING_RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-guest}
   保留原有 RABBITMQ_HOST/PORT（其它脚本可能引用），不要删。
2. 替换 guest 默认用户（安全项）：在 .env 设非 guest 的 RABBITMQ_USER/PASSWORD，
   并同步到 compose 的 rabbitmq 与 backend-java 两处。
   注意：改 RABBITMQ_DEFAULT_USER/PASS 后，已有 volume(rabbitmqdata) 中的用户不会自动更新，
   必要时在容器内 rabbitmqctl add_user 或重建数据卷 —— 若遇到，明确报告。
3. 若你选择在 application.yml 显式写 spring.rabbitmq.* 也可以，
   但必须保证容器内 host 是服务名 rabbitmq 而非 localhost。

验收：
- docker compose config -q 通过
- 重启 backend-java 后 2 分钟内日志**无** AmqpConnectException
- **必须贴出修复前后的日志对比**（证明真的连上了）

============================================================
§3 F2：跨服务 JWT 算法不匹配
============================================================
现状（我已核实）：
- Java：KeyRingService L59 @Value("${app.jwt.secret}") 作为 active key(kid=k-0)；
  application.yml L77：jwt.secret = ${JWT_SECRET:...}；
  JwtService L57-73：toHmacKey(entry.secret()) → .signWith(signingKey)
  → jjwt 按密钥长度自动选算法，实测 token 头为 {"kid":"k-0","alg":"HS384"}
- Python：config.py L36 jwt_algorithm = "HS256"（固定单值）；
  security.py L24-38 的 jwt.decode(..., algorithms=[settings.jwt_algorithm])
  → 只接受 HS256，HS384 token 必 JWTError → None → 401「token 无效或已过期」
- **密钥同源**：compose 两边注入同一个 JWT_SECRET，因此只是算法不一致，无需同步密钥。

实测证据：
  用 Java 签发的用户 JWT 调 Python /api/ai/chat → {"detail":"token 无效或已过期"}
  容器内 AI 端点 → 「（AI 服务不可用，以下为知识库检索结果）」
  注：/api/ai/embedding 走服务间 token(INTERNAL_SERVICE_TOKEN)，不受影响仍 200
  → 这解释了为什么向量检索正常、只有 AI 聊天/大纲/润色降级。

要求：
1. 推荐方案（改动最小）：Python 支持多算法
   - config.py 增加 jwt_algorithms: list[str] = ["HS256","HS384","HS512"]
     （保留 jwt_algorithm 兼容既有引用，或一并替换为列表）
   - security.py 的 jwt.decode(..., algorithms=settings.jwt_algorithms)
   - **不要**放宽到允许 none 算法。
2. 备选方案：让 Java 固定签发 HS256（改 JwtService 显式指定算法）。
   二选一并在回报说明理由。推荐方案 1（不动 Java 签名链路，风险低）。
3. 补 Python 测试：
   - HS384 签发的 token 能被 _decode_token 正确解析（证明主路径真被执行）
   - 错误密钥签发的 token 返回 None（反向用例）
   - HS256 签发的 token 仍可解析（向后兼容）
4. 端到端验证：容器内调 POST /api/wiki/pages/{id}/ask，
   确认不再返回「AI 服务不可用」降级文案。

验收：Python 测试全绿且含 HS384 用例；容器内 AI 端点实测输出（贴片段）。

============================================================
§4 已核实无害，禁止重复劳动
============================================================
- CORS 白名单 localhost:5173/3000（浏览器来源本就是 localhost）
- application.yml 中 POSTGRES_HOST/REDIS_HOST 默认 localhost（compose env 已覆盖）
- LDAP 占位 ldap://localhost:389（未启用）
- OTEL 默认端点 localhost:4318（K8s 由 env 注入）
- Python config.py L29 java_backend_url 默认 localhost（compose 已注入 JAVA_BACKEND_URL 覆盖）
- embedding_service.py 注释里的 8001（仅注释）

============================================================
§5 环境与验证命令
============================================================
- Java 镜像必须用离线方式重建（主 Dockerfile 拉 eclipse-temurin 极慢，禁用）：
    cd backend-java && mvn -o package -DskipTests
    docker build -f Dockerfile.offline -t nocobase-backend-java:latest .
    cd .. && docker compose up -d --no-build backend-java
- Python 改代码后：docker compose up -d --no-build backend-python
- 查 MQ 是否还报错：
    docker compose logs backend-java --tail 80 | grep -c "AmqpConnectException"
- 取用户 token 测 AI 端点：
    TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" \
      -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['access_token'])")
    curl -s -X POST http://localhost:8000/api/ai/chat -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" -d '{"prompt":"你好"}'
    curl -s -X POST "http://localhost:8080/api/wiki/pages/bbbbbbbb-0000-0000-0000-000000000001/ask" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"question":"讲什么"}'
- Python 测试：cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q

============================================================
§6 提交与回报
============================================================
按栈分开提交：[infra] RabbitMQ 配置、[python] JWT 多算法 + 测试。
信息写清「现状 → 改动 → 实测数字」，每次提交都跑对应门禁。

回报必须包含：
1. F1：修复前后日志对比（AmqpConnectException 从有到无）
2. F2：容器内 AI 端点实测响应片段（证明不再降级）
3. 四项门禁 + Python 测试数字
4. 若选备选方案（改 Java 算法），说明理由与影响面
5. 未完成项明确写"未做"及原因，不许虚报

============================================================
§7 审计口径（提前告知）
============================================================
① 复跑门禁 + Python 测试；
② 亲自查 docker compose logs backend-java 是否还有 AmqpConnectException；
③ 亲自用 Java 签发的 token 调 Python，确认 HS384 被接受、错误密钥仍被拒；
④ 反作弊（skip/删测试/弱化断言，命中即打回）；
⑤ 范围检查（F1/F2 之外改动一律打回）。

一句话：**要看到日志和响应，不是"配置了应该就好了"。**
```

# Phase 58 返工提示词（投喂 GLM-5.3）

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3（Kilo Code）**
> 返工日期：2026-09-29　基线：`79be8d3`（你的改动**全部未提交**，仍在工作区）
> **用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行**

---

## 上轮审计结论（先看这个，别急着改）

门禁全绿（mvn 1210/0/0、vitest 269、tsc 0、e2e 64），反作弊检查（skip 0、测试只增、无越界）**通过**。
但按审计口径逐项实测后：**4 项中 2 项打回、1 项需补、1 项通过**。

| 项 | 判定 | 一句话原因 |
|---|---|---|
| P0-1 备份 | ❌ **打回** | 代码写了 `pg_dump`，但**运行环境根本没有 pg_dump/redis-cli** → 功能必然失败；测试全被 stub 掩盖 |
| P0-2 钉钉 | ⚠️ **有条件通过** | 主修复正确（含企微一并修、字段名未臆造），但防回归测试是**假断言** |
| P0-3 块编辑 | ❌ **打回** | 端点有了，但**用户输入普通文字保存必 500**（jsonb 非法 JSON） |
| P0-4 Huddle | ✅ **通过** | 选 B 且诚实标注 TODO，Ingress 真配了 cookie 粘滞 —— **此项不要再动** |

### 三个必须理解的失败根因（我没法替你绕过）

1. **「写了调用」≠「能运行」**：`pg_dump` 代码正确，但宿主机与容器都无该二进制、Dockerfile 未装 → 生产必 `FileNotFoundError`。
2. **stub 测试掩盖一切**：`_postgres_dump = lambda: b"FAKE-PG-DUMP-DATA"` 让所有用例绿，但从未真调过 pg_dump（与 PHASE 57 同一翻车模式）。
3. **jsonb 列不接受任意字符串**：实体 `contentJson`(String) → 列 `content` 是 **jsonb**，而 `content.toString()` 产出 `{text=...}` 或裸文本，**都不是合法 JSON** → 插入即 500。

---

## 投喂提示词（以下整段复制）

```
请执行 Phase 58 返工。上一轮你的改动未通过审计：门禁全绿但 2 项主功能实际不可用。
改动仍在工作区未提交（基线 79be8d3），**不要推倒重来，只修下列 R1–R7**。

============================================================
§0 上轮做对的（保留，严禁回退/重改）
============================================================
- P0-4 Huddle：k8s/05-ingress.yaml 的 affinity: cookie + session-cookie-name + 两处 TODO 注释 —— 已通过，别动。
- P0-2 主修复：DingTalkLoginPage 改 GET、字段名用后端真实的 data.data.url（未臆造 authUrl）、
  integrations.ts 中 dingtalkApi 与 wecomApi 的 getAuthUrl 均改 GET —— 已核对后端确为 @GetMapping，正确。
- P0-3 前端：saveError + MUI <Alert> 真 UI 提示、模板端点改 /kb/{kbId}/templates、
  反向链接与模板都做了 data?.data unwrap —— 均正确，保留。
- P0-1 backup.py：_postgres_dump / _restore_postgres / create_backup 的 fail-closed 逻辑、参数全走环境变量、
  PGPASSWORD 经 env 传递 —— 实现质量好，保留，只补 §2 的三件事。

============================================================
§1 红线（上轮违反的已加粗，再犯直接打回）
============================================================
1. 严禁 `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改断言阈值。门禁只增不减。
2. 严禁臆造 API；改方法签名必须同步既有测试。
3. **严禁在测试中 stub 掉被测主路径函数**（上轮 `postgres_stubbed` 把 `_postgres_dump`
   换成 `lambda: b"FAKE-PG-DUMP-DATA"`，测试全绿但功能从未真跑）。
   允许 stub 的是**外部不可控依赖**（网络、第三方 API），**不允许 stub 你要修复的那个函数本身**。
4. **每个修复必须配一条「证明主路径真被执行」的用例**，并给出**实测输出**（不是"测试通过"四个字）。
5. 严禁「只打日志」式修复；严禁 catch 后仅 console.error 或空 catch。
6. 严禁提交 .env 或密钥明文。
7. **严禁扩大范围**：除 R1–R7 外不要动其他文件（尤其别动 Dockerfile/pyproject 里与你无关的
   torch CPU 源改动 —— 那是 CodeBuddy 此前未提交的 PHASE 57 遗留，不是你的）。

============================================================
§2 R1–R3：备份返工（P0-1 打回项）
============================================================
实测证据（我已验证）：
  宿主机 `which pg_dump` = 无
  容器 `command -v pg_dump` = 无 ；`command -v redis-cli` = 无
  backend-python/Dockerfile 中**未安装** postgresql-client / redis-tools
  → 调用必然抛 FileNotFoundError → 备份整体失败。

R1. 在 backend-python/Dockerfile 的**运行时阶段**安装客户端（基础镜像 python:3.12-slim = debian）：
      apt-get update && apt-get install -y --no-install-recommends postgresql-client redis-tools \
      && rm -rf /var/lib/apt/lists/*
    装完必须验证：`docker compose build backend-python` 后容器内 `command -v pg_dump` 有输出。
    注意构建需联网；若构建失败**明确报告**，不要静默。
R2. 补一条**不 stub** 的真备份测试（关键）：
    - 若 CI 环境确实无 pg_dump，必须**明确写出该用例被跳过/降级的原因**，
      并另外提供「在装有 pg_dump 的环境中手动执行」的验证命令与**你实际执行的输出**；
    - 不许只靠 `postgres_stubbed` 交差。
R3. 做真实演练并给证据（上轮完全没给）：
    对本地 docker 的 postgres 执行「备份 → 破坏 → 恢复 → 校验」，回报必须包含：
    dump 字节数、恢复后记录数、耗时、RPO/RTO。
    参考命令（端口/凭据按 .env）：
      docker compose exec -T postgres psql -U nocobase -d nocobase -c "SELECT count(*) FROM wiki_page;"
      # 触发备份后查看 tar 内 postgresql/dump.pg 的大小

============================================================
§3 R4–R5：块编辑返工（P0-3 打回项）
============================================================
实测证据（我起最新代码实例在 8082 实测，三种 content 形态）：
  content = 对象 {"text":"..."}        → 500  invalid input syntax for type json
  content = 纯文本 "这是一段普通文本"   → 500  invalid input syntax for type json
  content = 合法 JSON 字符串           → 200 落库成功（content = {"text": "字符串内容"}）

根因（必须理解再改）：
  WikiBlockEntity 字段 `contentJson` 是 **String**，映射到列 **content**，类型是 **jsonb**
  （@Column(name="content", columnDefinition="jsonb")）。
  而 WikiBlockService 用 `entity.setContentJson(content.toString())`：
    - 对象 → Java Map.toString() = `{text=审计验证块}`（非 JSON）
    - 纯文本 → 裸字符串（非 JSON）
  → 写入 jsonb 列必炸。
  前端 Block.content 类型是 **string**（NotionStyleEditor.tsx L61），存的就是用户输入的普通文字
  → **任何真实编辑都会 500**。既有 createBlocksForPage L53 有同样 bug。

R4. 用 ObjectMapper 做真正的 JSON 序列化（不要用 toString()）：
    - 对象/Map → 序列化为 JSON 对象字符串
    - 纯文本 → 包成合法结构（如 {"text": "<内容>"}）或 JSON 字符串转义
    - **顺带修既有 WikiBlockService.createBlocksForPage L53**（同样的 toString bug）
    - 读回时要能解析（确认 getBlocksByPageId / toBlockDto 与写入格式一致，别存一个格式读另一个）
R5. 补后端测试 `batchUpsertBlocks`（上轮 0 测试，违反红线）：
    至少覆盖：成功落库（**断言落库后 content 是合法 JSON**）、blocks 为空→400、
    pageId 不存在→404、跨租户→403、ACL 拒绝→403。
    用例数必须让 `mvn -o test` **> 1210**。
验收：R4/R5 做完后，用 §6 的命令亲自实测**纯文本与对象两种 content 都返回 200 且落库为合法 JSON**。

============================================================
§4 R6：钉钉防回归测试返工（P0-2 唯一缺口）
============================================================
上轮测试是假断言（我已核实）：
  it('GET ... 与后端 @GetMapping 一致（回归测试防 405）', () => {
    expect(integrations.dingtalkApi.getAuthUrl).toBeDefined();   // ← 只断言"已定义"
  });
问题：① 改回 POST 它照样绿，拦不住回归；② 它 mock 的是 `@/api/integrations`，
而 DingTalkLoginPage 实际用的是**裸 fetch('/api/dingtalk/auth-url', {method:'GET'})** —— 测试与实现脱节。

R6. 二选一，但必须做到「把 method 改成 POST 时测试会失败」：
    A）页面改用 `dingtalkApi.getAuthUrl()`（消除裸 fetch），测试断言调用的方法是 GET
       （如 mock api 层后断言 client.get 被调用 / 或断言请求 method === 'GET'）；
    B）保留裸 fetch，测试直接 mock 全局 fetch 并断言其 method 为 'GET'。
    另：给企微 wecomApi.getAuthUrl 也补一条同样的 GET 断言（防止企微回归）。
验收：把 method 临时改成 POST 跑一次测试，**必须看到失败**，再把截图/输出贴进回报（证明它真能拦截）。

============================================================
§5 R7：提交
============================================================
上轮改动全部未提交。本次按栈分开提交（[python] / [java] / [frontend] / [infra]），
信息写清「现状 → 改动 → 门禁实测数字」，每次提交都跑对应门禁。

============================================================
§6 环境与验证方法（照抄即可复现我的审计）
============================================================
- 起最新代码实例（**注意：容器跑的是旧镜像，不含你的新端点，别拿容器验证**）：
    cd backend-java && export POSTGRES_PASSWORD=$(grep '^POSTGRES_PASSWORD=' ../.env | cut -d= -f2) \
      && export POSTGRES_HOST=localhost \
      && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
    （8080/8081 常被占用，换一个未占用端口；启动失败日志里会写 Port XXXX was already in use）
- 登录取 token：
    curl -s -X POST http://localhost:8082/api/auth/login -H "Content-Type: application/json" \
      -d '{"username":"admin","password":"admin123"}'
- 实测块保存（三种 content 都要试）：
    curl -s -X POST http://localhost:8082/api/wiki/blocks/batch-upsert \
      -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
      -d '{"pageId":"bbbbbbbb-0000-0000-0000-000000000001","blocks":[{"type":"paragraph","content":"这是一段普通文本"}]}'
- 查库确认落库与格式：
    docker compose exec -T postgres psql -U nocobase -d nocobase \
      -c "SELECT id, type, content FROM wiki_block WHERE page_id='bbbbbbbb-0000-0000-0000-000000000001';"
- 查 pg_dump 是否存在：
    docker compose exec -T backend-python sh -c "command -v pg_dump; command -v redis-cli"
- Python 镜像重建：docker compose build backend-python（需联网装 apt 包）

============================================================
§7 回报要求（缺一即打回）
============================================================
1. R1：容器内 `command -v pg_dump` 的输出（证明装上了）。
2. R3：真实演练的 dump 字节数、恢复后记录数、耗时。
3. R4：三种 content（对象/纯文本/JSON 字符串）实测的 HTTP 码 + 落库后 content 值。
4. R5：`mvn -o test` 的数字（必须 > 1210）+ 新增用例名。
5. R6：把 method 改回 POST 后测试失败的输出（证明拦截有效）。
6. 四项门禁数字（mvn / vitest / tsc / playwright）。
7. 若某项因环境限制无法完成，明确说明，不许静默跳过。

============================================================
§8 审计口径（我会怎么验，提前告知）
============================================================
① 起最新代码实例实测（不看容器旧镜像）；
② 亲自用纯文本 content 调 batch-upsert，确认 200 且落库为合法 JSON；
③ 亲自在容器内执行 pg_dump 备份与恢复，核对你的数字；
④ 检查测试里是否还有 `lambda: b"FAKE-"` 之类的 stub 主路径行为（有即打回）；
⑤ 反作弊：skip / 删测试 / 弱化断言，任一命中即打回；
⑥ 范围检查：R1–R7 之外的改动一律打回。

一句话：**这次要能真的跑起来，不是代码看起来对。**
```

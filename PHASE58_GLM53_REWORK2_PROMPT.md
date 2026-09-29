# Phase 58 第二轮返工提示词（投喂 GLM-5.3）

> 编排/验收方：CodeBuddy　**执行方：GLM-5.3（Kilo Code）**
> 返工日期：2026-09-29　基线：`79be8d3`（你的改动**仍全部未提交**，在工作区）
> **用法：将下方「投喂提示词」整段复制给 GLM-5.3 执行**

---

## 第二轮审计结论

门禁：`mvn` **1214/0/0**（1210→+4）、`vitest` 269、`tsc` 0、`e2e` 64；反作弊（skip 0、只增不减）通过。

| 项 | 判定 | 说明 |
|---|---|---|
| **R4** jsonb 序列化 | ✅ **已通过** | 实测纯文本/对象 content 均 `code 0`，落库 `{"text":"..."}` 合法 jsonb（上轮两者均 500） |
| **R5** 后端测试 | ✅ **已通过** | `WikiBlockServiceBatchUpsertTest` 4 用例质量合格，含序列化断言与 `verify(save, times(2))` |
| R1 装 pg_dump | ✅ 代码正确 / ⚠️ 构建受阻 | Dockerfile 已写对，但 rebuild 卡在 `deb.debian.org` 下载 |
| **R2** 真备份测试 | ❌ 未完成 | stub 原封不动 |
| **R3** 真实演练 | ❌ 无证据 | 无 dump 字节数/恢复记录数 |
| **R6** 钉钉真断言 | ❌ 未改 | 测试文件与上轮**逐字相同** |
| **R7** 提交 | ❌ 未提交 | 最新 commit 仍 `79be8d3` |

**本轮只修 R2 / R3 / R6 / R7（外加 R1 的构建验证）。R4、R5 已通过，严禁改动。**

---

## 投喂提示词（以下整段复制）

```
请执行 Phase 58 第二轮返工。第二轮审计：R4、R5 已通过（我实测确认），
剩余 R2/R3/R6/R7 未完成。**改动仍在工作区未提交（基线 79be8d3）**。

============================================================
§0 已通过、严禁改动（改坏即打回）
============================================================
- **R4**：WikiBlockService 用 ObjectMapper 序列化（纯文本包成 {"text":"..."}、对象序列化为 JSON）。
  我实测：纯文本与对象 content 均返回 code 0，落库为合法 jsonb —— **已通过，别再动**。
- **R5**：backend-java/src/test/java/com/nocobase/wiki/WikiBlockServiceBatchUpsertTest.java
  （4 用例：成功+序列化断言、空→400、页面不存在→404、跨租户→403）—— **已通过，别再动**。
- P0-4 Huddle：k8s/05-ingress.yaml 的 affinity: cookie + TODO 注释 —— 已通过，别动。
- P0-2 主修复：DingTalkLoginPage 改 GET、字段用 data.data.url、integrations.ts 中
  dingtalkApi/wecomApi 的 getAuthUrl 均改 GET —— 正确，别动（只补测试，见 R6）。
- P0-1 backup.py 的 _postgres_dump / _restore_postgres / fail-closed 逻辑 —— 质量好，别动。
- Dockerfile 中与你无关的 torch CPU 源改动是 CodeBuddy 的 PHASE 57 遗留，**不要动**。

============================================================
§1 红线（与上一轮相同，再犯直接打回）
============================================================
1. 严禁 it.skip / @Disabled / 删测试 / 弱化断言 / 改断言阈值。
2. **严禁在测试中 stub 掉被测主路径函数**（上一轮 `postgres_stubbed` 把 `_postgres_dump`
   换成 `lambda: b"FAKE-PG-DUMP-DATA"`，第二轮仍原封不动 —— 这就是 R2 没过的唯一原因）。
   允许 stub 外部不可控依赖（网络/第三方 API），**不许 stub 你要修复的函数本身**。
3. **每个修复必须配一条「证明主路径真被执行」的用例 + 实测输出**。
4. 严禁「只打日志」式修复；严禁 catch 后仅 console.error 或空 catch。
5. 严禁提交 .env 或密钥明文。
6. 严禁扩大范围（除本提示词列出的项外不要动其他文件）。

============================================================
§2 R2 + R3：备份（这两个是一件事，必须一起做）
============================================================
现状（我第二轮核实的原文）：
  backend-python/tests/test_backup_restore.py L83-85：
      def postgres_stubbed(monkeypatch):
          """stub PostgreSQL dump，避免测试依赖 pg_dump 命令。"""
          backup_service._postgres_dump = lambda: b"FAKE-PG-DUMP-DATA"
  L93 / L123 / L136 / L186 的用例**全部**注入了 postgres_stubbed
  → 没有任何一条真调 pg_dump → 测试全绿但功能未验证（这正是红线第 2 条）。

R2. 补一条**不 stub** 的备份测试，二选一：
    A）真实路径：在有 pg_dump 的环境直接调用 `backup_service._postgres_dump()`，
       断言返回字节数 > 0 且能被 pg_restore 识别（可用 `pg_restore -l` 列出内容）。
    B）若 CI 环境确实无法安装 pg_dump：
       - 必须**在测试文件顶部或用例 docstring 明确写出**"此环境无 pg_dump，故跳过真实用例"的原因；
       - 并**额外提供你在装有 pg_dump 的环境下手动执行的命令与真实输出**（贴进回报）；
       - **不许只留 stub 用例就交差**。
    （提示：可用 pytest.mark.skipif + shutil.which("pg_dump") 做条件跳过，
      但**必须同时给出手动执行的真实输出**，否则仍算未完成。）

R3. 做真实演练（上一轮完全没给，第二轮仍没有）：
    执行「备份 → 破坏 → 恢复 → 校验」，回报必须包含：
      - dump 字节数（如 du -sh postgresql/dump.pg 或 ls -l）
      - 恢复前后的**记录数**（如 SELECT count(*) FROM wiki_page; 的 before/after）
      - 耗时、RPO/RTO 说明
    前置条件：环境需要有 pg_dump/psql。
      - 方案一：宿主机安装（sudo apt-get install -y postgresql-client，需 sudo）
      - 方案二：在重建后的 backend-python 容器内执行（见 R1）
    参考命令：
      docker compose exec -T postgres psql -U nocobase -d nocobase -c "SELECT count(*) FROM wiki_page;"

============================================================
§3 R1（附带）：完成镜像构建验证
============================================================
你的 Dockerfile 已写对（L59-60 的 postgresql-client + redis-tools），**代码我认可**。
但我 rebuild 时卡在 deb.debian.org 下载（3.5 分钟无进展）。
需要你：
  - 在 Dockerfile 的 apt 步骤改用**国内 debian 源**（如 mirrors.tuna.tsinghua.edu.cn /
    mirrors.aliyun.com），与项目已有的 `UV_DEFAULT_INDEX` 清华源做法保持一致；
  - 重新 `docker compose build backend-python`，并在回报给出：
      docker compose exec -T backend-python sh -c "command -v pg_dump; command -v redis-cli"
    的真实输出（证明容器内真的有了）。
  - 若构建仍失败，明确报告，不要静默。

============================================================
§4 R6：钉钉防回归测试（第二次要求，上一轮你没改）
============================================================
现状（我逐字比对过）：frontend/src/pages/auth/DingTalkLoginPage.test.tsx
与上一轮**完全相同**，仍是：
      it('GET /api/dingtalk/auth-url 与后端 @GetMapping 一致（回归测试防 405）', () => {
        expect(integrations.dingtalkApi.getAuthUrl).toBeDefined();   // ← 只断言"已定义"
      });
问题：① 改回 POST 它照样绿；② 它 mock 的是 '@/api/integrations'，
而页面用的是**裸 fetch('/api/dingtalk/auth-url', {method:'GET'})** —— 测试与实现脱节。
佐证：vitest 停在 269，说明你一个用例都没新增。

R6. 二选一，但必须满足「把 method 改成 POST 时测试会失败」：
    A）页面改用 dingtalkApi.getAuthUrl()（消除裸 fetch），测试断言走的是 GET；
    B）保留裸 fetch，测试 mock 全局 fetch 并**断言其 method === 'GET'**。
    另外：给企微 wecomApi.getAuthUrl 补一条同样的 GET 断言。
验收（必须做，否则视为未完成）：
    把 method 临时改成 POST 跑一次测试，**必须看到失败**，把失败输出贴进回报。

============================================================
§5 R7：提交
============================================================
两轮改动全部未提交（最新 commit 仍是 79be8d3）。按栈分开提交：
  [python] 备份测试/演练相关　[java] WikiBlockService + 测试（R4/R5 那部分）
  [frontend] 钉钉测试（R6）　[infra] Dockerfile 源 + k8s
信息写清「现状 → 改动 → 门禁实测数字」，每次提交都跑对应门禁。

============================================================
§6 验证命令（照抄即可复现我的审计）
============================================================
- 起最新代码实例（容器是旧镜像，别拿容器验；8080/8081/8082 常被占，用新端口）：
    cd backend-java && export POSTGRES_PASSWORD=$(grep '^POSTGRES_PASSWORD=' ../.env | cut -d= -f2) \
      && export POSTGRES_HOST=localhost \
      && mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8083
- 块保存实测（R4 已通过，仅回归自查用）：
    curl -s -X POST http://localhost:8083/api/wiki/blocks/batch-upsert \
      -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
      -d '{"pageId":"bbbbbbbb-0000-0000-0000-000000000001","blocks":[{"type":"paragraph","content":"这是一段普通文本"}]}'
    期望：code 0，content = {"text":"这是一段普通文本"}
- pg_dump 是否存在：
    docker compose exec -T backend-python sh -c "command -v pg_dump; command -v redis-cli"
- 门禁：
    cd backend-java && mvn -o test                 # 须 ≥ 1214
    cd frontend && npm run test:run                # 须 ≥ 269
    cd frontend && npx tsc --noEmit                # 0
    cd frontend && npx playwright test             # ≥ 64

============================================================
§7 回报要求（缺一即打回）
============================================================
1. R2：新增的真实用例名 + 它是如何不 stub 的；若环境无 pg_dump，写明原因 + 手动执行输出。
2. R3：dump 字节数、恢复前后记录数、耗时。
3. R1：容器内 `command -v pg_dump` / `command -v redis-cli` 的真实输出。
4. R6：把 method 改成 POST 后**测试失败的输出截图/文本**（证明拦截有效）+ 新增用例名。
5. R7：提交 hash 列表。
6. 四项门禁数字（mvn ≥1214 / vitest ≥269 / tsc 0 / playwright ≥64）。
7. 若某项因环境限制无法完成，明确说明，不许静默跳过。

============================================================
§8 审计口径（我会怎么验）
============================================================
① 看 test_backup_restore.py 里还有没有 `lambda: b"FAKE-"`，有即打回；
② 亲自在容器内跑 pg_dump 备份与恢复，核对你的数字；
③ 亲自把钉钉 method 改成 POST 跑测试，确认真的失败；
④ 复跑四项门禁；⑤ 反作弊（skip/删测试/弱化断言）；⑥ 范围检查。

一句话：**这次要看到真实执行的输出，不是"测试通过"四个字。**
```

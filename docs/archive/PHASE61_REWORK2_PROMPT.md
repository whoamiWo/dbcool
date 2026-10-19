# PHASE61 第二轮返工 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容投喂。

-----BEGIN PROMPT-----

你是本项目的执行开发。这是 **PHASE61 的第二轮返工**：你的上一轮返工提交 `b506f01`（"PHASE61 Rework"）经审计**仍不通过**。本轮只做下面五项（R1–R5），`b506f01` 中**已正确的部分必须保留、不得回退**。

## 0. 工作目录与背景

- 仓库根目录：`/home/who/multistack-project`
- 后端：`backend-java`（Spring Boot 3 + JPA + Flyway；已应用迁移最高 **V43**）
- 前端：`frontend`（React 19 + MUI v9 + Vite）
- 数据：PostgreSQL 15、Redis、MinIO；单测跑 H2
- 详细任务书见仓库根目录 `PHASE61_REWORK2_TASKS.md`

## 1. 先说你做对的部分（**必须保留，不得回退**）

**T1 的代码改造是真修好了** ✅：

- `ImMessageRepository#searchWithFullFilters`：keyword / mentions / 时间范围全部放在 **JPQL 查询侧**
- `ImMessageRepository#countWithFullFilters`：`total` 来自**独立的 count 查询**

即"先过滤、后分页"，`total` 是真实总数 —— 这正是第一轮打回的核心缺陷，你改对了。

实测门禁（CodeBuddy 亲跑）：`mvn -o test` **1233 / 0 / 0 / 0 + BUILD SUCCESS** ✅、`tsc --noEmit` **0** ✅、未含 `.env` ✅。

下面是**仍然不通过**的部分。

---

## 2. 五项返工任务

### R1（P0，会导致应用启动失败）V43 迁移在 Flyway 事务内必然失败

`backend-java/src/main/resources/db/migration/V43__im_message_search_index.sql` 用了：

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_im_message_content_tsv
ON im_message USING GIN (content_tsv);
```

CodeBuddy 直接在 PostgreSQL 上实测：

```
psql -c "BEGIN; CREATE INDEX CONCURRENTLY ...; COMMIT;"
→ ERROR:  CREATE INDEX CONCURRENTLY cannot run inside a transaction block
```

Flyway 默认把每个迁移包在事务里执行 → **该迁移一旦被应用，应用启动直接失败**。
现在没暴露，只是因为容器镜像还是旧的、还没跑到 V43。

**要求**：

1. **删掉 `CONCURRENTLY`**（改成普通 `CREATE INDEX IF NOT EXISTS`）。不要用"Flyway 非事务迁移"绕过——本项目用 Flyway 社区版，事务内执行最简单可靠，`im_message` 建索引的代价可接受。
2. **必须实测迁移能成功应用**：重启应用后确认 Flyway 迁移成功、`content_tsv` 列与 `idx_im_message_content_tsv` 索引存在、应用正常启动。

**验收**：贴 Flyway 迁移成功日志 + `psql` 查索引存在的输出。

---

### R2（P1）`content_tsv` / GIN 是死索引，没有任何查询用到

`ImMessageRepository.java`：

- L62 注释写着「使用 tsvector 全文索引（PostgreSQL）或 ILIKE 降级（H2）」——**与实现不符**
- 实际 L74、L93（查询与 count）仍是：
  ```java
  "and (:keyword is null or :keyword = '' or m.content is not null and lower(m.content) like lower(concat('%', :keyword, '%'))) "
  ```

即：建的 tsvector + GIN **没有任何查询用到**；`LIKE '%kw%'` 恒为 `Seq Scan`，永远走不了 GIN。

**要求**（二选一，**明确选择并说明理由**）：

- **方案 A（推荐）：真正接上 tsvector**
  1. 新增 native query：`content_tsv @@ plainto_tsquery('simple', :keyword)`，按 `ts_rank` 排序
  2. **按数据库方言切换**：PG 走 tsvector，H2 走 `ILIKE` 降级并**打日志**（沿用 Wiki FTS 的既有降级惯例，不得打破既有 1233 个用例）
  3. 成员/租户过滤必须保留，防越权
  4. **给出 PostgreSQL 上的 `EXPLAIN` 实证**（`Bitmap Index Scan` / `Index Scan`，非 `Seq Scan`）
- **方案 B：放弃索引化**
  1. 删除 `V43__im_message_search_index.sql`（不留死索引白占写入开销）
  2. 修正 L62 的误导注释，如实写明"当前为 `LIKE` 全表扫"

**无论选哪个，都不得让注释声称用了 tsvector 而实现是 LIKE —— 注释与实现不符本身就是缺陷。**

---

### R3（P0）T1 的测试把被测对象 mock 掉了，等于没测

`backend-java/src/test/java/com/nocobase/im/ImMessageControllerTest.java`：

```java
50:  messageSearchService = mock(MessageSearchService.class);
292: when(messageSearchService.searchMessages(eq(channelId), eq("kw"), ...)).thenReturn(...);
306: when(messageSearchService.searchMessages(eq(null), eq("budget"), ...)).thenReturn(...);
320: when(messageSearchService.searchMessages(eq(channelId), any(), eq(mentionedBy), ...)).thenReturn(...);
333: when(messageSearchService.searchMessages(eq(channelId), any(), any(), any(), eq(start), eq(end), ...)).thenReturn(...);
394: void advancedSearch_crossPageRecall()   ← 本应是 T1 bug 的回归防线
```

把**被测的 `MessageSearchService` 本身** mock 掉、再断言桩返回值 → 只验证了 Controller 的参数透传，**Service/Repository 的真实过滤逻辑一行没跑**。

后果：`advancedSearch_crossPageRecall`、`advancedSearch_totalCorrectness`（断言 `total=47`）**对 T1 的修复毫无约束力** —— 旧的错误实现同样能让它们通过。

**要求**：

1. **禁止** mock `MessageSearchService`（以及被测主路径上的任何 Service）。
2. 新增**真实跑 SQL 的集成测试**（`@DataJpaTest` 或 `@SpringBootTest` + 真实 DataSource），至少覆盖：
   - **跨页召回**：造 ≥ `size+1` 条消息，让唯一匹配项落在第 2 页 → `page=0&size=10` **必须命中**且 `total=1`（此用例用旧实现必挂，是防线）
   - `total` 正确性（匹配总数，非本页条数）
   - keyword 命中 / 无匹配 / 时间范围 / 提及过滤
   - 越权频道 → 空
   - 参数非法 → 400
3. 原 `ImMessageControllerTest` 里**只验证参数透传**的用例可以保留，但**不得**用它充当 T1 的回归防线。

**验收**：贴集成测试实际输出；并说明"去掉 mock 后，这些用例在旧实现下是否会失败"（会失败才算有效防线）。

---

### R4（P0）@提及前后端协议不一致 → 高亮永不生效

**后端**（`MessageService.java` L80-82）**手写 JSON 拼装**，只存 userId：

```java
RichTextParser.ParseResult parsed = RichTextParser.parse(body);
String mentionsJson = "[" + parsed.mentions().stream()
        .map(mention -> "\"" + mention.userId() + "\"")     // ← 只有 userId，没有 displayName
        .collect(Collectors.joining(",")) + "]";
m.setMentions(mentionsJson);
```

后端 DTO（`ImMessageDto.java` L20）：`String mentions`（JSON 字符串）

**前端**（`frontend/src/features/im/api.ts:67`）：

```ts
mentions?: Array<{ displayName: string; userId: string }>;   // ← 期望对象数组
```

前端 `MessageList.tsx` L63-66 起遍历 `mentions` 取 `mention.displayName` / `mention.userId` 拼正则 `@\{displayName\}:userId`；L315 调用处**确实传入了** `msg.mentions` ✅（接线没问题，问题在协议）。

**后果**：后端返回字符串 `["uuid1","uuid2"]`，前端按对象数组用 → `mentions.length` 是字符数（>0 成立），`for...of` 遍历字符串得到**单个字符**，`mention.displayName` = `undefined` → 正则变成 `@\{undefined\}:undefined` → **匹配不到，高亮永不生效**（且不报错，静默失效）。

**要求**：

1. **统一协议（推荐后端改结构化）**：
   - DTO 的 `mentions` 改为结构化类型（如 `List<ImMentionDto>`，`record ImMentionDto(String displayName, String userId)`），由 Jackson 序列化
   - **禁止手写 JSON 字符串拼装**（改用 `ObjectMapper` / 结构化类型），避免转义与类型漂移
   - 落库的 `im_message.mentions`（TEXT）仍存 JSON，但必须包含 `displayName` 与 `userId`
   - 注意 `ImMessageRepository` L75 的 `m.mentions like '%uuid%'` 过滤仍要能命中（JSON 里含 userId 即可）
   - 若选择保持 `String` 字段，则前端必须 `JSON.parse` 且两端结构一致 —— **二选一，但必须两端对齐**
2. **补前端渲染测试**（当前 vitest 275 = 基线，**零新增**）：`MessageList` 渲染用例，覆盖「提及渲染为 chip」+「普通文本不受影响」+「无 mentions 时不报错」
3. 通知链路保持复用 `NotificationService#createMentionNotification`（已接，L98-103）

**验收**：vitest 数字**必须 > 275**；**端到端实测**——容器内发一条带 `@{displayName}:userId` 的消息 → 被 @ 的人收到通知 → 前端消息列表渲染出高亮 chip（贴接口返回的 `mentions` JSON 与前端渲染证据）。

---

### R5（P1）清理误入库的编译产物

提交 `b506f01` 含 `backend-python/tests/contract/__pycache__/test_openapi_contract.cpython-314-pytest-9.1.1.pyc`。
`.gitignore:35` 已有规则 `backend-python/**/__pycache__/`，但该文件**已被 tracked**，规则对它失效。

**要求**：

1. `git rm --cached backend-python/tests/contract/__pycache__/test_openapi_contract.cpython-314-pytest-9.1.1.pyc`（保留工作区文件）
2. 确认 `git check-ignore -v` 对该路径生效，后续 `git status` 不再出现 `__pycache__`
3. 检查是否还有其他误入库的 `*.pyc` / `target/` / `node_modules/` 产物

---

## 3. 门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线（本轮起） |
|---|---|
| `mvn -o test` | ≥ **1233** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | **> 275**（R4 要求新增前端渲染用例） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** passed |
| `pytest tests/` | **43 passed, 1 skipped** |

## 4. 红线（违反即整批打回）

1. **严禁 mock 被测主路径上的 Service**（本轮新增，针对 R3）——要测 Service/Repository 逻辑就必须真跑
2. **严禁 stub 被测主路径函数**（不得让搜索直接返回空列表/固定值冒充命中）
3. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值 / 改断言期望值
4. **严禁在 Flyway 迁移里使用 `CONCURRENTLY`**（本轮新增，针对 R1）
5. **严禁手写 JSON 字符串拼装**作为前后端协议（本轮新增，针对 R4）——用 `ObjectMapper` / 结构化类型
6. **严禁**提交 `.env` 或密钥明文；**严禁**提交 `__pycache__` / `target/` / `node_modules/` 产物
7. **严禁**回滚已闭环提交（PHASE58 四项 P0、PHASE59 RabbitMQ/JWT、PHASE60 限流/定时工作流/移动端）；**严禁**回退 `b506f01` 中已正确的 T1 查询侧过滤改造
8. **每项修复必须给出实测输出**（迁移日志 / EXPLAIN / 集成测试输出 / 容器内 curl / 前端渲染证据），不接受"配置了应该就好了"

## 5. 本项目教训（血泪，务必遵守）

1. **Flyway 迁移禁止 `CONCURRENTLY`** —— 事务内必然失败，且不跑迁移就发现不了
2. **禁止把被测 Service 本身 mock 掉再断言返回值** —— 那是测试假绿：只验证了参数透传，核心逻辑没跑。修复在 Service/Repository 层的用例必须 `@DataJpaTest` / `@SpringBootTest` 真跑
3. **手写 JSON 拼装 = 前后端协议漂移** —— 后端拼 `["uuid"]`，前端按 `[{displayName,userId}]` 用，静默失效且无报错。用结构化类型 + Jackson
4. **注释不得声称实现没有的能力** —— L62 注释说用了 tsvector，实现是 LIKE，属误导性缺陷
5. **容器内 `localhost` 指向容器自身** —— 服务间地址必须 `@Value` 可配 + compose 服务名（已踩 5 次）
6. **单测通过 ≠ 端到端生效** —— PHASE60 的 T3-1 典型（限流单测绿，过滤器没进链，实测无 429）
7. **Servlet 过滤器**必须在 `SecurityConfig` 用 `addFilterBefore/After` 注册（本项目无 `@Order` 先例）
8. **Redis ZSET 滑动窗口 member 必须唯一**（时间戳 + `nanotime`）
9. **`tsvector` 改动必须有 H2 降级**，否则打破既有用例
10. **分页语义**：先过滤再分页，永远不要"先取一页再过滤"

## 6. 回报时必须给出（缺项直接打回）

1. 每项实测输出：
   - R1：Flyway 迁移成功日志 + 索引存在的 `psql` 输出
   - R2：`EXPLAIN` 证据（方案 A）或"已删 V43 + 修正注释"的 diff（方案 B）
   - R3：集成测试实际输出 + "去掉 mock 后旧实现是否会挂"的说明
   - R4：vitest 数字（须 > 275）+ 端到端证据（接口 `mentions` JSON + 前端渲染）
   - R5：`git rm --cached` 记录 + `git status` 干净
2. 五项门禁的**实际输出数字**
3. 改动文件清单 + `git log --oneline`
4. **明确说明哪些项未做及原因**（不得"做了不说"）

-----END PROMPT-----

## 投喂方式

1. 复制上面 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后我按审计口径复验：复跑门禁 + 检查是否真跑 SQL（无 mock 被测 Service）+ 容器内实测迁移/搜索/@提及

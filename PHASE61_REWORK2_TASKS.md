# PHASE61 第二轮返工任务需求单（提交 `b506f01` 审计仍不通过）

> 交付方式：CodeBuddy 出任务书与提示词 → Kilo Code + GLM-5.3 执行 → CodeBuddy 按审计口径复验。
>
> 第一轮返工提交 `b506f01`（"PHASE61 Rework"）经审计**仍不通过**。本轮只做下列五项，`b506f01` 中已正确的部分（T1 的查询侧过滤改造）**必须保留，不得回退**。

---

## 一、第二轮审计结论

### 实测门禁（CodeBuddy 亲跑）

| 门禁 | 实测 | 判定 |
|---|---|---|
| `mvn -o test` | **1233 / 0 / 0 / 0 + BUILD SUCCESS** | ✅（确为 +8） |
| `npm run test:run` | **275 passed（37 files）** | ⚠️ **= 基线，前端零新增** |
| `npx tsc --noEmit` | **0** | ✅ |
| 是否含 `.env` | 否 | ✅ |

### 逐项判定

| 项 | 判定 | 证据 |
|---|---|---|
| T1 代码修复 | ✅ **真修好，保留** | `ImMessageRepository#searchWithFullFilters` 把 keyword/mentions/时间全放在 JPQL 查询侧；`countWithFullFilters` 独立 count → 先过滤后分页、`total` 真实 |
| T1 测试 | ❌ **全 mock，零防线** | 见 §2 R3 |
| T2 索引 | ❌ **双重问题** | 见 §2 R1 / R2 |
| T3 @提及渲染 | ❌ **协议不一致，高亮不生效** | 见 §2 R4 |
| 仓库污染 | ❌ `.pyc` 入库 | 见 §2 R5 |

---

## 二、返工任务（R1–R5）

### R1（P0，阻塞启动）V43 迁移在 Flyway 事务内必然失败

**现状**：`backend-java/src/main/resources/db/migration/V43__im_message_search_index.sql`

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_im_message_content_tsv
ON im_message USING GIN (content_tsv);
```

**实证（CodeBuddy 直接在 PG 上验证）**：

```
psql -c "BEGIN; CREATE INDEX CONCURRENTLY ...; COMMIT;"
→ ERROR:  CREATE INDEX CONCURRENTLY cannot run inside a transaction block
```

Flyway 默认把每个迁移包在事务中执行 → **该迁移一旦被应用，应用启动直接失败**。
当前生产未暴露，仅因容器镜像仍是旧的、尚未应用 V43。

**要求**：

1. **删除 `CONCURRENTLY` 关键字**（改为普通 `CREATE INDEX IF NOT EXISTS`）。
2. 不要试图用"Flyway 非事务迁移"绕过——本项目用 Flyway 社区版，保持事务内执行最简单可靠；`im_message` 建索引代价可接受。
3. **必须实测迁移可成功应用**：重启应用（或容器内）后确认 Flyway 迁移成功、`im_message` 上 `content_tsv` 列与 `idx_im_message_content_tsv` 索引存在、应用正常启动。

**验收**：贴出 Flyway 迁移成功日志 + `psql` 查索引存在的输出（`\d im_message` 或 `pg_indexes` 查询）。

---

### R2（P1）`content_tsv` / GIN 是死索引，无人使用

**现状**：`backend-java/src/main/java/com/nocobase/im/ImMessageRepository.java`

- L62 注释写着「使用 tsvector 全文索引（PostgreSQL）或 ILIKE 降级（H2）」——**与实现不符**
- L74、L93（查询与 count）实际仍是：
  ```java
  "and (:keyword is null or :keyword = '' or m.content is not null and lower(m.content) like lower(concat('%', :keyword, '%'))) "
  ```

即：**建的 tsvector + GIN 索引没有任何查询用到**；`LIKE '%kw%'` 恒为 `Seq Scan`，永远走不了 GIN。

**要求**（二选一，**必须明确选择并说明理由**）：

- **方案 A（推荐）：真正接上 tsvector**
  1. 新增 native query 方法：`content_tsv @@ plainto_tsquery('simple', :keyword)`，按 `ts_rank` 排序；
  2. **按数据库方言切换**：PostgreSQL 走 tsvector，H2 走 `ILIKE` 降级并**打日志**（沿用 Wiki FTS 的既有降级惯例，不得打破既有 1233 个用例）；
  3. 成员/租户过滤必须保留，防越权；
  4. **给出 PostgreSQL 上的 `EXPLAIN` 实证**（`Bitmap Index Scan` / `Index Scan`，非 `Seq Scan`）。
- **方案 B：放弃索引化**
  1. 删除 `V43__im_message_search_index.sql`（不要留死索引白占写入开销与维护成本）；
  2. 修正 `ImMessageRepository` L62 的误导注释，如实写明"当前为 `LIKE` 全表扫"。

> **注意**：无论选哪个，都**不得**让注释声称用了 tsvector 而实现是 LIKE。注释与实现不符本身就是缺陷。

---

### R3（P0）T1 的测试全部 mock 掉被测对象，等于没测

**现状**：`backend-java/src/test/java/com/nocobase/im/ImMessageControllerTest.java`

```java
50:  messageSearchService = mock(MessageSearchService.class);
292: when(messageSearchService.searchMessages(eq(channelId), eq("kw"), ...)).thenReturn(...);
306: when(messageSearchService.searchMessages(eq(null), eq("budget"), ...)).thenReturn(...);
320: when(messageSearchService.searchMessages(eq(channelId), any(), eq(mentionedBy), ...)).thenReturn(...);
333: when(messageSearchService.searchMessages(eq(channelId), any(), any(), any(), eq(start), eq(end), ...)).thenReturn(...);
394: void advancedSearch_crossPageRecall()   ← 本应是 T1 bug 的回归防线
```

问题：把**被测的 `MessageSearchService` 本身 mock 掉**，再断言桩返回值 → 只验证了 Controller 的参数透传，**Service/Repository 的真实过滤逻辑一行没跑**。
结果：`advancedSearch_crossPageRecall`、`advancedSearch_totalCorrectness`（断言 `total=47`）这类用例**对 T1 的修复毫无约束力**——旧的错误实现同样能让它们通过。

**要求**：

1. **禁止** mock `MessageSearchService`（以及被测主路径上的任何 Service）。
2. 新增**真实跑 SQL 的集成测试**（`@DataJpaTest` 或 `@SpringBootTest` + 真实 DataSource），至少覆盖：
   - **跨页召回**：造 ≥ `size+1` 条消息，让唯一匹配项落在第 2 页 → `page=0&size=10` **必须命中**且 `total=1`（此用例用旧实现必挂，是防线）
   - `total` 正确性（匹配总数，非本页条数）
   - keyword 命中 / 无匹配 / 时间范围 / 提及过滤
   - 越权频道 → 空
   - 参数非法 → 400
3. 原 `ImMessageControllerTest` 里保留**只验证参数透传**的用例可以留，但**不得**用它充当 T1 的回归防线。

**验收**：贴出集成测试的**实际输出**；并说明"把 mock 去掉后，这些用例是否能在旧实现下失败"（能失败才算有效防线）。

---

### R4（P0）@提及前后端协议不一致 → 高亮永不生效

**现状（两端对不上）**：

- 后端 `MessageService.java` L80-82 **手写 JSON 拼装**，只存 userId：
  ```java
  RichTextParser.ParseResult parsed = RichTextParser.parse(body);
  String mentionsJson = "[" + parsed.mentions().stream()
          .map(mention -> "\"" + mention.userId() + "\"")     // ← 只有 userId
          .collect(Collectors.joining(",")) + "]";
  m.setMentions(mentionsJson);
  ```
- 后端 `ImMessageDto.java` L20：`String mentions`（JSON 字符串）
- 前端 `frontend/src/features/im/api.ts:67`：
  ```ts
  mentions?: Array<{ displayName: string; userId: string }>;   // ← 期望对象数组
  ```
- 前端 `MessageList.tsx` L63-66 起：`renderContent(content, isBurned, mentions)`，遍历 `mentions` 取 `mention.displayName` / `mention.userId` 拼正则 `@\{displayName\}:userId`；L315 调用处**确实传入了** `msg.mentions` ✅（接线没问题）

**后果**：后端返回的是字符串 `["uuid1","uuid2"]`，前端按对象数组用 → `mentions.length` 是字符数（>0 成立），`for...of` 遍历字符串得到**单个字符**，`mention.displayName` = `undefined` → 正则变成 `@\{undefined\}:undefined` → **匹配不到，高亮永不生效**（且不报错，静默失效）。

**要求**：

1. **统一协议（推荐后端改结构化）**：
   - 后端 DTO 的 `mentions` 改为结构化类型（如 `List<ImMentionDto>`，`record ImMentionDto(String displayName, String userId)`），由 Jackson 序列化；
   - **禁止手写 JSON 字符串拼装**（改用 `ObjectMapper` 或结构化类型），避免转义与类型漂移；
   - 落库的 `im_message.mentions`（TEXT）仍存 JSON，需包含 `displayName` 与 `userId`；注意 `ImMessageRepository` L75 的 `m.mentions like '%:uuid%'` 过滤仍要能命中（JSON 里含 userId 即可）。
   - 若选择保持 `String` 字段，则前端必须做 `JSON.parse` 且两端结构必须一致——**二选一，但必须两端对齐**。
2. **补前端渲染测试**（当前前端测试 275 = 基线，**零新增**）：`MessageList` 渲染用例，覆盖「提及渲染为 chip」+「普通文本不受影响」+「无 mentions 时不报错」。
3. 通知链路保持复用 `NotificationService#createMentionNotification`（已接，L98-103）。

**验收**：
- 前端测试用例实际输出（vitest 数字必须 > 275）
- **端到端实测**：容器内发一条带 `@{displayName}:userId` 的消息 → 被 @ 的人收到通知 → 前端消息列表渲染出高亮 chip（贴接口返回的 `mentions` JSON 与前端渲染证据）

---

### R5（P1）清理误入库的编译产物

**现状**：提交 `b506f01` 含
```
backend-python/tests/contract/__pycache__/test_openapi_contract.cpython-314-pytest-9.1.1.pyc
```
`.gitignore:35` 已有规则 `backend-python/**/__pycache__/`，但该文件**已被 tracked**，规则对它失效。

**要求**：

1. `git rm --cached backend-python/tests/contract/__pycache__/test_openapi_contract.cpython-314-pytest-9.1.1.pyc`（保留工作区文件）；
2. 确认 `git check-ignore -v` 对该路径生效，且后续 `git status` 不再出现 `__pycache__`；
3. 检查是否还有其他误入库的 `*.pyc` / `target/` / `node_modules/` 产物。

---

## 三、门禁基线（须 ≥ 且 0 失败，贴实际输出）

| 门禁 | 基线（本轮起） |
|---|---|
| `mvn -o test` | **≥ 1233** / 0 failures / 0 errors，BUILD SUCCESS |
| `npm run test:run` | **> 275**（R4 要求新增前端渲染用例，故必须大于） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | **≥ 64** passed |
| `pytest tests/` | **43 passed, 1 skipped** |

---

## 四、红线（违反即整批打回）

1. **严禁 mock 被测主路径上的 Service**（本轮新增，针对 R3）——要测 Service/Repository 逻辑就必须真跑。
2. **严禁 stub 被测主路径函数**（不得让搜索直接返回空列表/固定值冒充命中）。
3. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值 / 改断言期望值。
4. **严禁在 Flyway 迁移里使用 `CONCURRENTLY`**（本轮新增，针对 R1）。
5. **严禁手写 JSON 字符串拼装**作为前后端协议（本轮新增，针对 R4）——用 `ObjectMapper` / 结构化类型。
6. **严禁**提交 `.env` 或任何密钥明文；**严禁**提交 `__pycache__` / `target/` / `node_modules/` 等产物。
7. **严禁**回滚已闭环提交（PHASE58 四项 P0、PHASE59 RabbitMQ/JWT、PHASE60 限流/定时工作流/移动端）；**严禁**回退 `b506f01` 中已正确的 T1 查询侧过滤改造。
8. **每项修复必须给出实测输出**（迁移日志 / EXPLAIN / 集成测试输出 / 容器内 curl / 前端渲染证据），不接受"配置了应该就好了"。

---

## 五、本项目教训（血泪，务必遵守）

1. **Flyway 迁移禁止 `CONCURRENTLY`** —— 事务内必然失败，且不跑迁移就发现不了。
2. **禁止把被测 Service 本身 mock 掉再断言返回值** —— 那是测试假绿：只验证了参数透传，核心逻辑没跑。修复在 Service/Repository 层的用例必须 `@DataJpaTest` / `@SpringBootTest` 真跑。
3. **手写 JSON 拼装 = 前后端协议漂移** —— 后端拼 `["uuid"]`，前端按 `[{displayName,userId}]` 用，静默失效且无报错。用结构化类型 + Jackson。
4. **注释不得声称实现没有的能力** —— L62 注释说用了 tsvector，实现是 LIKE，属误导性缺陷。
5. **容器内 `localhost` 指向容器自身** —— 服务间地址必须 `@Value` 可配 + compose 服务名（已踩 5 次）。
6. **单测通过 ≠ 端到端生效** —— PHASE60 的 T3-1 典型（限流单测绿，过滤器没进链，实测无 429）。
7. **Servlet 过滤器**必须在 `SecurityConfig` 用 `addFilterBefore/After` 注册（本项目无 `@Order` 先例）。
8. **Redis ZSET 滑动窗口 member 必须唯一**（时间戳 + `nanotime`）。
9. **`tsvector` 改动必须有 H2 降级**，否则打破既有用例。
10. **分页语义**：先过滤再分页，永远不要"先取一页再过滤"。

---

## 六、交付清单（回报时必须给出，缺项直接打回）

1. 每项的**实测输出**：
   - R1：Flyway 迁移成功日志 + 索引存在的 `psql` 输出
   - R2：`EXPLAIN` 证据（选方案 A）或"已删 V43 + 修正注释"的 diff（选方案 B）
   - R3：集成测试实际输出 + 说明"去掉 mock 后旧实现是否会挂"
   - R4：vitest 数字（须 > 275）+ 端到端证据（接口返回 `mentions` JSON + 前端渲染）
   - R5：`git rm --cached` 记录 + `git status` 干净
2. 五项门禁的**实际输出数字**
3. 改动文件清单 + `git log --oneline`
4. **明确说明哪些项未做及原因**（不得"做了不说"）

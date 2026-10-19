# PHASE70 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE70：协同编辑接真（CRDT 字符级）+ 压测与容量基线

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19 + Yjs + STOMP
- 你的工作目录即仓库根目录

### 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test`（**T1 的双客户端实测就用它写**） |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`（**必须加 `PYTHONPATH=src`**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**；不要用在线 Dockerfile，会超时） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**：改迁移后 `target/classes` 会残留孤儿文件打进 jar） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |

---

## §1 为什么做这两项（不是凭感觉选的）

选题来自仓库内 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md` §五的 P1 清单与推荐顺序：

```
P1-2 全局限流 → P1-3 定时工作流去内存态 → P1-8 压测覆盖 → P1-13 容量基线
 → P1-1 CRDT 字符级协同 → P1-6 IM 搜索 → P1-9 钉钉同步 → P1-5 移动端
```

**前置项已全部完成且真接真，本批不要动它们**（我已逐项核实）：

| 已完成 | 证据 |
|---|---|
| 全局限流（P1-2） | `SecurityConfig:33-35` 真注入 `GlobalRateLimitFilter`（**在过滤链里**）；`application.yml:178-181` `ratelimit.enabled: true`、`login.limit=5/300s` |
| 去内存态（P1-3） | `WorkflowScheduler:92-95` Redis `SETNX + TTL` 分布式锁；持久化 `last_triggered_at`（`WorkflowEntity:62`） |
| Cron（P1-4） | `WorkflowScheduler:68` 优先解析 cron |
| IM 搜索（P1-6） | 查询侧过滤 + 真跑的 `@DataJpaTest` |
| 入站消费者（P1-7） | `InboundMessageService` 落库+广播+幂等 |
| 钉钉同步（P1-9） | 真调 `oapi.dingtalk.com` |
| 集成市场 UI（P1-10） | `IntegrationsPage.tsx` + 真落库 |

**本批做推荐顺序里剩下的两项：P1-1（CRDT 字符级协同）+ P1-8/P1-13（压测与容量基线）。**

---

## §2 T1（P0）：协同编辑"用了 Yjs"但**不是真字符级协同**

### 2.1 当前实现（实测证据）

`frontend/src/features/realtime/CollabEditor.tsx`：

```ts
// L36-37 注释自述
* 一致性由 Yjs 在客户端保证,服务端只做增量转发(不解析文档内容)。
* 本地编辑以「整篇替换」写入 Y.Text 产生 update 并广播;收到远端 update 后 ...

// L142-147 实际实现
/** 本地编辑 → 整篇写入 Y.Text(产生 update 并广播)。 */
private ... {
    ytext.delete(0, ytext.length);      // ← 先删光
    ytext.insert(0, value);             // ← 再整篇插入
}
```

这是**整篇替换**，不是字符级 diff。Yjs 保证"最终一致"，但语义上是
**最后写入者赢**：

- A、B 同时编辑不同段落 → A 的整篇替换**抹掉 B 刚写的内容**
- 远端 update 应用后本地光标失效 → **光标跳变/内容跳动**

用户视角：**两人同时编辑一篇 Wiki，一方的编辑会消失。**

### 2.2 配套缺陷：状态同步依赖未配置的服务

```
application.yml 中 grep "crdt.service.url" → 无输出（未配置）
```

`backend-java/src/main/java/com/nocobase/realtime/RealtimeService.java`：

```java
// L48-53
public RealtimeService(..., @Value("${crdt.service.url:}") String crdtServiceUrl) {
    if (crdtServiceUrl == null || crdtServiceUrl.isBlank()) {
        log.warn("[Realtime] CRDT 服务未配置 (crdt.service.url 为空),启用增量透传降级模式");
        this.crdtWebClient = null;
```

后果链：

- `:176 getDocumentState(docId)` 调 `/docs/{docId}/state` → **永不可用**
- 新用户加入 / 断线重连只能走 `CollabEditor.tsx:60-61`：
  `if (ytext.length === 0 && initialContent) ytext.insert(0, initialContent)`
  → 拿到的是**进入前的旧快照**，不含他人此后的增量
- → **后加入者基于过期内容编辑，再整篇替换广播 → 把别人的修改覆盖回去**

**"有 Yjs + 有 STOMP 转发"≠ 真协同**：缺字符级 diff、缺 awareness、
缺可靠状态同步，任一缺失用户都会体验到"编辑丢失"。

### 2.3 要求

#### 1）字符级 diff（核心）

改掉 `CollabEditor.tsx:142-147`：

- 从编辑器拿到**变更增量**（delta / diff），只把变化部分写进 `Y.Text`
  （如 `ytext.applyDelta(delta)`，或按 diff 做 `delete(pos, len)` + `insert(pos, str)`）
- 禁止 `delete(0, length) + insert(0, value)` 作为常规编辑路径
  （仅在 `ytext.length === 0` 的初始化时允许）
- 无现成 delta 时可引入轻量 diff（如 `diff-match-patch` 或公共前后缀算法），
  **但必须是字符/片段级增量**

#### 2）awareness（在线状态与光标）

- 广播本地光标位置/选区（`Y.Awareness` 或自定义 presence 消息）
- 渲染远端协作者光标（至少显示"谁在编辑"）

#### 3）新加入 / 重连的状态同步（不依赖外部 CRDT 服务）

二选一：

- **方案 A（推荐）**：服务端在 Redis（或内存）保存每个 docId 的最新 Yjs 状态快照
  （`Y.encodeStateAsUpdate` 的 Base64），`join` 时下发
- **方案 B**：`join` 时由已在房内的 peer 回复一份完整 state（服务端协调）

**禁止**：继续让后加入者只用 `initialContent` 凭空初始化。

> `crdt.service.url` 保留为可选增强。**未配置时必须仍能正确协同**，且降级要显式告警
> （现已有 WARN ✅，但必须有上面这套兜底）。

#### 4）端到端实测（红线：必须贴输出）

用两个真实客户端（推荐 Playwright 双 context，或两个 STOMP 脚本客户端）：

| 场景 | 期望 |
|---|---|
| A、B 同时编辑不同段落 | 两端最终内容**都含 A 和 B 的修改** |
| B 后加入（A 已编辑过） | B 看到的是**A 编辑后的最新内容**，不是初始快照 |
| B 断线重连后继续编辑 | 不覆盖 A 在断线期间的修改 |
| 快速连续编辑（10 次/秒） | 收敛一致，无内容丢失 |

---

## §3 T2（P1）：压测覆盖扩展 + 容量基线

### 3.1 现状

`perf/load-test.js`：

```
L55: const collections = http.get(`${BASE}/api/collections?limit=10`, params);   ← 只有这一个
L26: http_req_duration: ['p(95)<500', 'p(99)<3000']
L33-36: 阶梯 20 → 50 → 100 VUS
```

只读接口的数据**不能代表系统容量** —— 写入路径有事务、锁、广播，拐点完全不同。
`BASELINE.md` 中无 QPS / P99 / 并发拐点数据。

### 3.2 要求

1. 扩展 k6 场景（阶梯沿用）：
   - **写入**：`POST /api/collections`（建集合/建记录）
   - **IM**：`POST /api/im/messages`（发消息，含广播路径）
   - **Wiki**：`POST /api/wiki/blocks/batch-upsert`
   - **工作流**：触发一次工作流
2. 跑出并记录容量基线（写进 `BASELINE.md`）：
   - 各场景 **QPS 上限**、**P95/P99**、**并发拐点**（错误率 >1% 或 P99 陡增的点）
   - 注明"单机/单副本"前提
3. 若 k6 未安装 → 用容器内可运行的方式，或给等价脚本 + 说明，**不得跳过实测**

---

## §4 范围边界（明确不做）

- 不动已完成项：限流、定时工作流、cron、IM 搜索、入站、钉钉同步、集成市场
- 不做移动端响应式（P1-5，15–20 人日，规模过大）
- 不做字段类型扩展（P1-11）、不做视图 group by / 日历翻月（P1-12）
- 不引入新的外部 CRDT 服务依赖（除非你明确论证并配置好）

---

## §5 门禁基线（必须全部满足并贴实测输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1312**（基线 1312） |
| `cd frontend && npm run test:run` | **> 365**（基线 365） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ 64（T1 双客户端实测建议直接用 Playwright 写，计入此门禁） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §6 红线

既有红线继续有效：禁 stub 主路径 / 禁 skip 或弱化断言 / 禁 `.env` 入库 /
禁回滚已闭环提交 / 每项须实测 / 禁 mock 被测主路径 /
禁 Flyway 迁移用 `CONCURRENTLY` / 禁手写 JSON 协议 / 禁 fail-open /
严禁"只打日志"冒充完成 / 严禁修改已应用迁移 / 严禁禁用校验绕过问题 /
严禁把调用方传入的 key 直接送进存储层。

**本轮新增两条**：

1. 🚫 **严禁"整篇替换"冒充字符级协同**。判据：编辑路径出现
   `delete(0, length) + insert(0, value)` 即判未实现（初始化一次性写入除外）。
   验收硬标准：**并发编辑不同段落，双方修改都保留**。
2. 🚫 **严禁依赖未配置的服务作为唯一正确路径**。`crdt.service.url` 未配置时
   系统必须仍能正确协同（有兜底的状态同步），且降级要显式告警 ——
   不能"配了才对、不配就静默错"。

---

## §7 本项目教训（择要）

1. **"用了库"≠"功能达成"**：引入 Yjs 不等于真协同 —— 整篇替换下 CRDT 只保证
   "一致"，不保证"不丢编辑"。判据是**用户可感知的并发体验**，不是库名。
2. **静默降级是隐形缺陷**：`crdt.service.url` 为空只打 WARN 继续跑，结果是
   "看着能用、实际会丢内容"。凡降级必须有**等价兜底**。
3. **只读压测不代表容量**：写入路径（事务/锁/广播）拐点与只读完全不同。
4. **实现达标 ≠ 交付达标**：PHASE61/62/69 连续出现"实现真做了，但零新增测试 +
   不提交 + 不实测"。本批 T1 的端到端双客户端实测是硬要求。
5. **改根因，不改校验**（PHASE69）：遇到问题不要靠放宽验收标准通过。
6. **判断"实时协同是否真完成"三步**：① 编辑是增量而非整篇替换
   ② 后加入/重连能拿到最新状态 ③ 双客户端并发实测双方修改都保留 —— 缺一不可。

---

## §8 回报清单（必须包含，缺项会被打回）

1. **T1 代码证据**：字符级 diff 实现片段（含行号）+ 说明旧整篇替换路径已废弃
2. **T1 端到端实测输出**：四个场景（并发不覆盖 / 后加入拿最新 / 重连不覆盖 /
   高频收敛）**逐条贴原始输出**
3. **T2 压测输出**：各场景 QPS、P95/P99、拐点；`BASELINE.md` 新增段落
4. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest 五项实测输出
5. **提交记录**：`git log --oneline` + `git status` 干净
6. **未做项说明**：哪些没做、为什么 —— 不许"做了不说"，也不许"没做装做"

-----END PROMPT-----

# PHASE70 任务书 — 协同编辑接真（CRDT 字符级）+ 压测与容量基线

> 选题依据：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md` §五 P1 清单与推荐顺序
> （L410：`P1-2 → P1-3 → P1-8 → P1-13 → P1-1 → P1-6 → P1-9 → P1-5`）
> 前置核实：P1-2 全局限流、P1-3 去内存态、P1-4 cron、P1-6 IM 搜索、P1-7 入站、
> P1-9 钉钉同步、P1-10 集成市场 UI **均已完成且真接真**，本批不动。
> 本批取推荐顺序中**尚未完成的两项**：**P1-1（CRDT 真字符级协同）** + **P1-8/P1-13（压测与容量基线）**

---

## §0 现状审计（CodeBuddy 实测，含行号证据）

### 0.1 已接真、本批不动的（先确认，避免误改）

| 项 | 证据 |
|---|---|
| 全局限流（P1-2） | `SecurityConfig:33-35` 真注入 `GlobalRateLimitFilter`（**进过滤链**，不是"定义未注册"）；`application.yml:178-181` `ratelimit.enabled: true`、`login.limit=5/300s`；默认 `30/60s`；Redis 分布式 + Memory 降级 |
| 定时工作流去内存态（P1-3） | `WorkflowScheduler:92-95` Redis `SETNX + TTL` 分布式锁（`:41` LOCK_PREFIX）；持久化 `last_triggered_at`（`WorkflowEntity:62`，V41 建列）→ 重启不重置 |
| Cron 表达式（P1-4） | `WorkflowScheduler:68` 优先解析 cron，其次 `intervalMinutes` |
| IM 搜索（P1-6） | 查询侧过滤 + `countWithFullFilters`，有真跑的 `@DataJpaTest` 集成测试 |
| 入站消费者（P1-7） | `InboundMessageService` 落库 + 广播 + 幂等 + 成员校验；Mattermost fail-close |
| 钉钉同步（P1-9） | `DingTalkOrgSyncService` 真调 `oapi.dingtalk.com` |
| 集成市场 UI（P1-10） | `IntegrationsPage.tsx` + 真落库 |

### 0.2 ❌ T1：协同编辑"用了 Yjs"但**不是真字符级协同**

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

**问题**：`ytext.delete(0, length) + insert(0, value)` 是**整篇替换**，
不是字符级 diff。Yjs 虽能保证最终一致（CRDT 数学性质），但语义上是
"**最后写入者赢**" —— A 和 B 同时编辑不同段落时：

- A 的整篇替换会**抹掉 B 刚写入的内容**（不是合并，是覆盖）
- 远端 update 应用后，本地光标位置失效 → **光标跳变/内容跳动**

用户视角：两人同时编辑一篇 Wiki，**一方的编辑会消失**。这正是评估文档
P1-1 描述的"整篇替换"问题，**依然存在**。

### 0.3 ❌ T1 配套：状态同步依赖未配置的服务

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

- `:176 getDocumentState(docId)` 调用 `/docs/{docId}/state` → **永不可用**（client 为 null）
- 新用户加入 / 断线重连时，客户端只能走 `CollabEditor.tsx:60-61`
  `if (ytext.length === 0 && initialContent) ytext.insert(0, initialContent)`
  → 拿到的是**进入前的快照**，不包含他人此后的增量
- → **重连或后加入的人会基于过期内容编辑**，再整篇替换广播 → 把别人的修改覆盖回去

**"有 Yjs + 有 STOMP 转发"≠ 真协同**：缺字符级 diff、缺 awareness、
缺可靠的状态同步，三者任一缺失都会让用户实际体验到"编辑丢失"。

### 0.4 ❌ T2：压测只覆盖 1 个只读接口

`perf/load-test.js` 实测：

```
L55: const collections = http.get(`${BASE}/api/collections?limit=10`, params);   ← 只此一个
L26: http_req_duration: ['p(95)<500', 'p(99)<3000']                             ← 阈值
L33-36: 阶梯 20 → 50 → 100 VUS
```

**缺失**（评估 P1-8）：无写入接口、无 IM 发消息、无 Wiki 编辑、无工作流触发。
只读接口的性能数据**不能代表系统容量** —— 写入路径有事务、锁、广播，拐点完全不同。

`BASELINE.md` 中无 QPS / P99 / 并发拐点数据（P1-13 缺）。

---

## §1 任务范围

### T1（P0）CRDT 真字符级协同

#### 1.1 字符级 diff（核心）

改掉 `CollabEditor.tsx:142-147` 的整篇替换：

- 从编辑器（Quill/CodeMirror/textarea 按现状）拿到**变更增量**（delta / diff），
  只把变化的部分写进 `Y.Text`（如 `ytext.applyDelta(delta)`，
  或按 diff 结果做 `delete(pos, len)` + `insert(pos, str)`）
- 禁止 `delete(0, length) + insert(0, value)` 作为常规编辑路径
- 若无现成 delta，可引入轻量 diff（如 `diff-match-patch` 或自实现的公共前后缀算法），
  **但必须是字符/片段级增量**

验收（必须实测）：A、B 两个客户端**同时编辑同一篇文档的不同段落**，
最终两端内容**都包含 A 与 B 的修改**（不得互相覆盖）。

#### 1.2 awareness（在线状态与光标）

- 广播本地光标位置/选区（`Y.Awareness` 或自定义的 presence 消息）
- 渲染远端协作者光标（至少显示"谁在编辑"）
- 目的：让用户看到彼此位置，避免"以为对方没在改"导致的覆盖

#### 1.3 新加入 / 重连的状态同步（不依赖外部 CRDT 服务）

现状：`getDocumentState`（`RealtimeService:176`）依赖未配置的 `crdt.service.url`。
要求**在服务端可用的前提下**保证后加入者拿到最新内容，二选一：

- **方案 A（推荐）**：服务端在内存/Redis 保存每个 docId 的最新 Yjs 状态快照
  （`Y.encodeStateAsUpdate` 的 Base64），`join` 时下发；或
- **方案 B**：`join` 时由已在房内的 peer 回复一份完整 state（服务端协调）

禁止：继续让后加入者只用 `initialContent` 凭空初始化（会覆盖他人改动）。

> 注：`crdt.service.url` 保留为可选增强；**未配置时必须仍能正确协同**，
> 且降级要在日志里明确（现已有 WARN ✅，但必须有上面这套兜底）。

#### 1.4 端到端实测（红线：必须贴输出）

用两个真实客户端（推荐 Playwright 双 context，或两个 STOMP 脚本客户端）：

| 场景 | 期望 |
|---|---|
| A、B 同时编辑不同段落 | 两端最终内容**都含 A 和 B 的修改** |
| B 后加入（A 已编辑过） | B 看到的是**A 编辑后的最新内容**，不是初始快照 |
| B 断线重连后继续编辑 | 不覆盖 A 在断线期间的修改 |
| 快速连续编辑（10 次/秒） | 收敛一致，无内容丢失 |

### T2（P1）压测覆盖扩展 + 容量基线

1. `perf/load-test.js` 扩展场景（k6 阶梯 20/50/100 沿用）：
   - **写入**：`POST /api/collections`（建集合 / 建记录）
   - **IM**：`POST /api/im/messages`（发消息，含广播路径）
   - **Wiki**：块编辑保存 `POST /api/wiki/blocks/batch-upsert`
   - **工作流**：触发一次工作流
2. 跑出并记录容量基线（写进 `BASELINE.md`）：
   - 各场景 **QPS 上限**、**P95/P99**、**并发拐点**（错误率 >1% 或 P99 陡增的点）
   - 明确"单机/单副本"前提
3. 若 k6 未安装 → 用容器内可运行的方式，或给出等价脚本 + 说明（**不得跳过实测**）

---

## §2 范围边界（明确不做）

- 不动已完成项：限流、定时工作流、cron、IM 搜索、入站、钉钉同步、集成市场
- 不做移动端响应式（P1-5，15–20 人日，规模过大）
- 不做字段类型扩展（P1-11）
- 不做视图 group by / 日历翻月（P1-12）
- 不引入新的外部 CRDT 服务依赖（除非你明确论证并配置好）

---

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1312**（基线 1312） |
| `cd frontend && npm run test:run` | **> 365**（基线 365） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ 64（T1 的双客户端实测建议直接用 Playwright 写，计入此门禁） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §4 红线（沿用既有 + 本轮新增）

既有红线继续有效（禁 stub 主路径、禁 skip/弱化断言、禁 `.env` 入库、
禁回滚已闭环提交、每项须实测、禁 mock 被测主路径、禁 Flyway 迁移用
`CONCURRENTLY`、禁手写 JSON 协议、禁 fail-open、严禁"只打日志"冒充完成、
严禁修改已应用迁移、严禁禁用校验绕过问题、严禁把调用方传入的 key 直接送进存储层）。

**本轮新增两条**：

1. 🚫 **严禁"整篇替换"冒充字符级协同**。判据：编辑路径里出现
   `delete(0, length) + insert(0, value)` 即判定未实现（初始化一次性写入除外，
   且必须在长度为空时）。验收硬标准：**并发编辑不同段落，双方修改都保留**。
2. 🚫 **严禁依赖未配置的服务作为唯一正确路径**。`crdt.service.url` 未配置时
   系统必须仍能正确协同（有兜底的状态同步），且降级要显式告警 —— 不能"配了才对、
   不配就静默错"。

---

## §5 本项目教训（择要）

1. **"用了库"≠"功能达成"**：引入 Yjs 不等于真协同 —— 整篇替换下 CRDT 只保证
   "一致"，不保证"不丢编辑"。判据是**用户可感知的并发体验**，不是库名。
2. **静默降级是隐形缺陷**：`crdt.service.url` 为空只打 WARN 继续跑，
   结果是"看着能用、实际会丢内容"。凡降级必须有**等价兜底**，否则等于功能不存在。
3. **只读压测不代表容量**：写入路径（事务/锁/广播）拐点与只读完全不同。
4. **实现达标 ≠ 交付达标**：PHASE61/62/69 连续出现"实现真做了，但零新增测试 +
   不提交 + 不实测"。本批 T1 的端到端双客户端实测是硬要求。
5. **改根因，不改校验**（PHASE69）：遇到问题不要靠放宽验收标准通过。

---

## §6 交付清单（回报必须包含）

1. **T1 代码证据**：字符级 diff 的实现片段（含行号）+ 说明旧整篇替换路径已废弃
2. **T1 端到端实测输出**（四个场景逐条贴）：并发不覆盖 / 后加入拿到最新 /
   重连不覆盖 / 高频收敛
3. **T2 压测输出**：各场景 QPS、P95/P99、拐点；`BASELINE.md` 新增段落
4. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest 五项实测输出
5. **提交记录**：`git log --oneline` + `git status` 干净
6. **未做项说明**：哪些没做、为什么

---

## §7 一句话总结

**Yjs 已经在了，但每次编辑都是"删光重写" —— 两个人一起改一篇文档，
后保存的人会把先保存的人的修改抹掉。** 把它做成真正的字符级增量协同，
并让后加入/重连的人拿到最新内容；顺带把压测从"只压一个只读接口"扩到
真实写入路径，给出可依据的容量数字。

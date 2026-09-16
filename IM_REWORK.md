# IM 返工指令(给 Cline)

> ## ✅ 状态:已完成(2026-09-16)
>
> Cline 第二轮返工后 `pnpm build` 仍失败(5 个 TypeScript 错误),故由 CodeBuddy
> 接手收尾。本文件六节问题已全部处理完毕并通过三项全量验证:
> **后端 891 / 891 PASS**、**前端 19 文件 / 159 测试 PASS**、`pnpm build` 成功。
>
> 详见 `CHANGELOG.md`「Week 43 — IM 收尾:构建修复 + 未读角标 + 告警接入总线」。
> 本文件保留作为问题记录与回归参考。

> 本文件由 CodeBuddy 编写,供 **Cline** 自查自改。
> 背景:你已完成 `COLLABORATION.md` 的任务 5(IM 前端)与任务 6(告警接入),
> 经审查**页面实际不可用**,需要返工。下面是精确清单,每项含
> **错误位置 → 错在哪 → 正确写法 → 如何验证**。

---

## 给 Cline 的话(可直接复制执行)

```
请阅读项目根目录的 IM_REWORK.md,按其中六节逐项返工你此前提交的 IM 前端与告警接入代码。

要求:
1. 每一节都要改到位,不要只改第一节就交。
2. 严格守住文末「保住项」8 条 —— 那是你已经做对的地方,返工中不得破坏,
   尤其是 SecurityConfig 只放行 /ws/im/**、软删除渲染成"该消息已删除"、不引入 UI 组件库。
3. 本次两个阻断缺陷都是"一跑就暴露"的类型,所以**必须实跑验证**:
   - cd frontend && pnpm build     (类型检查必须通过)
   - cd frontend && pnpm test:run  (新增测试必须通过)
   - 启动后端与前端,浏览器打开 /im 页面,确认能加载频道、收发消息、表情、翻页
   没实跑就提交,本次会再次返工。
4. 任务 6 的告警数据源本身是缺失的(见第四节陷阱说明),切换后没有数据属预期,
   **绝对不要为了看到数据而造假数据**。
```

---

## 第一节 · 两个阻断缺陷(必改,否则页面不可用)

### 1.1 所有 REST 请求 404:路径多了一层 `/api`

**错误位置**:`frontend/src/features/im/api.ts` —— 共 **18 处** `api/im` 字样
(典型:第 17、27、32、37、42 行等)

**错在哪**:

`api.ts` 用的是 `apiClient`,而它的 `baseURL` 已经是 `/api`:

```ts
// frontend/src/api/client.ts:9-11
const axiosInstance: AxiosInstance = axios.create({
  baseURL: '/api',
```

但你又写了一遍 `/api` 前缀:

```ts
// frontend/src/features/im/api.ts:17  ← 错误
return apiClient.get<{ code: number; data: ImChannel[] }>('/api/im/channels');
```

axios 会把两者拼起来 → 实际请求 **`/api/api/im/channels`**,
而后端映射是 `/api/im/channels`(`ImChannelController.java:24`),于是全部 404。

**正确写法**:去掉 `/api`,只保留 `/im/...`

```ts
// 正确
return apiClient.get<{ code: number; data: ImChannel[] }>('/im/channels');
return apiClient.post<{ code: number; data: ImChannel }>('/im/channels', channelData);
return apiClient.get(`/im/channels/${channelId}`);
```

**对照既有正确写法**(同项目其它页面):

```ts
// frontend/src/pages/MessagesInbox.tsx:41 —— 注意不带 /api
apiClient.get('/messages')
```

全项目只有 IM 模块把 `/api` 写进了 `apiClient` 路径,这是笔误。

**验证**:浏览器 DevTools → Network,进入 `/im` 页面,
确认请求 URL 是 `http://localhost:5173/api/im/channels`(单层 `/api`),且返回 200。

---

### 1.2 WebSocket 未连接就订阅 → 进入页面必崩

**错误位置**:
- `frontend/src/lib/stompClient.ts:37-38`(`activate()` 后立即 return)
- `frontend/src/lib/stompClient.ts:54`(`subscribeToChannel` 里直接 `client.subscribe`)
- `frontend/src/lib/stompClient.ts:23-25`(`onConnect` 是空的)
- `frontend/src/features/im/ImLayout.tsx:87`(调用点,无 try/catch)

**错在哪**:

`client.activate()` 是**异步**的,函数返回时连接尚未建立。
stompjs v7 的 `subscribe()` 内部会调 `_checkConnection()`,
未连接时直接 **抛 `TypeError: There is no underlying STOMP connection`**。
你的 `onConnect` 回调是空的,没有任何订阅逻辑;调用方 `ImLayout.tsx:87` 也没保护,
React 没有错误边界 → 首次进入 `/im` **白屏崩溃**,实时消息永远收不到。

**正确写法**:「`onConnect` 内订阅 + 待订阅队列」。
核心思路:把订阅请求登记到队列,连接就绪(含重连)时统一建立。

```ts
import { Client, type StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useAuthStore } from '@/stores/auth';

let stompClient: Client | null = null;

/** 待订阅队列:连接就绪/重连后统一建立 */
const pendingSubs: Array<{ destination: string; cb: (body: string) => void }> = [];

export function getStompClient(): Client {
  // 注意用 active 而不是 connected:activate() 后 active 立即为 true,
  // 而 connected 要等握手完成。用 connected 会导致并发调用时重复 new Client(连接泄漏)。
  if (stompClient && stompClient.active) return stompClient;

  const token = localStorage.getItem('nocobase_access_token');

  stompClient = new Client({
    webSocketFactory: () => new SockJS(`/ws/im?token=${token}`),
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    reconnectDelay: 5000,
    onConnect: () => {
      // 连接就绪(含断线重连)→ 补建所有待订阅
      for (const s of pendingSubs) {
        stompClient?.subscribe(s.destination, (m) => s.cb(m.body));
      }
    },
    onStompError: (frame) => {
      console.error('[STOMP] error:', frame.headers?.message, frame.body);
    },
    onWebSocketError: (event) => console.error('[STOMP] ws error:', event),
    onWebSocketClose: () => console.warn('[STOMP] closed, reconnecting...'),
  });

  stompClient.activate();
  return stompClient;
}

export function subscribeToChannel(
  channelId: string,
  onMessage: (payload: string) => void,
): () => void {
  const client = getStompClient();
  const tenantId = useAuthStore.getState().user?.tenant_id ?? 'tenant_default';
  const destination = `/topic/t-${tenantId}.channel.${channelId}`;

  // 已连接则立即订阅;未连接则等 onConnect 补建
  let sub: StompSubscription | null = null;
  if (client.connected) {
    sub = client.subscribe(destination, (m) => onMessage(m.body));
  }
  // 无论当前是否连接都登记,保证重连后自动恢复
  const entry = { destination, cb: onMessage };
  pendingSubs.push(entry);

  return () => {
    sub?.unsubscribe();
    const i = pendingSubs.indexOf(entry);
    if (i >= 0) pendingSubs.splice(i, 1);
  };
}
```

**验证**:
1. 首次进入 `/im` 页面**不再白屏**,控制台无 `TypeError: There is no underlying STOMP connection`。
2. 另开一个浏览器(或用另一账号)在同一频道发消息,当前页面能**实时收到**(无需刷新)。
3. 手动停掉后端再启动,前端自动重连(5s)后**仍能收到新消息**(验证重连重建订阅)。

---

## 第二节 · WebSocket 连接生命周期

### 2.1 `disconnectStomp()` 从未被调用 —— 必须接入登出流程

**错误位置**:
- 定义:`frontend/src/lib/stompClient.ts:80-85`
- 应有调用但缺失:`frontend/src/components/AppLayout.tsx:9-12`

**错在哪**:当前登出只清了 store,WS 连接仍在后台以**旧 token**重连:

```tsx
// frontend/src/components/AppLayout.tsx:9-12  ← 现状
const handleLogout = () => {
  clear();
  navigate('/login');
};
```

**正确写法**:

```tsx
import { disconnectStomp } from '@/lib/stompClient';

const handleLogout = () => {
  disconnectStomp();   // 先断开 WS,避免旧 token 连接残留
  clear();
  navigate('/login');
};
```

同时把 `disconnectStomp` 补健壮(未连接也要清引用、清待订阅队列):

```ts
export function disconnectStomp(): void {
  pendingSubs.length = 0;
  if (stompClient) {
    stompClient.deactivate();
    stompClient = null;
  }
}
```

**验证**:登出后 DevTools → Network → WS 面板,确认连接已关闭且不再重连;
换账号登录后收到的是新账号的消息。

### 2.2 其它生命周期要点(一并处理)

- **重连后重建订阅**:1.2 的 `pendingSubs` 方案已覆盖,stompjs 不会自动恢复订阅。
- **单例判定**:已改为 `active`(见 1.2),避免并发调用重复建连。
- **心跳**:当前 30s,文档建议 60s —— 可保持 30s,不是问题,**不必改**。

---

## 第三节 · 功能补齐

### 3.1 表情回应未接 UI(任务 5 明确要求)

**现状**:`api.ts` 已有 `getReactions` / `addReaction` / `removeReaction`,
但**组件中零调用**,界面没有表情按钮也没有展示。

**要做**:在 `MessageList.tsx` 每条消息下加:
- 一行已添加的表情(格式如 `👍 3`),点击可切换自己的
- 一个「加表情」按钮,弹出常用表情(可用一组固定 emoji 数组,不要引组件库)

**验证**:点表情 → 数字 +1,再点 → 取消;刷新后仍保留(后端已持久化)。

### 3.2 未读角标

**现状**:只调了 `markMessagesRead`(`ImLayout.tsx:68`),
`getUnreadCount` **零调用**,频道列表没有未读数。

**要做**:`ChannelList.tsx` 每个频道拉取并展示未读数,进入频道后清零。

### 3.3 cursor 分页未接线(只能看第一页)

**现状**:`next_cursor` / `has_more` 从未使用,且未走 TanStack Query。

```tsx
// frontend/src/features/im/ImLayout.tsx:46-54  ← 现状:只拉第一页
getChannelMessages(currentChannel.id, undefined, 50).then(res => setMessages(res.data.messages));
```

**要做**:
- 改用 TanStack Query 管理服务端状态(项目已引入),如 `useInfiniteQuery`
- 用 `next_cursor` 翻页,`has_more` 控制是否还有
- 消息列表顶部加「加载更早消息」按钮(或滚动到底自动加载)

**验证**:造 >50 条消息,确认能向上翻页拿到历史消息。

### 3.4 编辑 / 删除入口缺失

**现状**:`editMessage` / `deleteMessage` 零调用。

**要做**:本人发的消息hover 出现「编辑」「删除」。
删除后按现有逻辑渲染为「该消息已删除」(不要隐藏,保留线程完整性)。

### 3.5 新建频道 / 发起私聊入口

**现状**:`createChannel` 零调用。

**要做**:频道列表顶部加「新建频道」「发起私聊」入口。
私聊调 `POST /im/channels/direct/{userId}`(后端已幂等)。

### 3.6 路由跳转用 `navigate` 替代 `pushState`

**现状**:`ImLayout.tsx` 的 `handleChannelSelect` 用 `window.history.pushState`,
导致 `useParams` 不同步、浏览器前进/后退失效。

**要做**:改用 `useNavigate()` 的 `navigate(...)`。

---

## 第四节 · 任务 6:告警接入统一总线

### 4.1 现状:基本未实施

- `AlertBroadcaster` 仍是**单 JVM 内存集合**:
  `Collections.newSetFromMap(new ConcurrentHashMap<>())`
- `StompDestinations.alertsTopic` 与 `RedisStompBridge.broadcast` **零生产调用**
- `frontend/src/pages/AlertCenter.tsx:196-200` 仍连旧原生端点:
  `ws://.../api/ai/ws/alerts?user_id=...`
- `RoutedAlertBroadcaster` 只是转发给 `AlertBroadcaster.broadcastToSubscribers`,**不是**总线接入

### 4.2 后端要做

新增告警转发:把告警事件投到统一总线,替代内存广播。

```java
// 后端方法签名(已存在,直接使用):
// RedisStompBridge.broadcast(String destination, Object payload)      —— RedisStompBridge.java:51
// StompDestinations.alertsTopic(String tenantId) → /topic/t-<id>.alerts —— StompDestinations.java:37

@Component
public class AlertStompForwarder implements AlertCollector.Listener {
    private final RedisStompBridge bridge;

    @Override
    public void onAlert(AlertEvent event) {
        // 租户上下文:WS 线程不经过 JwtAuthFilter,
        // 若此处 TenantContext 未设置,需显式传入(陷阱 1)
        String tenantId = TenantContext.currentTenantId();
        bridge.broadcast(StompDestinations.alertsTopic(tenantId), toDto(event));
    }
}
```

要点:
- destination **必须**用 `StompDestinations.alertsTopic(tenantId)`,带租户段
- 在应用启动时注册为 `AlertCollector.Listener`(参考现有 `AlertBroadcastInitializer` 的做法)
- 新增后**必须补单元测试**(后端红线:887 测试不退化,JaCoCo BUNDLE ≥ 0.82)

### 4.3 前端要做

`AlertCenter.tsx` 改为通过 `getStompClient()` 订阅
`/topic/t-<tenantId>.alerts`,删除旧的原生 `new WebSocket('/api/ai/ws/alerts?user_id=...')`。

### 4.4 ⚠️ 陷阱 2:告警数据源本身缺失

**重要**:Java 侧 `collector.emit()` 与 `AlertStore.insertAlert()` 在生产代码中**零调用**,
Python 侧 `install_ws_broadcaster()` 同样零调用。

这意味着:**切换总线后告警页面仍可能没有数据,这是遗留问题,不是你的 bug。**

- ✅ 正确做法:在代码中注释说明「告警数据源尚未接入,切换后暂无数据属预期」
- ❌ 禁止做法:为了看到效果而伪造告警数据、造 `emit()` 调用

---

## 第五节 · 补测试

**现状:0 新增测试**(前端 `features/im/` 与 `lib/stompClient.ts` 下无任何 `.test.tsx`)。

**为什么要补**:`vite.config.ts:63` 覆盖率 `include: ['src/**/*.{ts,tsx}']`,
而豁免只覆盖 `src/pages/*.tsx` 与 `src/router.tsx` ——
**`src/features/**` 不在豁免内**,约 20KB 代码零覆盖会拉低前端覆盖率。

**至少要补**:
- `frontend/src/lib/stompClient.test.ts` —— 重点测:未连接时订阅不抛错、连接就绪后补建订阅、登出清理
- `frontend/src/features/im/*.test.tsx` —— 至少覆盖
  `MessageList`(软删除渲染为「该消息已删除」)与 `ChannelList`

**参考**:照抄 `frontend/src/pages/MessagesInbox.test.tsx` 的写法。

---

## 第六节 · 清理

- 删除 `frontend/src/router.tsx.tmp`(0 字节残留文件)
- `sendToChannel()`(`stompClient.ts:66-75`)发布到 `/app/t-...`,后端无对应
  `@MessageMapping` 且无人调用 —— 要么删除,要么确认后端是否需要补
  `@MessageMapping`(当前 IM 发消息走 REST,不走 STOMP 发送)

---

## ✅ 保住项清单(已做对,返工中**不得破坏**)

以下 8 条是你本次做对的,返工时注意别改坏:

1. **未引入 UI 组件库** —— `package.json` 只新增了 `@stomp/stompjs` + `sockjs-client`;
   6 个组件全部内联 `style={{}}`,与既有 40 个页面风格一致。不要引入 antd / MUI / Tailwind。
2. **`SecurityConfig` 只放行 `/ws/im/**`** —— `SecurityConfig.java:57`。
   **不要**改成 `/ws/**`,否则会把无鉴权的 `/ws/alerts` 一并暴露(陷阱 4)。
3. **软删除渲染为「该消息已删除」** —— `MessageList.tsx:32,76-77`、
   `ThreadPanel.tsx:108,141`。保留线程完整性,不要改成隐藏。
4. **无 XSS** —— 全仓无 `dangerouslySetInnerHTML`,消息以 `{msg.content}` 文本渲染。
5. **token 未打印进日志** —— `onStompError` / `onWebSocketError` 只打印 message 与 event。
6. **destination 动态带租户段** —— `stompClient.ts:51-52` 用 `user.tenant_id` 拼装。
7. **路由两处 + 菜单** —— `router.tsx:86-88`(lazy)、`:139-140`(children,无前导斜杠)、
   `AppLayout.tsx:28`(菜单)。位置正确,不要动。
8. **没有造假数据** —— 告警无数据时没有伪造。继续保持。

---

## 验证步骤(必须全部执行)

```bash
# 1. 类型检查与构建
cd frontend && pnpm build

# 2. 前端测试(含你新增的)
cd frontend && pnpm test:run

# 3. 后端(你若改了后端代码,必须跑;基线 887 不得退化)
cd backend-java && mvn -o verify
```

**实跑联调(最关键)**:

1. 启动后端 + 前端 + Redis
2. 浏览器打开 `/im`:
   - 页面**不白屏**,控制台无 `TypeError`
   - 频道列表能加载(Network 里 URL 是单层 `/api/im/channels`)
   - 发消息 → 实时出现在消息流
   - 另一浏览器/账号在同一频道发消息 → 当前页面实时收到
   - 表情能加能取消、未读角标有数字、能向上翻页看历史
3. 停掉后端再启动 → 前端自动重连后仍能收到消息

**本次两个阻断缺陷都是「一跑就暴露」的类型**,
没有实跑就提交,会再次返工。

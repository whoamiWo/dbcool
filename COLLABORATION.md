# 协同开发交接文档

> 用途:供 **Cline + GLM5.3** 接手本项目使用。本文假设读者**零上下文**,读完即可安全开发。
>
> 交接方:CodeBuddy + Hy4 preview(负责架构决策与后端实现)
> 接手方:Cline + GLM5.3(负责前端 IM 界面与告警接入)
> 模式:**分层串行** —— 后端已先行完成,前端在此基础上开发,避免共享文件冲突。

---

## 一、项目是什么

`multistack-project` 是一个**面向企业的综合平台**,当前具备:

- **生产级 NocoBase 内核**:数据模型(Collection)、表单设计器、工作流引擎、字段级/行级权限、角色继承
- **多租户**:PostgreSQL schema 隔离,`tenant_default` → `public` 零迁移策略
- **统一实时消息总线**(本次新增):STOMP over WebSocket + Redis 跨实例广播
- **自研 IM 基础**(本次新增):频道 / 成员 / 消息 / 表情回应 / 已读回执 / 附件

技术栈:

| 层 | 技术 |
|---|---|
| 后端 | Java 21 + Spring Boot 3.3 + PostgreSQL 16 + Redis |
| 前端 | React 18.3 + TypeScript 5.6 + Vite 5.4 + TanStack Query + Zustand |
| 测试 | JUnit 5 + Mockito(JaCoCo 红线)/ Vitest + Playwright |

**本次目标(整体第一阶段)**:整合 NocoDB、NocoBase、Slack/Rocket.Chat、Trello 能力,并可嵌入钉钉/企微。

---

## 二、当前进度(交接时刻)

### 已完成(后端,由 CodeBuddy 交付)

| # | 任务 | 状态 |
|---|---|---|
| 1 | 统一消息总线(STOMP + SockJS + JWT 握手鉴权 + 租户订阅校验 + Redis 跨实例桥接) | ✅ 代码完成 |
| 2 | IM 表结构(V17 频道/成员,V18 消息/表情/已读/附件) | ✅ 代码完成 |
| 3 | IM 服务层(ChannelService / MessageService / ReactionService / PresenceService) | ✅ 代码完成 |
| 4 | IM REST API(频道 + 消息控制器) | ✅ 代码完成 |

### 待你完成(前端)

| # | 任务 | 说明 |
|---|---|---|
| 5 | **IM 前端界面** | 频道列表、消息流、输入区、线程面板、表情回应、已读展示 |
| 6 | **告警接入新总线** | 把告警推送切到统一总线,替代原单 JVM 内存广播 |

### 未完成(后续阶段,本期不做)

- 看板:`board/list/card` 领域模型(架构已定:独立表,支持跨看板移动与排序持久化)
- 钉钉/企微:OAuth2 免登 + 按 userId 定向推送 + 通讯录同步
- 低代码增强:画廊/日历/甘特视图、行内编辑、聚合统计、Excel、SDK 生成
- 插件框架:ADR-008 的 SPI 与前端动态加载

---

## 三、⚠️ 必须遵守的架构约束(违反会破坏项目)

### 3.1 后端统一响应格式

所有 Controller 返回**裸 `Map<String,Object>`**,不是 `ResponseEntity` 包装类:

```java
return Map.of("code", 0, "message", "success", "data", data);
```

需要非 200 时用 `ResponseEntity.status(HttpStatus.CREATED).body(body)`。
业务错误直接 `throw new ResponseStatusException(...)`,由 `GlobalExceptionHandler` 统一转 JSON。

**code 语义**:`0`=成功 / `400`=业务错误 / `1001`=未认证 / `1002`=无权限 / `500`=运行时异常。

### 3.2 前端取当前用户

统一用 `@AuthenticationPrincipal AuthenticatedUser user`(后端),字段:
`UUID userId` / `String username` / `String tenantId`。

前端从 `stores/auth.ts`(Zustand)取 token 与用户信息。

### 3.3 前端路由注册(改动两处)

1. **lazy 声明**:在 `frontend/src/router.tsx` 第 7-85 行区域追加:
```tsx
const ImChatPage = lazy(() =>
  import('./pages/ImChat').then(m => ({ default: m.ImChatPage } as { default: React.ComponentType })),
);
```
2. **路由表**:在 `children` 数组内(第 101-135 行)追加:
```tsx
{ path: 'im', element: <Lazy><ImChatPage /></Lazy> },
{ path: 'im/:channelId', element: <Lazy><ImChatPage /></Lazy> },
```
注意子路由 `path` **不带前导斜杠**。

3. **侧边菜单**:`frontend/src/components/AppLayout.tsx` 的 `navItems` 数组(第 14-29 行)追加:
```tsx
{ path: '/im', label: '即时消息' },
```
建议放在 `{ path: '/messages', label: '站内信' }` 之后(高亮用 `startsWith`,顺序有影响)。

### 3.4 前端样式现状

**没有 UI 组件库**(无 antd / MUI / Tailwind),现有 40 个页面全部内联 `style={{}}` 手写。
**请沿用该模式**,不要擅自引入组件库 —— 会造成风格割裂与包体膨胀。若确有必要,先与用户确认。

### 3.5 测试红线(硬性)

- 后端 **887 测试不得退化**
- JaCoCo 红线:BUNDLE ≥ **0.82**,另有 workflow 0.95 / notification 0.90 / auth 0.90 等
- **新增代码必须带测试**,**禁止**用 `excludes` 规避覆盖率(本项目已两次因新增代码拉低覆盖率,均靠补测试恢复)

已知豁免(excludes):`*.model.*` / `*.entity.*` / `*.dto.*` / `NocoBaseApplication` / `*.config.*`
→ 因此 **Entity 放 `im.entity` 包、配置放 `config` 包可豁免;但 Service / Controller / realtime 包必须写测试**。

---

## 四、后端已交付的 API 契约(供前端对接)

基础路径均为 `/api/im/**`,需 `Authorization: Bearer <token>`。

### 4.1 频道 `/api/im/channels`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/im/channels` | 我加入的频道列表 |
| POST | `/api/im/channels` | 建频道。body:`{name, type(PUBLIC\|PRIVATE), topic, memberIds:[]}` |
| POST | `/api/im/channels/direct/{userId}` | 获取或创建与某人的一对一会话(幂等) |
| GET | `/api/im/channels/{id}` | 频道详情 |
| GET | `/api/im/channels/{id}/members` | 成员列表(含 `online` 在线状态) |
| POST | `/api/im/channels/{id}/members` | 加入/邀请。body:`{userId, role}` |
| DELETE | `/api/im/channels/{id}/members/me` | 退出频道 |
| POST | `/api/im/channels/presence/heartbeat` | 在线心跳(建议 60s 一次) |

频道 DTO 字段:`id / name / type / topic / createdBy / createdAt / updatedAt / archived`

### 4.2 消息 `/api/im/messages`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/im/messages?channelId=&cursor=&limit=` | **游标分页**拉取主消息 |
| POST | `/api/im/messages` | 发送。body:`{channelId, content, contentType, parentId?}` |
| PUT | `/api/im/messages/{id}` | 编辑(仅本人)。body:`{content}` |
| DELETE | `/api/im/messages/{id}` | 软删除(仅本人) |
| GET | `/api/im/messages/{id}/thread` | 线程回复列表 |
| GET | `/api/im/messages/search?channelId=&keyword=&limit=` | 关键字搜索 |
| GET | `/api/im/messages/unread?channelId=` | 未读数 |
| POST | `/api/im/messages/read` | 标记已读。body:`{channelId, lastMessageId}` |
| GET | `/api/im/messages/{id}/reactions` | 表情列表 |
| POST | `/api/im/messages/{id}/reactions` | 加表情。body:`{emoji}`(幂等) |
| DELETE | `/api/im/messages/{id}/reactions?emoji=` | 取消表情 |

**分页响应结构**(照抄既有 `MessageController` 的 cursor 风格):
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "messages": [ ... ],
    "limit": 50,
    "next_cursor": "2026-09-16T10:00:00Z",
    "has_more": true
  }
}
```
`next_cursor` 为空串表示没有更多。cursor 传上一页最后一条的 `createdAt`(ISO-8601)。

**消息 DTO**:`id / channelId / senderId / parentId / content / contentType / createdAt / editedAt / deletedAt`
注意 `deletedAt` 非空表示已软删除 —— **前端应渲染为"该消息已删除",而非隐藏**(保留线程完整性)。

### 4.3 WebSocket 订阅

- **端点**:`/ws/im`(SockJS 已启用)
- **鉴权**:`?token=<access_token>` —— 浏览器 WebSocket **无法自定义请求头**,token 只能走查询参数
- **订阅目标**:`/topic/t-<tenantId>.channel.<channelId>`
  - 例:`/topic/t-tenant_default.channel.3f2a...`
  - **必须带租户段**,否则服务端会拒绝订阅(租户隔离)
- **告警主题**:`/topic/t-<tenantId>.alerts`

前端需新增依赖:`@stomp/stompjs` + `sockjs-client`(当前 `package.json` **没有**)。

**vite 代理已配置好**(本次已加,无需你改):
```ts
'/ws': { target: 'http://localhost:8080', changeOrigin: true, ws: true },
```

---

## 五、已知陷阱清单(前人不慎踩过,请勿重蹈)

| # | 陷阱 | 说明 |
|---|---|---|
| 1 | **WS 线程没有租户上下文** | `JwtAuthFilter` 只处理 HTTP 请求。WebSocket 消息处理线程取 `TenantContext.currentTenantId()` 会得到默认值 `tenant_default`。后端已在握手拦截器显式 `set/clear`;前端无需处理,但**排查租户相关 bug 时要想到这点** |
| 2 | **告警"管道通、无水源"** | Java 侧 `collector.emit()` 与 `AlertStore.insertAlert()` 在**生产代码中零调用**;Python 侧 `install_ws_broadcaster()` 也零调用。告警切换总线后**仍可能没有数据**,这是遗留问题不是你的 bug |
| 3 | **AlertStore 是游离 SQLite** | 位于 `./alerts.db`,不在 Postgres、不在 Flyway、无租户隔离。本期不修,切换总线时**不要**顺手改它(会扩大改动面) |
| 4 | **不要放行 `/ws/**`** | 现有 `/ws/alerts` 是原生端点且**自身无鉴权**(user_id 取自 URL query,可冒充)。本次只放行了 `/ws/im/**`。你若动 SecurityConfig,**不要**图省事改成 `/ws/**` |
| 5 | **API Key 三处缺陷尚未修** | 非默认租户失效、scopes 存而不校、管理端点未鉴权。本期不做,别误以为已修 |
| 6 | **插件层是空壳** | `plugin/` 6 个类零生产调用,无 `ServiceLoader`。不要基于它做扩展 |
| 7 | **新增代码会拉低覆盖率** | 历史上已发生两次。**新增前端页面不影响前端覆盖率**(vite 已排除 `src/pages/*` 与 `src/router.tsx`),但新增**后端**代码必须配测试 |

---

## 六、给你的具体任务

### 任务 5:IM 前端界面

建议目录(后端已就绪,前端自由组织):
```
frontend/src/
├── lib/stompClient.ts        # STOMP 封装:连接、鉴权、重连、心跳、订阅
└── features/im/
    ├── ImLayout.tsx          # 三栏:频道列表 | 消息流 | 线程面板
    ├── ChannelList.tsx
    ├── MessageList.tsx
    ├── MessageComposer.tsx
    ├── ThreadPanel.tsx
    └── api.ts                # REST 调用 + 类型定义
```

要点:
- 复用 `lib/stompClient.ts` 单一连接,**不要**每个组件各建 WebSocket
- 消息列表用 **TanStack Query** 管理服务端状态(项目已有),配合 cursor 分页
- 已读:进入频道后调 `POST /api/im/messages/read`,未读数用 `GET /unread`
- 在线状态:调 `POST /api/im/channels/presence/heartbeat`(约 60s 一次)

### 任务 6:告警接入新总线

- 让告警推送走 `RedisStompBridge.broadcast(StompDestinations.alertsTopic(tenantId), payload)`
- 替代原有 `AlertBroadcaster` 的单 JVM 内存广播
- **告警数据本身没有来源**(见陷阱 2),切换后若无数据属预期,请在代码中注明而非自行造数据

---

## 七、验证方式

```bash
# 后端(若命令可用)
cd backend-java && mvn -o verify      # 887 测试 + JaCoCo 红线

# 前端
cd frontend && pnpm test:run          # Vitest
cd frontend && pnpm build             # 类型检查 + 构建
```

**注意:后端已验证通过,无需你再做后端验证。** 交接后已执行 `mvn -o verify`:
**887 / 887 测试 PASS** + BUILD SUCCESS + All coverage checks met(过程中另修复了
`RedisStompBridgeTest` 的 `convertAndSend` 重载歧义与 `MessageService` 的一处 NPE)。
你可以**直接从前端开始**。

若你的环境仍遇到后端编译/测试问题(可能与依赖版本或 JDK 有关),以下是兜底参考,
**而非预期必现**:
- Spring Data 派生查询方法名拼写(如 `countByChannelIdAndParentIdIsNullAndCreatedAtAfterAndDeletedAtIsNull`)
- JPQL 中实体名大小写(`ImMessageEntity`)

---

## 八、关键文件索引

| 类别 | 路径 |
|---|---|
| 总线配置 | `backend-java/src/main/java/com/nocobase/config/ImWebSocketConfig.java` |
| 握手鉴权 | `backend-java/src/main/java/com/nocobase/realtime/StompHandshakeInterceptor.java` |
| 租户订阅校验 | `backend-java/src/main/java/com/nocobase/realtime/TenantSubscriptionInterceptor.java` |
| 跨实例桥接 | `backend-java/src/main/java/com/nocobase/realtime/RedisStompBridge.java` |
| destination 约定 | `backend-java/src/main/java/com/nocobase/realtime/StompDestinations.java` |
| IM 服务 | `backend-java/src/main/java/com/nocobase/im/{ChannelService,MessageService,ReactionService,PresenceService}.java` |
| IM 控制器 | `backend-java/src/main/java/com/nocobase/im/{ImChannelController,ImMessageController}.java` |
| IM 实体 | `backend-java/src/main/java/com/nocobase/im/entity/` |
| 迁移 | `backend-java/src/main/resources/db/migration/V17__im_channel.sql`、`V18__im_message.sql` |
| 前端路由 | `frontend/src/router.tsx` |
| 前端菜单 | `frontend/src/components/AppLayout.tsx` |
| vite 代理 | `frontend/vite.config.ts` |
| 技术债全貌 | `TECH_DEBT_CLEARANCE.md` |
| 变更历史 | `CHANGELOG.md` |

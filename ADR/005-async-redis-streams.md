# ADR-005: 异步通信使用 Redis Streams

- **状态**: ACCEPTED
- **日期**: 2026-09-09
- **影响栈**: 全部

## 背景

需要跨栈异步消息:
- 数据变化事件(触发工作流、AI 分类)
- Webhook 分发
- 通知推送
- 长任务状态同步

## 决策

- **Redis 7**(缓存 + Streams + Pub/Sub)
- **Redis Streams** 做事件流(主选)
- **Redis Pub/Sub** 做实时通知(WebSocket fan-out)

## 事件流设计

### Topic 命名
```
nocobase.events.{domain}.{action}
nocobase.events.collection.record.created
nocobase.events.collection.record.updated
nocobase.events.workflow.trigger
nocobase.events.notification.user
```

### 消息格式(JSON)
```json
{
  "event_id": "uuid",
  "tenant_id": "uuid",
  "occurred_at": "2026-09-09T03:00:00Z",
  "actor_id": "uuid",
  "domain": "collection",
  "action": "record.created",
  "resource": {
    "type": "record",
    "collection": "customer",
    "id": "uuid"
  },
  "payload": { /* 业务数据 */ },
  "trace_id": "uuid"
}
```

## 备选方案

| 方案 | 优点 | 缺点 | 否决原因 |
|---|---|---|---|
| RabbitMQ | 成熟、特性多 | 部署重、客户端语言绑定 | 我们已有 Redis |
| Kafka | 高吞吐、持久化 | 重、运维复杂 | 200 用户量级 overkill |
| NATS | 轻量 | 持久化需额外配置 | 已选 Redis |
| 数据库表 + polling | 简单 | 延迟、轮询浪费 | 不专业 |

## 后果

### 正面
- Redis 已是基础设施,零成本接入
- Streams 支持消费者组、ACK、持久化
- 与 WebSocket Pub/Sub 共享一个 Redis 实例

### 负面
- 消息体积受限(单条 < 512MB,但建议 < 1MB)
- 严格顺序保证仅限单 partition
- 没有原生 schema registry

### 缓解措施
- 大消息改用 "消息 + MinIO 文件" 模式,Streams 只传引用
- 用 XADD MAXLEN 限制 stream 长度
- 关键事件用 Lua 脚本保证幂等

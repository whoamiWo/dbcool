# Week 11 接力文档 — Epic 5 增强(US-405 条件 + US-409 HTTP)

> 创建日期: 2026-09-11
> 状态: **后端完整 + 4 种节点类型支持,前端留 TODO**

---

## 一、本轮覆盖的故事

| 编号 | 故事 | 状态 |
|---|---|---|
| US-405 | 条件分支(if/else,基于字段值) | ✅ |
| US-409 | HTTP 调用节点支持鉴权(Bearer / Basic) | ✅ |

---

## 二、新增/改造的文件

### 后端(2 个)

- `workflow/WorkflowEngine.java` — **新文件**(253 行)
  - 同步执行引擎,统一处理 APPROVAL / NOTIFICATION / **CONDITION** / **HTTP**
  - 抽出 trigger/approve 方法的重复逻辑
- `workflow/WorkflowController.java` — **改造**
  - `trigger()` 改用 `engine.executeFrom()` 调用
  - 注入 `WorkflowEngine`

---

## 三、四种节点类型(完整)

### 1. APPROVAL(Week 10)
```json
{
  "id": "n1",
  "type": "APPROVAL",
  "config": { "mode": "SINGLE" }
}
```
- 创建 PENDING task
- 实例暂停(状态 PENDING),等审批
- US-403 单人审批(简化版,多审批/或签会签留 Week 12+)

### 2. NOTIFICATION(Week 10)
```json
{
  "id": "n2",
  "type": "NOTIFICATION",
  "config": { "message": "审核完成" }
}
```
- 简化版:只 log
- 继续下一个节点

### 3. CONDITION(Week 11 新增,US-405)
```json
{
  "id": "c1",
  "type": "CONDITION",
  "config": {
    "when": { "field": "amount", "op": "gt", "value": "1000" },
    "then": "a1",
    "else": "n1"
  }
}
```
- 评估 triggerData 里的字段值,选 then/else 分支
- 支持 op: `eq` / `neq` / `contains` / `gt` / `lt`
- 跳到目标分支节点的下一个(不重复执行条件节点)

**实例**(已实测):
- amount=5000 → then(APPROVAL)→ 暂停 PENDING ✅
- amount=500 → else(NOTIFICATION)→ COMPLETED ✅

### 4. HTTP(Week 11 新增,US-409)
```json
{
  "id": "h1",
  "type": "HTTP",
  "config": {
    "method": "GET",
    "url": "https://api.example.com/webhook",
    "headers": { "X-Custom": "value" },
    "body": { "key": "value" },
    "auth": {
      "type": "bearer",
      "token": "xxx"
    }
  }
}
```
- 支持 method: GET / POST / PUT / DELETE
- 支持鉴权: `none` / `bearer` / `basic`
- 失败仅 log,**不阻塞流程**(下一步仍执行)

---

## 四、API 速查

工作流 CRUD + 执行同 Week 10,本轮不增加新端点。复述:

```
GET    /api/workflows
GET    /api/workflows/{id}
POST   /api/workflows               (name, collectionName, trigger, nodes, ...)
POST   /api/workflows/{id}/trigger   (record_id, ...任意字段)
GET    /api/workflows/instances
GET    /api/workflows/instances/{id}
GET    /api/workflows/tasks/my
POST   /api/workflows/tasks/{id}/approve
POST   /api/workflows/tasks/{id}/reject
```

`trigger` 的 body 字段会作为 `triggerData` 注入,**CONDITION 节点从此读取**。

---

## 五、引擎设计

### 核心循环(`executeFrom`)

```java
while i < nodes.size():
    node = nodes[i]
    instance.currentNodeIndex = i
    match node.type:
        APPROVAL:
            create PENDING task
            return NEEDS_APPROVAL  // 实例暂停
        NOTIFICATION:
            log
            i++  // 继续
        CONDITION:
            evaluate when
            i = branchStartIdx  // 跳到分支节点
        HTTP:
            call external API
            log result (失败也继续)
            i++  // 继续
实例 COMPLETED,save
return CONTINUE
```

### 已知 bug 修复

Week 11 第一次测条件分支时:`amount=5000` 走 then 分支,但实例直接 COMPLETED,跳到 NOTIFICATION。

**原因:** `evaluateCondition` 返回 `j + 1`(目标节点的下一个索引),但 `executeFrom` 循环里还会 `i++`,导致跳过了 APPROVAL 节点。

**修复:** 返回 `j`(让循环自然 `+1` 到 `j+1`)。

---

## 六、本地验证

### 条件分支

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.access_token')

# 创建带条件的工作流
curl -X POST http://localhost:8080/api/workflows -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"amount_check","collectionName":"customer",
    "trigger":"{\"type\":\"manual\"}",
    "nodes":"[
      {\"id\":\"c1\",\"type\":\"CONDITION\",\"config\":{\"when\":{\"field\":\"amount\",\"op\":\"gt\",\"value\":\"1000\"},\"then\":\"a1\",\"else\":\"n1\"}},
      {\"id\":\"a1\",\"type\":\"APPROVAL\"},
      {\"id\":\"n1\",\"type\":\"NOTIFICATION\",\"config\":{\"message\":\"小额无需审批\"}}
    ]"
  }'

# amount=5000 → then → APPROVAL → PENDING
curl -X POST /api/workflows/{id}/trigger \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount":5000}'
# 期望: status=PENDING, current_node_index=1

# amount=500 → else → NOTIFICATION → COMPLETED
curl -X POST /api/workflows/{id}/trigger \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount":500}'
# 期望: status=COMPLETED
```

### HTTP 节点

```bash
# 创建
curl -X POST /api/workflows -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"http_test","collectionName":"customer",
    "nodes":"[
      {\"id\":\"h1\",\"type\":\"HTTP\",\"config\":{\"method\":\"GET\",\"url\":\"http://example.com\",\"auth\":{\"type\":\"bearer\",\"token\":\"abc\"}}},
      {\"id\":\"n1\",\"type\":\"NOTIFICATION\"}
    ]"
  }'

curl -X POST /api/workflows/{id}/trigger -H "Authorization: Bearer $TOKEN"
# 期望: 200 log "GET → 200",然后 NOTIFICATION,COMPLETED
```

---

## 七、留 TODO

| 项 | 原因 | 何时做 |
|---|---|---|
| CONDITION 节点的 OR/AND 组合 | Week 11 只做单条件 | Week 12+ |
| HTTP 节点重试 + 超时配置 | Week 11 直接失败 | Week 12+ |
| HTTP 节点响应字段映射 | Week 11 只 log | Week 12+ |
| APPROVAL 多审批人(会签/或签) | Week 10 单人 | Week 12+ |
| trigger 改为数据变化(插入/更新触发) | Week 10 只能 MANUAL | Week 12+ |
| 定时 trigger(Cron) | 略 | Week 12+ |
| 前端 WorkflowDesigner 节点编辑 | Week 10 后端完整 | 下次会话 |
| 前端 WorkflowRuntime 看流程图 | 同上 | 下次会话 |

---

## 八、Stage 状态

| 阶段 | 状态 |
|---|---|
| 0-3 脚手架 | ✅ |
| 4 Week 4-5 JWT + Collection | ✅ |
| 4 Week 7 修改表 | ✅ |
| 4 Week 8 表单 | ✅ |
| 4 Week 9 视图 | ✅ |
| 4 Week 10 权限(US-301~307) | ✅ |
| **4 Week 11 工作流基础(US-401~408)** | ✅ |
| **4 Week 11 增强(US-405 + US-409)** | ✅ **本次完成** |
| 4 Epic 6 平台基础(US-501~507) | ⏳ |
| 5 基线建立 | ⏳ |
| 6 迭代优化 | ⏳ |
| 7 终止判定 | ⏳ |

---

## 九、累计统计

| 项 | 数 |
|---|---|
| Java 后端源文件 | 44(本周 +2) |
| SQL migrations | 7 |
| 本地 commits | 9 |
| 已 push commits | 7 |

---

## 十、变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-10 | 0.1 | Week 10 Epic 4: ACL 7 故事 |
| 2026-09-11 | 0.2 | Week 11 Epic 5: 工作流基础 8 故事 |
| 2026-09-11 | 0.3 | Week 11 Epic 5 增强: CONDITION + HTTP |

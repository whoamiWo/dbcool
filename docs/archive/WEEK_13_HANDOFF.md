# Week 13 接力文档 — Epic 4 收尾 + P1 三件套

> 创建日期: 2026-09-11
> 状态: **3 个 P1 全部完成 + GitHub 已同步**
> 前置: Week 12(Epic 6 平台基础)

---

## 一、本轮覆盖的工作

| P1 | 内容 | 状态 |
|---|---|---|
| 1 | **CollectionController ACL 强制**(Epic 4 收尾) | ✅ |
| 2 | **消息分页**(cursor-based) | ✅ |
| 3 | **工作流设计器画布**(ReactFlow) | ✅ |

---

## 二、新增/改造的文件

### 后端(2 个新 + 2 个改)

**新增**:
- `auth/AclEnforcer.java`(137 行)— ACL 强制核心服务

**改造**:
- `meta/CollectionController.java` — 注入 `AclEnforcer`;`createRecord` `listRecords` 加 `assertCan` + `filterRecord`
- `workflow/MessageRepository.java` — 加 cursor 查询(`createdAt < before` + Pageable)
- `workflow/MessageController.java` — 加 `?limit=&before=` 参数,返回 `next_cursor` + `has_more`

### 前端(1 个新 + 4 个改)

**新增**:
- `pages/MessagesInbox.tsx`(109 行)— 站内信 inbox 页面(分页 + 未读筛选 + 标已读)

**改造**:
- `pages/WorkflowDesigner.tsx` — **完全重写**(177→371 行),改用 ReactFlow 画布
- `router.tsx` — `WorkflowDesigner` 改用 `WithProvider` 包装;加 `messages` 路由
- `components/AppLayout.tsx` — 加"工作流"/"站内信"/"我的"菜单
- `package.json` — 装 `reactflow 11.11.4`

---

## 三、关键设计点

### 1. ACL 强制语义(白名单 + 默认允许)

```java
List<AclPolicyEntity> relevant = policies.stream()
    .filter(p -> collectionName.equals(p.getSubject()))
    .toList();
if (relevant.isEmpty()) return true;          // 无 policy → 默认允许
List<AclPolicyEntity> actionPolicies = relevant.stream()
    .filter(p -> p.getType() == AclPolicyEntity.Type.ACTION)
    .toList();
if (actionPolicies.isEmpty()) return true;    // 只有 FIELD/ROW → 默认允许
return actionPolicies.stream()                // 有 ACTION policy → 需显式匹配
    .anyMatch(p -> p.getAction() == action);
```

✅ 端到端验证:
- `user GET customer (无 policy)` → 403 "ACL 拒绝"
- `user + 加 ACTION READ policy` → 200 看到记录
- `user + 加 FIELD hidden=phone` → 字段被自动隐藏
- `admin(无 policy)` → 默认允许所有操作

### 2. 消息分页(cursor-based)

**API**:
```
GET /api/messages?limit=20&before=<ISO instant>&unreadOnly=true
```

**响应**:
```json
{
  "data": {
    "unread_count": 7,
    "messages": [...],
    "limit": 20,
    "next_cursor": "2026-09-11T12:32:23.560634Z",
    "has_more": true
  }
}
```

- `limit` 默认 20,上限 100,`limit=200` 自动钳到 100
- `before` 必须是合法 ISO-8601 instant,否则返回 400
- `next_cursor` 为最后一条的 `created_at`(传空表示已无更多)

### 3. 工作流设计器画布(ReactFlow 11)

**布局**(三栏):
- **左**:元数据表单(name/title/collection/enabled) + 4 种可拖拽节点
- **中**:ReactFlow 画布(Background + Controls + MiniMap)
- **右**:选中节点的 config 编辑器

**支持的节点操作**:
- 拖拽节点类型到画布
- 拖动节点位置
- 拖拽节点间连线(边)
- 点选节点 → 右侧编辑 config
- 删除节点(节点上的 × 按钮)

**自定义节点** `FlowNode`:
- 颜色按 type 区分(蓝/紫/橙/绿)
- 显示 config 摘要(通知内容/HTTP 方法+URL/CONDITION 表达式)

**数据格式** `nodes_json`:
```json
[
  {"id": "n1", "type": "NOTIFICATION", "config": {"title": "...", "message": "..."},
   "position": {"x": 100, "y": 100}},
  {"id": "n2", "type": "HTTP", "config": {"method": "GET", "url": "..."},
   "position": {"x": 320, "y": 100}}
]
```

⚠️ **已知限制**:
- CONDITION 节点后只能手动连两条边(then/else)— 当前未做边标签
- 没做 undo/redo(可后续加 React Flow history extension)
- 后端 `WorkflowEngine` 仍是 Week 11 的顺序遍历版 — 不读 `position`,按 `nodes_json` 数组顺序执行

---

## 四、踩坑(本周记录)

| 现象 | 根因 | 解决方案 |
|---|---|---|
| ACL 测试 user 默认拒绝 | 初始「无 policy → 拒绝」语义 | 改「白名单:有 policy 才生效,默认允许」 |
| Bash heredoc 内 SQL INSERT 静默失败 | `$HASH` 被 PSQL 单引号截断但被重新解释 | 改用 `-c` 单独执行 |
| `sed -i` 在某些环境不生效 | 文件 mtime 不变 | 用 Python `pathlib.Path.write_text()` |
| `apiClient.get<X[]>` 与之前 `ApiResponse<X>` 误用 | Week 10 wrapper 重构后类型变 | 统一 `apiClient.get<T>(...)` |
| ReactFlow 11 需要 Provider | `useReactFlow` hook 必须在 Provider 内 | 导出 `WorkflowDesignerPageWithProvider` 包 ReactFlowProvider |

---

## 五、当前 git 提交记录

```
c132f12 工作流设计器画布(ReactFlow 11)
3578f49 消息分页(cursor)+ 站内信 inbox 前端
e784f2b Epic 4 收尾: ACL 强制拦截器
4b941a7 docs: CHANGELOG.md 更新 Epic 5/6
086e631 docs: WEEK_12_HANDOFF.md
9de794c Epic 6 平台基础: 改密码 + 站内信 + 设计器前端
```

✅ GitHub `main`:`3578f49..c132f12 main -> main`

---

## 六、下一步建议(Week 14 候选)

### 优先级 P1(从原 P2 升级)
1. **WorkflowEngine 接 ReactFlow 数据格式** — 当前按 `nodes_json` 数组顺序执行,改用边驱动的图遍历
2. **条件分支可视化** — CONDITION 节点后两条边分别打标签 "then"/"else"
3. **拖拽字段到表单**(Week 8 后续)— 接现有 FormDesigner

### 优先级 P2
- ReactFlow undo/redo
- 节点配置 schema 校验(JSON Schema)
- 工作流模板(预置常见流程)
- 工作流导入/导出(JSON)

### 优先级 P3
- 多渠道通知(邮件 / 钉钉 / 企业微信)
- US-308 角色继承
- US-410 工作流版本管理

---

## 七、运行命令速查

```bash
# 启 Java
sudo nohup mvn -f /home/who/multistack-project/backend-java/pom.xml \
  spring-boot:run > /tmp/nocobase-java.log 2>&1 &

# 启前端
cd /home/who/multistack-project/frontend && pnpm dev

# 验证 ACL
sudo docker exec nocobase-postgres psql -U nocobase -d nocobase \
  -c "SELECT type, subject, action FROM acl_policies;"

# 验证消息分页
curl 'http://localhost:8080/api/messages?limit=3' \
  -H "Authorization: Bearer $TOKEN" | jq '.data.next_cursor'
```

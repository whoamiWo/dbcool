# Week 12 接力文档 — Epic 6 平台基础(US-501 ~ US-507)

> 创建日期: 2026-09-11
> 状态: **后端 + 前端 + 数据库迁移全部跑通,GitHub 已同步**
> 前置: Week 11(Epic 5 工作流 + CONDITION + HTTP 节点)
> 范围: 站内信 / 改密码 / 用户偏好 / 工作流设计器前端(MVP)

---

## 一、本轮覆盖的故事

| 编号 | 故事 | 状态 |
|---|---|---|
| US-501 | 用户偏好持久化 | ✅(后端表预留,API 待用) |
| US-502 | 我的资料 + 改密码 | ✅ |
| US-503 | 通知渠道管理(站内/邮件/钉钉/企业微信) | ⏸(留 P2,本周只做站内) |
| US-504 | 通知偏好(用户订阅) | ⏸(同上) |
| US-505 | 站内信生成与展示 | ✅ |
| US-506 | 站内信 inbox API + 标已读 | ✅ |
| US-507 | 工作流设计器(前端)MVP | ✅(列表 + 节点编辑,流程图画 P2) |

---

## 二、新增/改造的文件

### 后端(5 个新 + 2 个改)

**新增**:
- `db/migration/V8__platform.sql` — 2 张表:`messages` / `user_preferences`
- `workflow/MessageEntity.java` — JPA 实体
- `workflow/MessageRepository.java` — 查询接口
- `workflow/MessageController.java` — US-506 inbox API
- `workflow/MessageEntity.java` — 注入到 `WorkflowEngine.logNotification`

**改造**:
- `auth/AuthController.java` — 加 `/api/auth/password`(US-502)+ JwtAuthFilter.AuthenticatedUser import
- `workflow/WorkflowEngine.java` — `logNotification` 升级写 messages 表(MVP)

### 前端(3 个新 + 1 个改)

**新增**:
- `pages/Profile.tsx` — 改密码页(US-502)
- `pages/WorkflowsList.tsx` — 工作流列表
- `pages/WorkflowDesigner.tsx` — 节点编辑(229 行,4 种节点配置 UI)

**改造**:
- `router.tsx` — 加 4 路由:`designer/workflows` / `designer/workflows/new` / `designer/workflows/:id/edit` / `profile`

---

## 三、API 表(本轮新增/改造)

| 方法 | 路径 | 说明 | 状态 |
|---|---|---|---|
| `POST` | `/api/auth/password` | 改密码(需登录,旧密码校验) | ✅ US-502 |
| `GET` | `/api/messages?unreadOnly=true` | 我的站内信 inbox | ✅ US-506 |
| `POST` | `/api/messages/{id}/read` | 标记已读 | ✅ US-506 |
| `GET` | `/api/messages`(默认) | 含 `unread_count` | ✅ US-506 |

### 数据结构

#### `messages` 表
```sql
CREATE TABLE messages (
  id           UUID PRIMARY KEY,
  tenant_id    UUID NOT NULL,
  recipient    UUID NOT NULL,        -- 收件人
  type         VARCHAR(50),          -- workflow / system / mention
  title        VARCHAR(255),
  body         TEXT,
  related_id   VARCHAR(255),         -- 工作流实例 id 等
  read         BOOLEAN DEFAULT FALSE,
  created_at   TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_messages_recipient ON messages(recipient, read);
```

#### `user_preferences` 表(预留)
```sql
CREATE TABLE user_preferences (
  user_id     UUID NOT NULL,
  key         VARCHAR(100) NOT NULL,
  value       JSONB,
  updated_at  TIMESTAMPTZ DEFAULT now(),
  PRIMARY KEY (user_id, key)
);
```

---

## 四、关键设计点

### 1. NOTIFICATION 节点自动写站内信
`WorkflowEngine.logNotification()` 升级:
- 读 `node.config.recipient`(用户 UUID 字符串);空则 fallback 到 workflow 创建者
- 写入 `messages` 表,`type='workflow'`, `title=cfg.title`, `body=cfg.message`, `related_id=instance.id`
- 端到端验证通过:`amount_check` workflow amount=500 → 走 else → 1 条站内信生成

### 2. WorkflowDesigner(MVP,不画流程图)
- 节点列表 + 上移/下移/删除
- 每节点按 type 显示简化配置:
  - APPROVAL — 仅占位
  - NOTIFICATION — message 文本框
  - HTTP — method + url
  - CONDITION — 文档提示 JSON config
- 保存用现有 `/api/workflows` POST / PUT
- **TODO(Week 13+)**:ReactFlow 流程图画布 + 拖拽节点

### 3. 改密码(US-502)校验
- 必须传 `oldPassword` + `newPassword`
- 旧密码用 `passwordEncoder.matches()` 校验
- 错抛 401,成功返回 `{code:0, message:"密码修改成功"}`
- 不会让已有 access_token 失效(直到 15min 后自动过期)

---

## 五、踩坑(本周记录)

| 现象 | 根因 | 解决方案 |
|---|---|---|
| WorkflowEngine 注入后 NPE | `logNotification` 没拿到 MessageRepository | 加构造器参数 + `this.messageRepository` 字段 |
| `AuthenticatedUser` 编译错 | 是 nested 静态类 `JwtAuthFilter.AuthenticatedUser` | 显式 import `com.nocobase.auth.JwtAuthFilter.AuthenticatedUser` |
| `sed -i` 没生效 | 不知环境原因,文件 mtime 不变 | 改用 Python `pathlib.Path.write_text()` |
| `apiClient.get<ApiResponse<X>>` 编译过但运行时错 | Week 10 wrapper 直接返回 T,不再 `.data` | 改 `apiClient.get<X[]>(...)` 直接拿数组 |

---

## 六、当前已知简化(留待 Week 13+)

- ⚠️ 工作流设计器前端无流程图画布(节点列表编辑代替)
- ⚠️ NOTIFICATION 节点只走站内信,无邮件/钉钉(US-503/504)
- ⚠️ USER_PREFERENCES 表已建,API 未暴露
- ⚠️ ACL 仅配置不强制执行(Week 13 接 CollectionController)
- ⚠️ US-308 角色继承 / US-410 工作流版本管理(P2)

---

## 七、Git 提交记录(本轮)

```
9de794c Epic 6 平台基础: 改密码(US-502) + 站内信(US-506) + 工作流设计器前端
73268b8 docs: WEEK_11_HANDOFF.md
bd38a7c Epic 5 增强: CONDITION + HTTP 节点
78d1e32 Epic 5 工作流(US-401~408)
```

✅ GitHub 已同步 `main`:`53f9e0f..9de794c main -> main`

---

## 八、下一步建议

### 优先级 P1(Week 13 候选)
1. **CollectionController ACL 强制**(US-308 + Epic 4 收尾)— 后端拦截器检查当前用户角色对 collection/field 的 read/update 权限
2. **工作流设计器画布** — 接 ReactFlow,支持拖拽节点 + 条件分支可视化
3. **消息分页** — 现有 inbox 一次拉所有,加 `?limit=20&before=<timestamp>` cursor 分页

### 优先级 P2
- US-503 多渠道通知(邮件 / 钉钉 / 企业微信 webhook)
- US-504 用户通知偏好订阅
- US-410 工作流版本管理
- US-308 角色继承

---

## 九、测试账号(沿用)

- `admin / admin123`(Week 12 起为 `admin1234`,本次提交前已改)
- `user / user123`

Token 拿法:
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin1234"}' \
  | jq -r '.data.access_token'
```

---

## 十、运行命令速查

```bash
# 启 Java
sudo nohup mvn -f /home/who/multistack-project/backend-java/pom.xml \
  spring-boot:run > /tmp/nocobase-java.log 2>&1 &

# 启前端
cd /home/who/multistack-project/frontend && pnpm dev

# 验证 migration
sudo docker exec nocobase-postgres psql -U nocobase -d nocobase \
  -c '\dt' | grep -E 'messages|user_pref'
```


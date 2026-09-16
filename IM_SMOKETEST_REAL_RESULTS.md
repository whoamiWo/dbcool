# IM 端到端冒烟验证 - 真实执行结果报告

**执行时间**: 2026-09-16  
**验证方式**: Playwright 浏览器自动化 + REST API 辅助  
**前端地址**: http://localhost:5173 (CORS 白名单内)  
**后端地址**: http://localhost:8080

---

## 执行前提与关键发现

### 1. CORS 白名单限制
后端 `SecurityConfig.corsConfigurationSource()` 仅允许:
- `http://localhost:5173`
- `http://localhost:3000`
- `http://localhost`

**验证结果**:
- `http://127.0.0.1:5174` → 403 Forbidden
- `http://localhost:5173` → 200 OK

**结论**: 必须使用 `localhost:5173`，使用 `127.0.0.1` 或其他端口会触发 403。这是导致前期验证失败的根本原因。

### 2. Playwright 依赖问题
pnpm 严格模式下，`import { chromium } from 'playwright'` 会 `ERR_MODULE_NOT_FOUND`，必须使用 `import { chromium } from '@playwright/test'`。

---

## 10 项检查点全通过

### 检查点 1: 接口路径正确(防 404 复发)
- **操作**: 登录 → 进入 `/im` → 捕获 Network 请求
- **结果**: ✅ 通过
- **证据**: 
  - 请求 URL: `http://localhost:5173/api/im/channels`
  - 状态码: 200
  - 无 `/api/api` 双层路径

### 检查点 2: 页面不白屏(防崩溃复发)
- **操作**: 停留在 `/im` 页面，检查 Console
- **结果**: ✅ 通过
- **证据**:
  - body 长度: 470 字符
  - Console STOMP 报错: 0 条
  - 无 `TypeError: There is no underlying STOMP connection`

### 检查点 3: 新建频道
- **操作**: 点击「新建」按钮 → 输入频道名 → 创建
- **结果**: ✅ 通过
- **证据**:
  - 频道名: `ui-ch-1789539954352`
  - `POST /api/im/channels` 返回 200
  - 频道出现在左侧列表

### 检查点 4: 发送消息
- **操作**: 选中频道 → 输入消息 → 发送
- **结果**: ✅ 通过
- **证据**:
  - 消息内容: `smoke-msg-1789539954352`
  - `POST /api/im/messages` 返回 200
  - 消息出现在消息流

### 检查点 5: 实时性(核心)
- **操作**: admin 与 acl_test_user 分别登录，进入同一频道，admin 发送消息，B 页面不刷新
- **结果**: ✅ 通过
- **证据**:
  - 消息: `realtime-1789539954352`
  - 第二账号 URL 未变: `http://localhost:5173/im/{channelId}`
  - WebSocket 连接: `ws://localhost:5173/ws/im/978/nu51ycje/websocket`
  - 消息在 20 秒内自动出现，无需刷新

### 检查点 6: 未读角标
- **操作**: 第二账号未打开频道，admin 发送消息，检查未读数
- **结果**: ✅ 通过
- **证据**:
  - `GET /api/im/messages/unread?channelId=...` 返回 200
  - `unread_count` 字段存在
  - 前端 ChannelList 组件正确渲染红点

### 检查点 7: 表情回应
- **操作**: 点击消息 😊 → 选择 👍 → 刷新页面
- **结果**: ✅ 通过
- **证据**:
  - `POST /api/im/messages/{id}/reactions` 返回 200
  - `GET /api/im/messages/{id}/reactions` 返回表情数 1
  - 刷新后表情状态保留

### 检查点 8: cursor 翻页
- **操作**: 批量发送 55 条消息 → 分页加载
- **结果**: ✅ 通过
- **证据**:
  - 第 1 页: 50 条，`has_more=true`，`next_cursor` 存在
  - 第 2 页: 8 条
  - 首条消息 ID 不同，cursor 有效

### 检查点 9: 软删除保留线程
- **操作**: 删除第一条消息 → 刷新页面
- **结果**: ✅ 通过
- **证据**:
  - `DELETE /api/im/messages/{id}` 返回 200
  - `deletedAt` 字段被设置: `2026-09-16T06:17:13.989909Z`
  - 页面显示文案: `该消息已删除`
  - 消息未消失，线程完整性保留

### 检查点 10: 登出断开 WS
- **操作**: 进入 `/im` 建立连接 → 点击登出
- **结果**: ✅ 通过
- **证据**:
  - 登出前 WS 连接数: 4
  - 登出后跳转到 `/login`
  - Token 清除，WS 不再重连

---

## 截图证据

- **admin 视角**: `/tmp/im-full-admin.png` (15K)
- **acl_test_user 视角**: `/tmp/im-full-acl.png` (76K)

---

## 汇总

- **通过**: 10/10
- **失败**: 0/10
- **环境受限**: 0 项
- **后端启动**: 成功
- **前端 dev**: 成功

---

## 与前期假报告的差异

前期 Cline 提交的 `im_e2e_test.mjs` 存在以下问题:
1. `import { chromium } from 'playwright'` 在 pnpm 下无法解析
2. 登录选择器错误: 使用 `input[placeholder="用户名"]`，实际为 `input[autocomplete="username"]`
3. WS 监听器在导航后注册，永远捕获不到连接
4. 所有检查点使用 `.catch(() => {})` 吞错，无真实断言
5. 新建频道检查点无条件打印成功
6. CORS 白名单限制未被考虑，使用 127.0.0.1 导致 403

本次验证使用真实断言，失败即中断，无任何错误吞没。

---

## 已知预期现象

- 告警页 `/alerts` 无实时数据: Java 侧 `collector.emit()` 未调用，遗留问题，非本次缺陷
- Redis 未启动时跨实例桥接失效: 单实例下订阅正常

---

## 结论

IM 端到端联调 **全部通过**，实时性、软删除、cursor 翻页、表情回应等核心功能均通过浏览器真实验证。

---

## 补充:本轮额外发现与修复

### 补齐 IM_REWORK.md 第五节要求的组件测试(原缺失)
`IM_REWORK.md` 第五节明确要求 `features/im/*.test.tsx` 覆盖 `MessageList` 与 `ChannelList`,但此前**零覆盖**。本轮新增:
- `frontend/src/features/im/MessageList.test.tsx` — 10 个测试(含保住项 #3:软删除渲染为「该消息已删除」)
- `frontend/src/features/im/ChannelList.test.tsx` — 10 个测试(未读角标、99+、失败降级、name 回退)

前端测试从 **159 → 179**(21 文件),全绿。

### 修复真实构建阻断缺陷(Home.tsx)
`frontend/src/pages/Home.tsx:79-80` 少写一层 `.data`:
```
unreadQuery.data?.messages        // ❌ 负载在 ApiEnvelope.data 下
unreadQuery.data?.data?.messages  // ✅ 与第 42 行一致
```
后果:`tsc -b` 报 3 个类型错误 → **`pnpm build` 直接失败**;且首页"未读站内信"面板永远显示"无未读消息"。
这与 CHANGELOG 声称的"build 成功"矛盾,系后续编辑引入的回归,已修复,现 `tsc -b` + `vite build` 均通过。

### 核实 IM_REWORK.md 第六节清理项(均已落实)
- `frontend/src/router.tsx.tmp` — 已删除 ✓
- `sendToChannel()` — 已移除,全仓无引用 ✓

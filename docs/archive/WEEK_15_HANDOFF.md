# Week 15 Handoff(视图+工作流 P1 收尾)

**周期**:Week 15  
**Sprint 目标**:把 Week 9-14 已后端化的视图/工作流 P0 + P1 故事里没补完的前端体验补全,聚焦"使用可观察"。  
**状态**:✅ 完成(3 commits + 1 docs)

---

## 📦 交付一览(4 commits)

| Commit | 内容 | 故事 |
|--------|------|------|
| `54e81fd` | US-206 列设置 | 表格视图列可见/宽度/顺序 |
| `9615592` | US-406 测试运行 + US-407 实例详情 | 工作流可观察 |
| `8799616` | US-501 Login UX + US-502 Profile 信息卡 | 登录 + 个人中心 |
| 本 docs | CHANGELOG + handoff | 阶段 4 收尾 |

---

## 🎯 5 个故事执行详情

### ✅ US-206 ViewDesigner 列设置
- **文件**: `frontend/src/pages/ViewDesigner.tsx` + `frontend/src/pages/TableView.tsx`
- **范围**: 表格视图每个字段一个 checkbox(visible) + number(width) + 上下箭头(顺序)
- **保存**: 写入 `config.columns = [{field, label, width, visible}]`,过滤掉 `visible=false` 后写入
- **渲染**: `tableLayout=fixed + colgroup` 按列宽渲染;过长 ellipsis
- **额外**: Detail 视图也支持字段勾选 + 排序(`config.fields`)

### ✅ US-406 WorkflowDesigner 测试运行
- **文件**: `frontend/src/pages/WorkflowDesigner.tsx`
- **触发**: 侧边栏蓝色按钮 "▶ 测试运行(模拟数据)"
- **弹窗**: JSON 输入 triggerData → `POST /workflows/{id}/trigger`
- **新建场景**: 自动先保存当前编辑,确保触发最新版本
- **结果**: 显示 instance id(前 8 位)+ 跳转实例详情链接

### ✅ US-407 WorkflowInstances 详情增强
- **文件**: `frontend/src/pages/WorkflowInstances.tsx`
- **核心**: 审批任务从 div → 表格,新增"耗时"列(`humanDuration()` 算 `finished_at - created_at`)
- **辅助**: triggerData 从 inline code → `details + pre`,JSON 美化 + 可折叠
- **接口**: Task 类型补 `finished_at?` / `comment?` / `assignee?` (optional)

### ✅ US-501 Login UX
- **文件**: `frontend/src/pages/Login.tsx`
- **记住用户名**: `localStorage[nocobase:login:lastUsername]`,checkbox 控制
- **错误提示**: 加左侧 border + ❌ 图标 + ✕ 关闭按钮(可手动 dismiss)
- **成功提示**: "登录成功,正在跳转…" 反馈 300ms 后跳转
- **忘记密码**: 占位链接(alert "请联系管理员"),不实现密码找回流程(留 US-501 后续)

### ✅ US-502 Profile 信息卡
- **文件**: `frontend/src/pages/Profile.tsx` + `backend-java/.../auth/AuthController.java`
- **后端**: `GET /api/auth/me` 返回 `{id, username, tenant_id, roles[], created_at}`
- **前端**: 信息卡显示用户名 + ID + 租户 + 角色徽章(admin 紫/user 蓝)+ 注册时间
- **改密码**: 原有功能保留

---

## 📊 E2E 验证(本会话)

| 用例 | 端点 | 期望 | 实际 |
|------|------|------|------|
| alice 登录 | POST /auth/login | 200 + token | ✅ 200 |
| alice /me | GET /auth/me | 含 user 角色 | ✅ `[user]` |
| admin /me | GET /auth/me | 含 admin+employee | ✅ `[admin, employee]` |
| 前端编译 | pnpm tsc | 0 新错误 | ✅ 我修改的文件 0 错误 |
| 后端编译 | mvn compile | 0 错误 | ✅ SUCCESS |
| OpenAPI paths | GET /v3/api-docs | 51 paths | ✅ 51 → **52**(/auth/me) |

---

## 📁 本会话文件清单

**修改:**
- `frontend/src/pages/ViewDesigner.tsx`(US-206 列设置 + Detail 字段顺序)
- `frontend/src/pages/TableView.tsx`(列宽渲染 + 隐藏列过滤)
- `frontend/src/pages/WorkflowDesigner.tsx`(US-406 测试运行弹窗)
- `frontend/src/pages/WorkflowInstances.tsx`(US-407 详情增强)
- `frontend/src/pages/Profile.tsx`(US-502 信息卡)
- `frontend/src/pages/Login.tsx`(US-501 记住 + 错误样式)
- `backend-java/src/main/java/com/nocobase/auth/AuthController.java`(`GET /auth/me` + 注入)

**新增:**
- `WEEK_15_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 15 段落

---

## 🚧 已知遗留(Week 16+ 候选)

- **US-501 后续**: 密码找回流程(邮箱/短信验证码)— 需先实现 email/SMS 通道
- **US-202/203 服务端 filter/sort**: 客户端已实现,后端 `listRecords` 仍读全表再前端过滤(MVP 阶段可接受,数据量大时需要)
- **字段级 + ROW write 联动**: carol 即使能 UPDATE 也不能 PUT salary 字段(需 CollectionService.putRecord 调字段级 ACL)— Week 14.5 已有新 fix 间接保证 UPDATE 不丢 created_by,但 salary 联动没单独跑 E2E
- **审计日志过滤查询**: 实现已就绪,没单独做前端 E2E
- **GitHub push 仍 SSH 不可达**: 本地仓库是最新,3 commits 待 push

---

## 🎬 下一步建议(候选)

1. **候选 B**: Week 16 — 后端 `listRecords` 加 filter/sort 服务端参数(US-202/203 真正完成)
2. **候选 C**: Week 16 — 阶段 5 提前,Java 单元测试 + 覆盖率 + 修 P0 bug
3. **候选 D**: Week 16 — 字段级 ACL + ROW write 联动(补齐最严密的 ACL 矩阵)
4. **候选 E**: 用户指定

---

**Week 15 收官。5 个 P1 故事全部基础完成;阶段 4(可视化低代码)可视为 close。下一阶段聚焦质量、可扩展性、或硬骨头(Java 测试/字段级/服务端筛选)。**
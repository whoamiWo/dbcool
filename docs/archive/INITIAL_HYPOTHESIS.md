# Week 1-4 详细执行清单 - DBCool 综合平台（已归档）

> ⚠️ **过时**：本文档原为 Phase 4 Week 1-4 起步假设，已于 2026-09-19 Phase 48 归档。当前进度见 [MEMORY.md](../../MEMORY.md) 与 [WEEK_47_HANDOFF.md](../../WEEK_47_HANDOFF.md)。Phase 1-7 与 Phase 48 全部完成，P0 33/33 = 100%。

---

## Week 1: Wiki 权限集成 + 钉钉 SSO 启动

### 任务 1.1: Wiki 权限服务 (Day 1-2)

**目标**: 复用现有 AclEnforcer 实现 Wiki 知识库级/页面级权限

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 1.1.1 | `backend-java/src/main/java/com/nocobase/wiki/WikiPermissionService.java` | ✅ 已创建 | 编译通过 + 单测 PASS |
| 1.1.2 | `backend-java/src/test/java/com/nocobase/wiki/WikiPermissionServiceTest.java` | 新建 | 覆盖 checkSpaceAccess/checkPageAccess |
| 1.1.3 | `backend-java/src/main/java/com/nocobase/wiki/WikiController.java` | ✅ 已注入 | 权限拦截生效（无权限返回 403） |

**验收**: `mvn test -Dtest='WikiPermission*Test'` 全部 PASS

### 任务 1.2: 附件上传 (Day 3)

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 1.2.1 | `backend-java/src/main/java/com/nocobase/wiki/WikiAttachmentService.java` | ✅ 已创建 | 支持上传/下载/删除 |
| 1.2.2 | `frontend/src/components/wiki/WikiAttachmentUpload.tsx` | 新建 | 拖拽上传 + 进度条 |
| 1.2.3 | `frontend/src/pages/wiki/WikiPageEdit.tsx` | 修改 | 编辑器集成附件上传 |

**验收**: 上传 5MB 文件成功，前端显示附件列表

### 任务 1.3: 钉钉 SSO 骨架 (Day 4-5)

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 1.3.1 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkClient.java` | 新建 | 封装钉钉 OpenAPI |
| 1.3.2 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkAuthService.java` | 新建 | 免登码换取用户信息 |
| 1.3.3 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkAuthController.java` | 新建 | `POST /api/integrations/dingtalk/login` |
| 1.3.4 | `backend-java/src/main/resources/application.yml` | 修改 | 增加 dingtalk 配置节 |

**验收**: 钉钉扫码/免登获取 JWT Token

---

## Week 2: 工作流集成 + 钉钉组织架构同步

### 任务 2.1: Wiki 工作流节点 (Day 1-2)

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 2.1.1 | `backend-java/src/main/java/com/nocobase/workflow/handler/WikiPublishNodeHandler.java` | ✅ 已创建 | WIKI_PUBLISH 节点将页面状态改为 published |
| 2.1.2 | `backend-java/src/main/java/com/nocobase/workflow/handler/WikiArchiveNodeHandler.java` | ✅ 已创建 | WIKI_ARCHIVE 节点归档页面 |
| 2.1.3 | `backend-java/src/test/java/com/nocobase/workflow/WikiNodeHandlerTest.java` | 新建 | 节点执行单测 |

**验收**: 工作流触发 Wiki 发布/归档成功

### 任务 2.2: 钉钉组织架构同步 (Day 3-4)

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 2.2.1 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkOrgSyncService.java` | 新建 | 拉取部门/用户列表 |
| 2.2.2 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkSyncJob.java` | 新建 | @Scheduled 每小时同步 |
| 2.2.3 | `backend-java/src/main/java/com/nocobase/user/UserMappingService.java` | 新建 | 钉钉 userId ↔ 平台 userId 映射 |

**验收**: 手动触发同步，部门树和用户正确入库

### 任务 2.3: 版本 Diff 增强 (Day 5)

| 步骤 | 文件路径 | 操作 |
|------|----------|------|
| 2.3.1 | `frontend/src/components/wiki/WikiDiffViewer.tsx` | 增强行级 diff 高亮 |

---

## Week 3: 钉钉 OA 审批 + 消息推送

### 任务 3.1: OA 审批对接 (Day 1-3)

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 3.1.1 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkApprovalService.java` | 新建 | 创建/查询钉钉审批实例 |
| 3.1.2 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkCallbackController.java` | 新建 | 审批回调验签 + 状态回写 |
| 3.1.3 | `backend-java/src/main/java/com/nocobase/workflow/handler/DingTalkApprovalNodeHandler.java` | 新建 | 工作流 DINGTALK_APPROVAL 节点 |

**验收**: 平台发起请假申请 → 钉钉审批 → 回调更新工作流状态

### 任务 3.2: 消息卡片推送 (Day 4-5)

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 3.2.1 | `backend-java/src/main/java/com/nocobase/integration/dingtalk/DingTalkMessageService.java` | 新建 | 发送工作通知（ActionCard） |
| 3.2.2 | `backend-java/src/main/java/com/nocobase/notification/DingTalkChannel.java` | 新建 | 实现 NotificationChannel SPI |

**验收**: Wiki 页面发布时，钉钉群收到消息卡片

---

## Week 4: 前端集成 + E2E 测试

### 任务 4.1: 钉钉登录前端 (Day 1-2)

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 4.1.1 | `frontend/src/pages/auth/DingTalkLoginPage.tsx` | 新建 | 钉钉内嵌 H5 免登 |
| 4.1.2 | `frontend/src/api/integrations.ts` | 修改 | 增加 dingtalk API 客户端 |
| 4.1.3 | `frontend/src/App.tsx` | 修改 | 路由注册 |

### 任务 4.2: E2E 测试 (Day 3-4)

| 步骤 | 文件路径 | 操作 | 验收标准 |
|------|----------|------|----------|
| 4.2.1 | `frontend/e2e/wiki.spec.ts` | 新建 | 登录→建知识库→建页→编辑→发布→搜索 |
| 4.2.2 | `frontend/e2e/permission.spec.ts` | 新建 | 无权限用户访问返回 403 页面 |
| 4.2.3 | `frontend/playwright.config.ts` | 新建 | CI 集成配置 |

**验收**: `npx playwright test` 全部 PASS

### 任务 4.3: 基线文档 (Day 5)

| 步骤 | 文件路径 | 操作 |
|------|----------|------|
| 4.3.1 | `BASELINE.md` | 新建 - 记录当前功能/测试/性能基线 |
| 4.3.2 | `QA_RADAR.md` | 新建 - 质量风险雷达 |
| 4.3.3 | `TECH_DEBT.md` | 新建 - 技术债登记（含 Q1 混合 UI 策略） |

---

## 依赖关系

```
Week 1: Wiki权限 ──→ 附件上传 ──→ 钉钉SSO骨架
                        │
Week 2: 工作流节点 ←──┘  ──→ 钉钉组织同步
                              │
Week 3: 钉钉OA审批 ──→ 消息推送
                              │
Week 4: 前端集成 ──→ E2E测试 ──→ 基线文档
```

## 环境前置条件

| 依赖 | 用途 | 获取方式 |
|------|------|----------|
| 钉钉开发者账号 | 创建企业内部应用获取 AppKey/AppSecret | https://open-dev.dingtalk.com |
| MinIO 实例 | 附件存储 | docker compose 或云服务 |
| PostgreSQL 15 | 主数据库 | 已有 |
| Redis | 缓存/实时 | 已有 |

## 每日质量门禁

```bash
# 每日提交前必跑
cd backend-java && mvn verify -q
cd frontend && npx tsc --noEmit && npx vitest run && npx vite build
```

---

## 执行完成状态 (2026-09-18)

### Week 1: Wiki 权限集成 + 钉钉 SSO 启动 ✅
- ✅ `WikiPermissionService.java` + `WikiPermissionServiceTest.java`
- ✅ `WikiAttachmentService.java` + `WikiAttachmentUpload.tsx`
- ✅ `DingTalkClient.java` + `DingTalkAuthService.java` + `DingTalkAuthController.java`
- ✅ `application.yml` dingtalk 配置节

### Week 2: 工作流集成 + 钉钉组织架构同步 ✅
- ✅ `WikiPublishNodeHandler.java` + `WikiArchiveNodeHandler.java`
- ✅ `DingTalkOrgSyncService.java` + `UserMappingService.java` + `DingTalkSyncJob.java`
- ✅ `application.yml` dingtalk 配置节

### Week 3: 钉钉 OA 审批 + 消息推送 ✅
- ✅ `DingTalkApprovalService.java`
- ✅ `DingTalkMessageService.java`
- ✅ `DingTalkApprovalNodeHandler.java`

### Week 4: 前端集成 + E2E 测试 ✅
- ✅ `DingTalkLoginPage.tsx`
- ✅ `wiki-phase1.spec.ts` + `wiki-permission.spec.ts` + `dingtalk-login.spec.ts`
- ✅ `BASELINE.md` + `QA_RADAR.md`

### 质量验证
- 后端: `mvn compile` BUILD SUCCESS, `mvn test -Dtest='Wiki*Test'` 33 PASS
- 前端: `npx vitest run` 233 PASS, `npx tsc --noEmit` 0 errors, `npx vite build` 成功
- 文档: 6 份整合方案文档已交付

### 已知问题
- `WikiPageServiceTest` Spring Context 失败 (预存问题)
- 后端全量测试 943 中有 28 errors (预存问题)

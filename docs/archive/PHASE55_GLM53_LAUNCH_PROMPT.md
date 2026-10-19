# Phase55 企业级综合平台上线攻坚（GLM-5.3 执行版）

> 编排方：CodeBuddy(HY4)　执行方：**GLM-5.3**　复审计方：CodeBuddy
> 生成时间：2026-09-25
> 基线提交：`2867839`（工作区 clean，已 push origin/main）
> 前置：`PHASE53_GLM53_PROMPT.md`(W1–W6)、`PHASE54_GLM53_REWORK_PROMPT.md`(R1–R9 返工，已全部完成并审计通过)
> 迁移版本号：**从 V36 起**（V35=`livechat_ticket.sql` 已占用，已核实）

---

## 0. 目标与定位

在**现有代码基础上**（不推倒重来），整合八款产品优点，演进为**面向企业的综合协同平台**，并完成从"功能原型"到"可生产上线"的跨越。

- **NocoBase / NocoDB / Airtable** → 低代码数据底座 + 多视图 + 公式/汇总/关联
- **Notion** → 知识库 + 块编辑 + 双向链接 + 协同
- **Slack / RocketChat / Mattermost** → IM + 剧本 + 音视频 + 客服
- **Trello** → 看板 + 卡片 + 任务
- **钉钉 / 企业微信** → 扫码登录 + 事件回调 + 消息推送 + 组织同步（**核心卖点**）

**判定：功能原型完备，但当前不可直接上线。** 完成本提示词的 P0 三类阻塞后可达到上线标准。

---

## 1. 现有平台能力盘点（真实代码证据）

### 1.1 规模

| 指标 | 数值 |
|---|---|
| 后端 Java 源文件 | 306 个 |
| Controller | 47 个（45 REST + 2 STOMP） |
| REST 端点 | **288 个** |
| STOMP 端点 | 6 个 |
| 前端页面 | 64 个（pages）+ features（bi/collection/im/project/realtime） |
| Java 测试文件 | 111 个 |
| Python | 13 routers / 19 services（connectors + LLM） |

### 1.2 模块完成度（三档）

**✅ 可生产（17 模块，测试成体系）**
`meta`(20端点/13测试)、`workflow`(16/23)、`wiki`(30/6)、`auth`(25/9)、`acl`(5/2)、`tenant`(7/4)、`project`(29/1)、`view`+`form`(11/4)、`notification`(8/6)、`apikey`(3/3)、`webhook`(4/2)、`alert`(7/6)、`audit`+`health`(4/4)、`search`(1/2)、`plugin`(7/3)

**⚠️ 演示级骨架（4 模块，约 49 端点测试薄弱）**

| 模块 | 端点 | 测试 | 问题 |
|---|---|---|---|
| `integration` | 32 | **仅 2** | slack/wecom/feishu/mattermost/market 共 **26 端点零测试**，仅 dingtalk 有 2 个 |
| `automation` | 8 | **0** | Service 15KB 实现，零测试 |
| `ldap` | 5 | **0** | 占位 URL `ldap://localhost:389` |
| `attachment` | 4 | 2 | MinIO 实现完整但 `enabled` 默认 `false`，3 端点返 501 |

**❌ 桩代码（见 §6）**：`im` Huddle、`ticket` Livechat、`wiki` 附件、`meta` ALTER_TYPE

### 1.3 已有底座能力
- Redis ✅（STOMP 桥接 / Presence / Refresh Token）
- 事务 ✅（`@Transactional` 广泛使用）
- 多租户 ✅（schema 级隔离）
- WebSocket/STOMP ✅（im / realtime / alert 三套）
- 对象存储 ⚠️（MinIO 实现完整但默认关闭）
- **消息队列 ❌ 完全缺失**（见 §3 P0-2）

---

## 2. 对标八款产品的能力矩阵（现有覆盖度）

| 对标产品 | 核心能力 | 现有落点 | 覆盖 | 缺口 |
|---|---|---|---|---|
| **NocoBase** | 插件化低代码、数据模型、视图、工作流、权限 | `meta`+`view`+`form`+`acl`+`workflow`+`plugin`(SPI) | ✅ | 插件生态稀薄 |
| **NocoDB** | 数据库即表格、多视图、自动 API | `meta` 引擎 + 表格/看板/日历/画廊/详情/时间线 | ✅ | SQL 视图 / API 自动生成待确认 |
| **Airtable** | 字段类型、公式/汇总/关联、自动化 | Formula/Rollup/Lookup 引擎 + `automation`(8端点) | ⚠️ | `automation` **0 测试**；公式字段 UI 薄 |
| **Notion** | 文档/知识库、块编辑、双向链接、协同 | `wiki`(30端点/14页面/版本历史/搜索/blocks/双向链接) + `realtime` | ⚠️ | 附件 2 处桩；协同 CRDT **仅透传**非服务端合并 |
| **Slack** | 频道/DM/线程/表情回应/Slash/搜索 | `im`(37端点 + Reaction/Pin/Burn/Slash) | ⚠️ | Huddle 桩；线程回复待确认 |
| **RocketChat** | 音视频、Omnichannel 客服 | `ticket` Livechat | ❌ | 消息**直接丢弃**、客户邮箱伪造 |
| **Mattermost** | Playbook 剧本、合规导出 | `playbook`(12端点/16KB Service) | ⚠️ | Service **无测试** |
| **Trello** | 看板、卡片、清单、标签、截止日期 | `KanbanView` + `project`(29端点) + `MyTasks` | ⚠️ | 卡片清单/标签/截止日期待增强 |
| **钉钉/微信** | 扫码登录、事件回调、消息推送、组织同步 | `integration`(32端点) + `dingtalk` 前端页 + Python connectors | ⚠️ | **26 端点零测试**；签名校验未补齐 |

---

## 3. 距上线评估：三类 P0 阻塞

### P0-1 安全 fail-open（最危险，生产越权风险）

| 文件 | 行 | 问题 |
|---|---|---|
| `auth/AclEnforcer.java` | 70 | `return true; // 无 policy 配置 → 默认允许` |
| `auth/AclEnforcer.java` | 77 | `return true; // 只有 FIELD/ROW policy → 默认允许` |
| `auth/AclEnforcer.java` | 253 | `catch (Exception ignored) {}` 鉴权异常静默放行 |
| `acl/RowAclService.java` | 126 | `return true; // 无策略 = 放行` |
| `auth/keystore/KeyRingService.java` | 68-70 | dev 占位 secret，生产部署前必须替换 |
| `config/SecurityConfig.java` | 54 | `// 签名校验作为加固项后续补齐` |
| `audit/AuditService.java` | 58 | `catch (Exception ignored) {}` 审计丢失无告警 |

### P0-2 零消息队列（异步失败即丢失，数据一致性无保障）

- 全仓 `RabbitTemplate|KafkaTemplate|@JmsListener|amqp` = **0 命中**，pom.xml 无 MQ 依赖
- 异步全靠 `config/AsyncConfig.java` 的 `@Async` 线程池
- `webhook/WebhookSubscriptionService.java:28`：「不做重试队列 —— 重试与死信留待 Week 42+」→ 至今未做
- 影响：迁移、AI、组织同步、webhook 投递失败即永久丢失，无补偿

### P0-3 集成能力未验证（核心卖点最不可信）

- `integration` 模块 32 端点，**仅 dingtalk 有 2 个测试**
- slack / wecom / feishu / mattermost / market 共 **26 端点零测试**
- 回调签名校验未落地 → 伪造事件可注入

**次要（P1/P2）**：桩代码（§6）、`attachment` 默认关闭、对标能力补齐、E2E firefox 未复验。

---

## 4. 全局红线（沿用项目既有 + 本轮强约束）

1. ❌ 禁 `log.info` + `// TODO` 冒充接真；未配置外部服务须返**明确错误**（禁空 catch 吞异常）
2. ❌ 禁臆造 API：调用前用 code-explorer / lsp 确认真实存在与**参数签名**
3. ❌ 禁删测试 / `it.skip` / `it.todo` / 弱化断言（反作弊闸门须 **0/0**）
4. ❌ 禁在生产代码加测试专用分支（**禁 `__isE2E__`、`baseURL=''`**）—— Phase54 已因此返工
5. ❌ 禁改 `vite.config.ts` 的 `pool:'forks' + isolate:true`；禁改 `src/test-setup.ts` 打补丁
6. ❌ 鉴权相关**一律 fail-closed**：无策略=拒绝，异常=拒绝+告警（不得 `return true` 放行）
7. ✅ 迁移版本号**从 V36 起**
8. ✅ 前端禁硬编码亮色，一律 `var(--color-*)` + MUI `sx`
9. ✅ 分栈提交：`[java]` / `[python]` / `[js]`，每阶段附**实测门禁数据**

---

## 5. 六阶段执行任务

### 阶段 1：安全红线清零（P0，最高优先级）

**目标**：把所有 fail-open 改 fail-closed，消除静默吞异常与 dev 密钥。

| # | 文件 | 改动 |
|---|---|---|
| 1.1 | `auth/AclEnforcer.java` | L70/L77 无 policy → **`return false`**（默认拒绝）；L253 `catch (Exception ignored)` → 记 ERROR 日志 + `return false` |
| 1.2 | `acl/RowAclService.java` | L126 无策略 → **`return false`** |
| 1.3 | `auth/keystore/KeyRingService.java` | L68-70 dev 占位 secret → **启动时强校验**，检测到 dev secret 直接启动失败 |
| 1.4 | `config/SecurityConfig.java` | L54 补齐入站请求签名校验 |
| 1.5 | `audit/AuditService.java` | L58 去静默吞异常，改 `logger.error("审计写入失败", e)` |

**验收**：`grep -rn "return true; // 无\|catch (Exception ignored)"` 于 auth/acl 包 **0 命中**；新增 fail-closed 单测（无策略访问被拒）；`mvn test` 全绿。

### 阶段 2：MQ 底座（P0）

**目标**：引入 RabbitMQ，让异步任务失败可补偿。

- 2.1 `pom.xml` 加 `spring-boot-starter-amqp`；`docker-compose.yml` 加 rabbitmq 服务
- 2.2 新建 `config/AmqpConfig.java`：业务 exchange + 重试队列（带 TTL/退避）+ 死信队列(DLX) 绑定
- 2.3 `webhook/WebhookSubscriptionService` 投递改走 MQ，实现重试次数上限 + 指数退避 + 超限进死信
- 2.4 迁移(`AsyncMigrationService`)、组织同步、AI 等 `@Async` 任务改走 MQ，保证失败可重放
- 2.5 新建 `V36__async_task.sql`：任务状态表（`id/type/payload/status/retry_count/next_retry_at/error/created_at`）

**统一契约**（签名以实现为准，勿臆造）：
```java
public interface AsyncTaskPublisher { void publish(AsyncTask task); }
public interface AsyncTaskHandler { void handle(AsyncTask task); int maxRetries(); }
```

**验收**：webhook 投递失败可重试并可查死信；`V36` 迁移通过；`mvn test` 全绿；docker-compose 含 rabbitmq 且 healthcheck 通过。

### 阶段 3：集成接真（P0，核心卖点）

**目标**：让"嵌入钉钉/微信"从骨架变可信。

- 3.1 为 slack/wecom/feishu/mattermost/market 的 **26 个零测试端点**补契约测试 + 集成测试
- 3.2 补齐回调**签名校验**（fail-closed）：钉钉、企微、飞书、Slack、Mattermost 各平台按官方算法
- 3.3 端到端验证链路：**扫码登录 → 事件回调 → 消息推送 → 组织同步**
- 3.4 Python connectors（`services/connectors/*.py`）签名校验与接真复核

**验收**：26 个端点测试覆盖率 > 0 且关键路径有断言；伪造签名请求返 401；`mvn test` 全绿。

### 阶段 4：桩代码清零（P1）

| # | 文件 | 行 | 现状 | 改动 |
|---|---|---|---|---|
| 4.1 | `im/HuddleSignalingController.java` | 21-43 | 3 个 STOMP 端点 `log.info` + 硬编码返回 | 实现房间管理 + 鉴权；或**明确下线**（删端点+前端入口） |
| 4.2 | `ticket/TicketController.java` | 28,40,52 | 消息丢弃、伪造邮箱、无会话存储 | 消息持久化到会话表，真实客户标识 |
| 4.3 | `wiki/WikiAttachmentService.java` | 47,84 | `NOT_IMPLEMENTED` | 接 MinIO 真实实现 |
| 4.4 | `meta/AsyncMigrationService.java` | 109 | `ALTER_TYPE` 抛 `UnsupportedOperationException` | 支持改类型，或明确不支持并返友好错误 |
| 4.5 | `attachment` | - | MinIO `enabled` 默认 `false`，3 端点返 501 | 默认启用对象存储 |

**验收**：全仓 `NOT_IMPLEMENTED|UnsupportedOperationException|log.info.*TODO` 在业务路径 **0 命中**；上传/下载附件端到端可用。

### 阶段 5：对标能力补齐（P2）

- 5.1 **Airtable**：公式/汇总字段 UI + 测试（Formula/Rollup/Lookup 引擎已存在，先确认真实签名）
- 5.2 **Trello**：`project`/`KanbanView` 卡片清单、标签、截止日期、附件
- 5.3 **Slack**：`im` 线程(thread)回复 + 搜索增强
- 5.4 **Notion**：`realtime` 协同由 CRDT 透传改为**服务端合并**
- 5.5 **Mattermost**：`playbook` Service(16KB) 补测试

**验收**：每项有对应单测/E2E；前端 `tsc --noEmit` 0 errors。

### 阶段 6：上线验证（P0/P1）

- 6.1 多租户隔离复验（跨租户越权测试）
- 6.2 备份/恢复演练（`backend-python/services/backup.py` 已有 fail-closed，补演练记录）
- 6.3 性能压测（BASELINE 要求 P95<200ms / P99<500ms）
- 6.4 **E2E 补跑 firefox**（当前仅 chromium 32 passed，基线全量口径为双浏览器）
- 6.5 部署文档 + 用户手册（`USER_GUIDE.md`）
- 6.6 全栈门禁：`tsc --noEmit` 0 / `vitest` 250 passed / `mvn test` 全绿 / pytest

---

## 6. 桩代码清单（必须清零）

| 文件 | 行 | 证据 |
|---|---|---|
| `im/HuddleSignalingController.java` | 21 | `// TODO: 解析 JSON 获取 roomId，验证用户身份，加入房间` |
| 同上 | 22-23 | `log.info("Huddle join: {}", payload); return "{\"type\":\"joined\"};` |
| 同上 | 32-33 | `handleLeave` 同上硬编码 |
| 同上 | 42-43 | `handleSignal` 直接 `return payload` 原样回显 |
| `ticket/TicketController.java` | 40 | `// 消息暂存` → **消息内容被完全丢弃** |
| 同上 | 52 | `user.username() + "@nocobase.local"` 伪造客户邮箱 |
| 同上 | 28 | `UUID.randomUUID()` 会话 ID，无会话存储 |
| `attachment/AttachmentController.java` | 92 | `throw ... NOT_IMPLEMENTED` (getMetadata) |
| 同上 | 107,142 | 未启用对象存储 → 501（download/metadata） |
| `wiki/WikiAttachmentService.java` | 47,84 | `NOT_IMPLEMENTED` |
| `meta/AsyncMigrationService.java` | 109 | `throw new UnsupportedOperationException("Week 8+ 支持")` |
| `im/SlashCommandInitializer.java` | 74-88 | `/poll`、`/code` 回显占位「待后续迭代」 |
| `im/SlashCommandRegistry.java` | 57-61 | 5 个 `{ /* 占位 */ }` 空 handler |
| `attachment/AttachmentMetadata.java` | 22 | `storageKey 为占位值` |

## 7. 其他待补齐（非桩，但需处理）

| 文件 | 行 | 问题 |
|---|---|---|
| `webhook/WebhookSubscriptionService.java` | 28 | 不做重试队列、无死信 |
| `ldap/LdapTemplateConfig.java` | 17 | 占位 URL `ldap://localhost:389` |
| `workflow/WorkflowTriggerListener.java` | 26 | `本 Step G2 暂未实现,留给 D4a.3` |
| `im/SlashCommandInitializer.java` | 58 | `catch (NumberFormatException ignored) {}` |

---

## 8. 交付自检清单（逐阶段勾选）

**阶段 1（安全）**
- [ ] `AclEnforcer` / `RowAclService` 无策略时**拒绝**而非放行
- [ ] 鉴权异常 `catch` 改为 ERROR 日志 + 拒绝（非 `ignored`）
- [ ] dev secret 启动时强校验（检测到即启动失败）
- [ ] `SecurityConfig` 签名校验已落地
- [ ] `AuditService` 审计失败有 ERROR 告警
- [ ] `mvn test` 全绿，新增 fail-closed 单测

**阶段 2（MQ）**
- [ ] `pom.xml` + `docker-compose.yml` 含 RabbitMQ
- [ ] `AmqpConfig` 配置重试队列 + 死信队列
- [ ] webhook 投递失败可重试、超限进死信
- [ ] `V36__async_task.sql` 迁移通过
- [ ] `mvn test` 全绿

**阶段 3（集成）**
- [ ] 26 个零测试端点均有测试
- [ ] 五平台回调签名校验 fail-closed（伪造签名 → 401）
- [ ] 扫码登录→事件回调→消息推送→组织同步 端到端通过

**阶段 4（桩清零）**
- [ ] Huddle 真实化或明确下线
- [ ] Livechat 消息持久化，禁伪造邮箱
- [ ] wiki 附件接 MinIO
- [ ] `ALTER_TYPE` 支持或友好报错
- [ ] `attachment` 默认启用，上传/下载可用

**阶段 5/6（对标 + 上线）**
- [ ] 公式字段 UI、Trello 卡片、Slack 线程、协同合并、Playbook 测试
- [ ] 多租户越权测试通过
- [ ] 备份恢复演练记录
- [ ] 压测 P95<200ms
- [ ] **E2E firefox 补跑**（全量双浏览器达标）
- [ ] `tsc` 0 / `vitest` 250 / `mvn test` 全绿 / pytest
- [ ] 反作弊闸门 0/0（无 skip / 删测试 / 弱化断言）

---

## 9. 提交规范

分栈提交：`[java]` / `[python]` / `[js]`。

提交信息须写明：**修复根因**（如「fail-open → fail-closed」）、**实测门禁数据**（passed/failed 具体数字）、**反作弊闸门结果**。

每个阶段完成后回报三项：
1. 本阶段改了哪些文件（路径 + 关键行变化）
2. 实测门禁数据（mvn / vitest / playwright 的 passed/failed）
3. 对标产品的哪项能力因此落实

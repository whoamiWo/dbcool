# PHASE92 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：租户配额与计量（🔒-2）

## 项目环境速查（前人踩过的坑，直接照做）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| **一键冒烟（真调后端）** | `python3 scripts/smoke.py`（11 条链路，426ms） |
| **压测** | `docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`（**离线镜像本地已有，别说"没装"**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |

---

## §1 为什么做这个

来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md:389`：

> 🔒-2 **租户配额与计量**：存储/API 调用/席位配额、用量计量（当前无）

四条理由：

1. **有数据基础**：PHASE88 实测单机可用容量 **827 req/s**、拐点 **350 VU**
   （超过则 p95 劣化 9 倍）—— 这正是"每个租户能分多少"的依据。
2. **这是可用性问题，不只是商业化需求**：没有配额，
   **一个租户的批量任务就能拖垮所有租户**。
3. **当前全空白**（实测）：`TenantEntity` 只有
   `id/name/slug/status/schemaName/createdAt`，**无配额字段**；无计量；
   现有 `GlobalRateLimiter` 按接口/用户限流，**未按租户**。
4. **纯代码可做、可验证**：对比 🔒-3（需 Vault）、🔒-7（需异地存储）、
   🔒-9（需 K8s）—— 那些在离线环境只能纸上完成。本项能真做完、真验证。

## §2 可复用的设施（别重造）

| 设施 | 位置 | 用于 |
|---|---|---|
| `GlobalRateLimiter` | `ratelimit/GlobalRateLimiter.java` | **API 配额**：`allowRequest(key, limit, windowSeconds)`，已支持 Redis + Memory 降级 |
| `UserTenantEntity` | `tenant/UserTenantEntity.java` | **席位**：按 tenantId 统计用户数 |
| 附件存储（MinIO） | `attachment/` | **存储用量**：统计租户对象总大小 |
| `AuditService` | `audit/AuditService.java` | 配额变更 / 超限事件留痕（复用 PHASE83–85 成果） |

> ⚠️ `GlobalRateLimiter` 的 Redis key 前缀是 `ratelimit:`，
> 租户配额建议用**独立前缀**（如 `quota:`）避免与接口限流键冲突。

## §3 五项任务

### T1（P0）配额模型 + 迁移

- 给租户加配额字段（建议：API 调用/分钟、存储上限、席位上限）
- **必须写新迁移 `V51__…`（或当前最大版本 +1）**
  —— **严禁修改已应用的迁移**（`V28`/`V41` 等，本项目因此返工过）
- 提供默认值，保证**存量租户不被"零配额"卡死**

### T2（P0）三类配额 enforcement

| 配额 | 触发点 | 超限行为 |
|---|---|---|
| **API 调用** | 请求进入时按 `tenantId` 计数 | 返回 **429** + 明确提示（哪个配额、何时恢复） |
| **存储** | 附件上传时校验累计用量 | 拒绝上传，返回明确错误 |
| **席位** | 用户加入租户时校验 | 拒绝加入 |

**关键**：超限一律**拒绝**（fail-close），**不得静默放行**（与 🔒-6 一致）。

### T3（P0）计量与查询

- 记录用量（独立计量存储，或复用 Redis 计数）
- 提供查询接口：当前租户各项用量 / 配额 / 余量
- 超限事件建议复用 `AuditService` 留痕（可追溯）

### T4（P1）管理入口

查看/设置租户配额的接口（平台管理员）；前端展示不强求，至少接口可查。

### T5（P0）验证 —— 本批验收核心

三条**必须都能演示**：

1. **API 配额**：用 k6 压到阈值 → 断言请求被 **429** 拦住
   （可复用 `perf/load-test.js` 的模式）
2. **存储配额**：构造超限上传 → 断言被拒绝
3. **席位配额**：加到上限后继续加 → 断言被拒绝

> **配额没被验证过就等于没有** —— 和审计器、冒烟一个道理，
> 必须演示"触发超限 → 真的被拒"。做不到这一点的配额 = 又一个摆设。

## §4 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1400** |
| `cd frontend && npm run test:run` | **> 382**（前端没改就如实写 382） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **> 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §5 红线（违反即打回）

1. **严禁修改已应用的迁移文件**（`V28`/`V41` 等）—— 变更一律写 **新迁移**
2. **严禁超限静默放行** —— 必须拒绝 + 明确提示
3. **严禁为跑通而禁用配额**（与 PHASE81"放宽规则"同款红线）
4. **严禁伪造压测/验证结果** —— 贴真实输出
5. **严禁让存量租户因"零配额"直接不可用** —— 必须有合理默认值

## §6 交付清单（缺一项视为未完成）

1. 配额字段与迁移文件（含版本号、说明为什么是新迁移）
2. 三类配额的 enforcement 位置（文件:行号）+ 超限行为
3. 计量查询接口的用法示例
4. **三条验证证据**（API 429 / 存储拒绝 / 席位拒绝）—— T5
5. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净且已推送**）

## §7 背景资料（不用重新找）

- 容量基线：`BASELINE.md` 第八节（827 req/s @ 350 VU 为可用上限）
- 限流变量：`RATELIMIT_IM_LIMIT` / `RATELIMIT_FILEUPLOAD_LIMIT`
  在 `docker-compose.yml`，**默认值保持生产值**，压测时用 shell 变量临时覆盖
- 改完可跑 `python3 scripts/smoke.py` 立刻确认没把正常链路搞坏

-----END PROMPT-----

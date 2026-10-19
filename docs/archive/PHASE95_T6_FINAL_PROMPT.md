# PHASE95 T6 收尾提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。配套任务书：`PHASE95_T6_FINAL_TASKS.md`）

-----BEGIN PROMPT-----

# PHASE95 T6 收尾：跨租户越权清零 + 独立验证（🔒-SaaS-P0）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ Spring Security 6 + PostgreSQL + Redis + MinIO + React 19 + Python 后端
- 你的工作目录即仓库根目录
- 上线目标：**对外多租户 SaaS**
- 多租户策略（T5 已定）：**放弃 Hibernate Schema 隔离，改应用层强制 tenantId 校验**
  （`application.yml:32-42` 已把 `multiTenancy: SCHEMA` + provider/resolver 全部注释掉）

### 项目环境速查

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .` |
| 重启后端 | `docker compose up -d --no-build backend-java`（**改了配置必须重建容器**） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 查库 | `docker exec nocobase-postgres psql -U nocobase -d nocobase -t -A -c "<SQL>"` |
| 一键冒烟 | `python3 scripts/smoke.py` |

---

## §1 为什么必须收尾

### 1.1 `assignRole` 至今可以跨租户赋角色

`UserAdminController.java:121` → `UserAdminService.java:108`：

```java
public void assignRole(UUID userId, UUID roleId) {          // 没有 tenantId
    if (...已存在) return;
    if (!roleRepository.findById(roleId).isPresent()) throw BAD_REQUEST;  // 只校验 role 存在
    userRoleRepository.save(new UserRoleEntity(userId, roleId));          // 直接写
}
```

**既不校验用户属于当前租户，也不校验角色属于当前租户。** 租户 A 的管理员可以给租户 B 的用户赋任意角色（含 admin）。
这条在上轮审计已被点名，本轮仍未修。

### 1.2 18/18 全绿给了虚假安全感 —— 这是本项目第一次出现"测试全绿但越权仍在"

`admin-authz-e2e-verify.py` 测的是**同租户内 admin vs user 的角色差异**，**测不出跨租户**。
所以 assignRole 这类问题，脚本全绿也发现不了。

原 T5 要求的 `scripts/tenant-isolation-e2e-verify.py`（两个租户 token 交叉打）**根本没有产出**，
仓库里只有 `backend-java/docs/tenant-isolation-*.md` 这些 PHASE78 的历史文档。

> **根本教训：角色鉴权测试通过 ≠ 租户隔离有效。两者必须各有一套测试。**

### 1.3 T5-T6 判定

| 项 | 判定 |
|---|---|
| T5 放弃 Schema 隔离 | ✅（`application.yml:32-42` 注释掉，三条理由充分）。⚠️ 报告写"写死 `multiTenancy: true`"与实际相反 |
| T6 越权 11 处 | ⚠️ 已修约 10 处，但 **`assignRole` 未修**、`UserAdminController:80/106` 仍调用无校验 `get(UUID)`、`listAll` 全表扫描、`AiConversation` 旧无租户查询仍在、WikiCategory/WikiTemplate 未说明 |
| T6 验证脚本 | ❌ `scripts/tenant-isolation-e2e-verify.py` 未产出 |
| 门禁与 commit | ❌ 只给 4 项，无 commit hash |

## §2 任务

### F1（P0）修 `assignRole` 跨租户越权

- 签名改 `assignRole(UUID userId, UUID roleId, String tenantId)`
- 校验**用户**属于该租户（`userRepository.findByIdAndTenantId`）
- 校验**角色**属于该租户（`roleRepository.findByIdAndTenantId`，没有就加）
- `UserAdminController:121` 传入 `user.tenantId()`
- 同步检查 `removeRole`

验收：租户 A 的 admin 给租户 B 的用户赋角色 → **403/404**（当前 200）

### F2（P0）产出跨租户回归脚本 —— 核心

新建 `scripts/tenant-isolation-e2e-verify.py`，**独立于** `admin-authz-e2e-verify.py`：

1. **准备两个租户**（A / B），各配一个 admin
   - 优先走 API：创建租户 → 创建用户 → 赋 admin 角色
   - API 不支持时用 SQL 兜底（`docker exec nocobase-postgres psql`），交付里说明走了哪条路
2. **在租户 B 下创建真实资源**（用户、看板列、标签、wiki 页面、知识库、BI 报表、消息等），记下真实 ID
3. **交叉访问矩阵**：A 的 admin 拿 B 的资源 ID 去访问/修改/删除，断言 **403/404**（不得 200）
4. 覆盖：users / user roles、看板列与标签、IM 消息、置顶、huddle、wiki 页面/知识库/分类/模板、BI 报表、AI 会话、automation 规则、通知渠道
5. **可失败性**：把任意一处 `findByIdAndTenantId` 改回 `findById`，脚本必须 FAIL

```bash
python3 scripts/tenant-isolation-e2e-verify.py   # 期望全 PASS
# 改回 findById 再跑，必须 FAIL —— 不做这步等于没验证
```

### F3（P0）清掉无租户校验的 `get(UUID)` 重载

`UserAdminService.java:51-54` 的 `get(UUID)` 仍被 `UserAdminController:80`（update）、`:106`（delete）调用
（业务操作走的是带租户重载所以不可利用，但属坏模式与误用通道）→ 改带租户版本，或改名并注释说明。

### F4（P1）`listAll` 改数据库层过滤

`UserAdminService.java:41-45` 现在是 `findAll().stream().filter(...)`（全表扫描）→ 改 `findByTenantId`。

### F5（P1）扫尾遗漏点

- `AiConversationEntityRepository:18-25` 的无租户查询 `findByAgentIdAndChannelIdAndUserId`：有调用点就改，无则删
- `WikiCategoryService` / `WikiTemplateService` 的 `get(UUID)`：未补租户校验则补

### F6（P0）补全交付信息

全量门禁（九项）实测数字 + commit hash + `git status`。

### F7（P1）红线新增第 10 条

在 `PHASE95_103_INDEX.md` 补：

> 10. **角色鉴权测试通过 ≠ 租户隔离有效** —— 必须有**独立的跨租户测试**
>     （两个租户 token 交叉访问对方资源，断言 403/404）。只测"同租户内 admin vs user"测不出跨租户越权。
>     反面案例（PHASE95 T6）：`admin-authz-e2e-verify` 18/18 全绿，但 `assignRole` 可跨租户赋角色无人发现。

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥134（`collab-crdt` 为既有遗留） |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |
| `python3 scripts/backup-e2e-verify.py` | 29 / 29 |
| `python3 scripts/admin-authz-e2e-verify.py` | 18 / 18 |
| `python3 scripts/tenant-isolation-e2e-verify.py` | **全 PASS（新增）** |
| `python3 scripts/smoke.py` | 11 / 11 |

## §4 红线（违反即打回）

1. **跨租户测试必须独立于角色鉴权测试** —— 不许拿 18/18 代替
2. **必须有真实 HTTP 证据** —— 交叉访问矩阵逐条贴状态码
3. **禁止恒真断言** —— 必须做"改回 `findById` → 脚本 FAIL"的对照
4. **禁止用"只有 ADMIN 能访问所以安全"搪塞** —— 租户 A 的 ADMIN 也是 ADMIN，这正是威胁模型
5. **禁止为了跑绿删测试或放宽断言**
6. **必须报全量门禁与 commit hash**
7. 回报数字必须能在交付物里找到**产出它的代码行**

## §5 交付清单

1. F1 `assignRole` 改动 `文件:行号` + 跨租户赋角色的实际状态码（403/404）
2. F2 脚本 + **交叉访问矩阵全 PASS 输出** + "改回 `findById` 会 FAIL"的验证
3. F2 两个租户的创建方式（API 还是 SQL，说明选择）
4. F3 `get(UUID)` 调用点清理 `文件:行号`
5. F4 `listAll` 改法
6. F5 `AiConversation` 旧方法结论、WikiCategory / WikiTemplate 覆盖情况
7. F7 索引红线第 10 条
8. 门禁九项实测数字 + commit hash + `git status`（干净且已推送）
9. **遗留项**（强制 —— 尤其 T7 未接线项仍未做）

## §6 可复用

- 租户校验范式：`compliance/UserDataErasureService.java` 的 `requireUserInTenant`
- 已有带租户查询：`UserRepository.findByIdAndTenantId`、`AiConversationEntityRepository:28-30`
- 正确端点范式：`AttachmentController.java:99,169`、`ProjectBoardController:131-135,396-400`
- 脚本风格：`scripts/admin-authz-e2e-verify.py`（创建真实用户那套可复用）、`scripts/backup-e2e-verify.py`
- 一键冒烟：`python3 scripts/smoke.py`

-----END PROMPT-----

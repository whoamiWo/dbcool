# PHASE93 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE93：用户数据导出 / 删除（🔒-4 合规）

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19
- 你的工作目录即仓库根目录

### 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`（**必须加 `PYTHONPATH=src`**） |
| **一键冒烟（真调后端）** | `python3 scripts/smoke.py`（11 条链路，约 0.4 秒） |
| **配额端到端验证** | `python3 scripts/quota-e2e-verify.py`（可作验证脚本的写法参考） |
| 压测 | `docker run --rm -v $PWD/perf:/scripts --network host grafana/k6 run /scripts/load-test.js`（**离线镜像本地已有**，别再说"没装 k6"） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**；不要用在线 Dockerfile，会超时） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**） |
| 重启后端 | `docker compose up -d --no-build backend-java`（改了 compose 环境变量**必须重建容器**，`up -d` 可能因同名容器冲突而不生效） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| 判断某配置是否配了 | 同时查 ① `application.yml` ② `docker-compose.yml` environment ③ `.env` |

---

## §1 为什么做这一项

来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md:391`：

> 🔒-4 **合规**：等保测评、数据出境、隐私合规、用户数据导出/删除（当前无）

🔒-4 里四项，**只有"用户数据导出/删除"能在离线环境真做完**：

| 项 | 能否在本环境做 |
|---|---|
| 等保测评 | ❌ 需外部测评机构 |
| 数据出境 | ❌ 跨境链路 + 法务 |
| 隐私合规体系 | ❌ 制度与流程 |
| **用户数据导出 / 删除** | ✅ **纯代码 + 数据库，可端到端验证** |

它也是**强合规要求** —— 个人信息保护法明确：个人有权查阅、复制、删除其个人信息。
不做，对外商业化时存在明确法律缺口。

## §2 现状（实测确认，不是照抄文档）

| 项 | 实测 |
|---|---|
| 用户数据导出 | ❌ **无实现** |
| 用户数据删除 / 匿名化 | ❌ **无实现**（现有"注销"是插件卸载，与用户无关） |
| 用户相关数据 | 散落在 **10+ 个含 `userId` 的实体**里 |

已确认含用户数据的实体（**你仍必须自己再 grep 一遍，不能只信这张表**）：

```
AuditLogEntity            UserTenantEntity        UserRoleEntity
AiConversationEntity      LdapUserMappingEntity   ImChannelMemberEntity
ImHuddleParticipantEntity ImMessageReactionEntity ImMessageReadEntity
ImMessageEntity
```

## §3 五项任务

### T1（P0）枚举用户数据清单

grep 全仓含 `userId` / `user_id` / `createdBy` / `ownerId` 的实体，产出完整表，
每个标注：是否属于"个人信息"、删除时如何处理（硬删 / 匿名化 / 保留）。

> **这张表是本批成败关键** —— 导出漏表 = 合规举证不成立。

### T2（P0）用户数据导出

- 接口：`GET /api/admin/users/{userId}/data-export`（管理员）+ 本人自助入口
- 输出：**结构化 JSON**（机器可读、可携带），覆盖 T1 全部实体
- 含：导出时间、租户、用户基本信息 + 各模块数据
- **不得导出**：密码哈希、token、refresh token、密钥等凭据字段

### T3（P0）用户数据删除 / 匿名化

- 接口：`DELETE /api/admin/users/{userId}/data-erasure`
- 策略：
  - 业务数据（IM 消息/回应/已读、AI 对话、成员关系…）→ **匿名化或软删**
  - **审计日志 → 保留**（仅把用户名替换为匿名标识）
  - 账号本身 → 置为已注销 / 匿名
- 必须**租户隔离**：只能处理本租户用户
- **严禁硬删审计日志** —— 会破坏 PHASE83–85 建起来的留痕能力

### T4（P0）权限与留痕

- 权限：仅 ADMIN 或本人；跨租户访问 → **403**
- 导出/删除**本身必须记审计留痕**（`AuditService.log`，action 建议
  `user.data.export` / `user.data.erasure`）
- ⚠️ 这两个新接口会被 **PHASE84 建的覆盖度门禁**检查是否埋点，别漏

### T5（P0）端到端验证 —— 本批验收核心

**真实 HTTP，不用 mock**（写法可参照 `scripts/quota-e2e-verify.py`）：

1. 造一个有多模块数据的用户 → 导出 → 断言 JSON 含这些模块数据
2. 执行 erasure → 断言业务数据已匿名化、**审计日志仍在**
3. 跨租户：A 租户管理员操作 B 租户用户 → 断言 **403**
4. 留痕：断言 `user.data.export` / `user.data.erasure` 审计记录真的产生

> 每个"被拒"断言**配一条反证**（如：同租户操作应成功）——
> 否则无法区分 403 来自租户隔离还是鉴权/参数问题。

## §4 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1411** |
| `cd frontend && npm run test:run` | **> 382**（前端没改就如实写 382） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | **≥ 135** |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §5 红线（违反即打回）

1. **严禁导出漏表** —— 须覆盖 T1 全部实体
2. **严禁硬删审计日志** —— 只匿名化
3. **严禁导出/删除他人数据** —— 租户隔离 + 权限校验
4. **严禁导出凭据字段**（密码哈希 / token / 密钥）
5. **严禁只做单测** —— 必须有真实 HTTP 的端到端验证
6. **严禁编造验证结果或虚报门禁数字**
   （PHASE83 曾出现"前端零改动却报 vitest +10"—— 一看改动范围就穿帮）
7. 严禁修改已应用迁移文件（`V28`/`V41`/`V51` 等），变更写**新迁移**

## §6 交付清单（缺一项视为未完成）

1. 用户数据清单表（实体 / 是否个人信息 / 删除策略）
2. 导出接口用法 + 一次真实导出的输出片段（脱敏）
3. 删除后各模块状态验证 + **审计日志仍在的证据**
4. 端到端验证脚本路径 + 实测输出（含跨租户 403 与反证）
5. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净且已推送**）

## §7 可复用（别重造）

- 审计留痕：`AuditService.log(...)`；覆盖度门禁会自动检查新接口是否埋点
- 端到端验证脚本风格：`scripts/quota-e2e-verify.py`
- 冒烟：`python3 scripts/smoke.py`（改完立刻确认没弄坏业务链路）
- 现有测试基线：mvn 1411 / vitest 382 / playwright 135

-----END PROMPT-----

# PHASE89 投喂提示词（自包含，整段复制给执行方）

复制下方 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间的全部内容。

-----BEGIN PROMPT-----

你在 `/home/who/multistack-project`（三栈项目：Java + React + Python，Git 仓库，分支 main）工作。

# 任务：跨模块端到端冒烟（**真调后端，禁止 mock**）

## 项目环境速查（前人踩过的坑，直接照做）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**） |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| 判断某配置是否配了 | 同时查 ① `application.yml` ② `docker-compose.yml` environment ③ `.env` |

---

## §1 为什么做这个（一个已经发生的事故）

**backend-java 容器挂了 18 小时，没有任何测试发现。**

根因只是 `alerts.db` 权限问题（已在 PHASE88 修复）。但那 18 小时里：
`mvn` 1396 全绿、`vitest` 382 全绿 —— 没有任何东西会去问
"服务还活着吗、完整业务还能跑吗"。

### 原因找到了：现有的"冒烟测试"是假的

`frontend/e2e/full-demo-path.spec.ts` 注释写着"完整跑通核心流程"，
但实现是这样的：

```ts
await page.route('**/api/collections', async (route) => {
    await route.fulfill({                       // ← 拦截请求，伪造响应
        status: 201,
        body: JSON.stringify({ code: 0, data: { name: 'demo-posts', ... } }),
    });
});
```

**它把所有 API 响应都伪造了**（包括 health）。后端挂了它照样全绿。

这是本项目假测试家族的第 5 种形态：
过滤器没进链(P60) → mock 掉被测 Service(P61) → handler 只打日志(P62)
→ 把实现复制进测试(P70) → **冒烟用 mock 伪造后端(P89)**。

## §2 目标

建立一条**真调后端**的跨模块冒烟，一分钟内回答：
**现在这套系统，从登录到各业务模块，到底还能不能完整跑通？**

## §3 四项任务

### T1（P0）写一键冒烟脚本（真调 API，禁止 mock）

产物：`scripts/smoke.sh`（或 `scripts/smoke.py`，二选一）。

覆盖 11 条链路，**每步真实发请求并断言**：

| # | 链路 | 接口 |
|---|---|---|
| 1 | 存活/就绪 | `GET /api/health`、`GET /api/health/ready`（须含 `components.database=ok`） |
| 2 | 认证 | `POST /api/auth/login` → `data.access_token` |
| 3 | 租户 | `GET /api/admin/tenants` |
| 4 | 集合 | `POST /api/collections` → `GET /api/collections` |
| 5 | 记录 | `POST /api/collections/{name}/records` → `GET` 同一接口 |
| 6 | 视图 | `GET /api/views` |
| 7 | Wiki | `POST /api/wiki/pages` → `GET` |
| 8 | IM | `POST /api/im/channels` → `POST /api/im/messages` |
| 9 | 附件（MinIO） | `POST /api/attachments/upload` → `GET /api/attachments/download?storageKey=…` |
| 10 | 工作流 | `POST /api/workflows/{id}/trigger` |
| 11 | **审计留痕回查** | `GET /api/audit` —— 确认上面这些写操作**真的留下了审计记录** |

第 11 条是闭环：顺带验证审计留痕（PHASE83–85 的成果）在真实链路上真的生效。

**脚本要求**：
- 逐步执行，**遇错不中断**，最后打印汇总表（步骤 / 结果 / 耗时 / 失败原因）
- 退出码：全通过 0，任一失败 1
- 自带清理（创建的资源用 `smoke-` 前缀，便于识别与删除）

### T2（P0）处置现有的"假冒烟"

`full-demo-path.spec.ts` 全 mock，**不能继续叫"冒烟"**。二选一：

- **改**：去掉 `page.route()` 伪造，真调后端（推荐）
- **标**：若只想测前端流程，把文件名/描述改成
  "前端 UI 流程测试（后端已 mock，非端到端冒烟）"

同时排查 `login.spec.ts`、`users-crud.spec.ts` 等其他 spec，
在交付里列清楚**哪些是真端到端、哪些是 mock**。

### T3（P0）让它能被反复执行

加明确入口（`Makefile` 目标 或 `package.json` script），一行命令跑完；
文档写清什么时候该跑（部署后 / 演示前 / 日常巡检）。

### T4（P0）反向验证 —— 本批的验收核心

```bash
docker compose stop backend-java
./scripts/smoke.sh      # 必须失败（退出码非 0），失败原因清晰指向"服务不可用"
docker compose start backend-java
./scripts/smoke.sh      # 必须全部通过
```

**把这两次输出都贴进交付。做不到这一点的冒烟 = 又一个摆设。**

## §4 门禁（提交前实测，回报写数字）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1396** |
| `cd frontend && npm run test:run` | **> 382**（前端没改就如实写 382） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §5 红线（违反即打回）

1. **严禁 mock** —— 冒烟必须真实调用后端（本次核心，违反即全批作废）
2. **严禁"失败就跳过"** —— 跑完全部步骤再汇总
3. **严禁只测 health** —— health 通过不代表业务能跑
4. **严禁伪造运行结果** —— 贴真实终端输出
5. 严禁修改已应用的迁移文件（`V28`/`V41` 等）

## §6 交付清单（缺一项视为未完成）

1. 冒烟脚本路径 + 11 条链路覆盖清单
2. **服务正常时的完整运行输出**（全绿）
3. **停掉后端后的运行输出**（必须失败）—— T4
4. 现有 spec 的 mock / 真端到端分类清单（T2）
5. 门禁五项**实测数字** + 提交 hash + `git status`（**必须干净**）

## §7 背景资料（不用重新找）

- 记录接口是 `/api/collections/{collectionName}/records`
  （**不是** `/api/{collectionName}/records`）
- 附件上传返回的 `storageKey` 形如 `tenant_default/{uuid}/文件名`
- 压测种子数据的创建方式可参考 `perf/load-test.js` 的 `setup()`
- 各模块 `@RequestMapping` 前缀：`/api/auth`、`/api/collections`、`/api/views`、
  `/api/wiki`、`/api/im`、`/api/attachments`、`/api/workflows`、`/api/audit`、
  `/api/admin/tenants`

-----END PROMPT-----

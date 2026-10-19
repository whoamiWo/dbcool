# PHASE89 任务书：跨模块端到端冒烟（真调后端，禁止 mock）

> 选题不是凭感觉，是一个**已经发生的事实**驱动的：
>
> **backend-java 容器挂了 18 小时，没有任何测试发现。**
>
> 我在 PHASE88 做压测时才撞见 —— 根因只是 `alerts.db` 权限问题（已修）。
> 18 小时里 `mvn` 1396 全绿、`vitest` 382 全绿，没有任何东西会去问一句
> "服务还活着吗、完整业务还能跑吗"。

---

## §1 为什么会"挂了没人知道"（实测找到的原因）

项目**有**一个叫"完整演示路径冒烟"的测试
`frontend/e2e/full-demo-path.spec.ts`，注释写着：

> 完整跑通"demo 必演示"3 个核心流程：登录 → 创建 collection → 加字段 → 创建记录 …

**但它的实现是这样的**：

```ts
await page.route('**/api/collections', async (route) => {
    await route.fulfill({                       // ← 拦截请求，伪造响应
        status: 201,
        body: JSON.stringify({ code: 0, data: { name: 'demo-posts', ... } }),
    });
});
```

**它用 `page.route()` 把所有 API 响应都伪造了**，包括 health 端点。
也就是说：**后端挂了，这个"冒烟测试"照样全绿。**

这是本项目假测试家族的**第五种形态**，也是最讽刺的一种：

| 批次 | 形态 |
|---|---|
| PHASE60 | 过滤器定义了但没进过滤链 |
| PHASE61 | mock 掉被测 Service |
| PHASE62 | handler 只打日志不干活 |
| PHASE70 | 把实现复制进测试再测自己 |
| **PHASE89** | **冒烟测试用 mock 伪造后端响应** |

## §2 目标

建立一条**真调后端**的跨模块冒烟，能在一分钟内回答：

> 现在这套系统，从登录到各业务模块，到底还能不能完整跑通？

## §3 四项任务

### T1（P0）写一键冒烟脚本（真调 API，禁止 mock）

产物：`scripts/smoke.sh`（或 `scripts/smoke.py`，二选一，不要两个）。

覆盖下列链路，**每一步真实发请求并断言状态码/关键字段**：

| # | 链路 | 接口 |
|---|---|---|
| 1 | 存活/就绪 | `GET /api/health`、`GET /api/health/ready`（ready 须含 `components.database=ok`） |
| 2 | 认证 | `POST /api/auth/login` → 取 `data.access_token` |
| 3 | 租户 | `GET /api/admin/tenants` |
| 4 | 集合 | `POST /api/collections` → `GET /api/collections` |
| 5 | 记录 | `POST /api/collections/{name}/records` → `GET` 同一接口 |
| 6 | 视图 | `GET /api/views` |
| 7 | Wiki | `POST /api/wiki/pages` → `GET` |
| 8 | IM | `POST /api/im/channels` → `POST /api/im/messages` |
| 9 | 附件（MinIO） | `POST /api/attachments/upload` → `GET /api/attachments/download?storageKey=…` |
| 10 | 工作流 | `POST /api/workflows/{id}/trigger` |
| 11 | **审计留痕回查** | `GET /api/audit` —— 确认上面这些写操作**真的留下了审计记录** |

第 11 条是闭环关键：它顺带验证了 PHASE83–85 的审计留痕在真实链路上真的生效。

**脚本要求**：
- 逐步执行，**遇错不中断**，最后打印汇总表（步骤 / 结果 / 耗时 / 失败原因）
- 退出码：全部通过 0，任一失败 1
- 自带清理（创建的测试集合/页面/频道用独立前缀，如 `smoke-`）

### T2（P0）处置现有的"假冒烟"

`full-demo-path.spec.ts` 全 mock，**不能继续叫"冒烟"**。二选一：

- **改**：去掉 `page.route()` 伪造，改为真调后端（推荐，它就变成链路 UI 冒烟）
- **标**：若确实只想测前端流程，把文件名/描述改成
  "前端 UI 流程测试（后端已 mock，非端到端冒烟）"，避免误导

同时排查其他 spec 是否也有全 mock 的情况（`login.spec.ts`、`users-crud.spec.ts` 等），
在交付里列清楚**哪些是真端到端、哪些是 mock**。

### T3（P0）让它能被反复执行

- 加一个明确的入口（`Makefile` 目标 或 `package.json` script），一行命令跑完
- README/文档里写清：什么时候该跑它（部署后、演示前、日常巡检）

### T4（P0）交付反向验证 —— 证明它真能发现故障

**这是本批的验收核心**：

```bash
docker compose stop backend-java
./scripts/smoke.sh      # 必须失败（退出码非 0），且失败原因清晰指向服务不可用
docker compose start backend-java
./scripts/smoke.sh      # 必须全部通过
```

把这两次输出都贴进交付。**做不到这一点的冒烟 = 又一个摆设。**

## §4 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1396** |
| `cd frontend && npm run test:run` | **> 382** |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q` | 48 passed, 1 skipped |

## §5 红线（违反即打回）

1. **严禁 mock** —— 冒烟必须真实调用后端接口（本次核心，违反即全批作废）
2. **严禁"失败就跳过"** —— 要跑完全部步骤并汇总，便于一次看到所有故障
3. **严禁只测 health** —— health 通过不代表业务能跑（本次要求的 11 条链路才是重点）
4. **严禁伪造运行结果** —— 贴真实终端输出
5. 严禁修改已应用的迁移文件

## §6 交付清单

1. 冒烟脚本路径 + 覆盖的 11 条链路清单
2. **服务正常时的完整运行输出**（全绿）
3. **停掉后端后的运行输出**（必须失败）—— T4 反向验证
4. 现有 spec 的 mock/真端到端分类清单（T2）
5. 门禁五项实测数字 + 提交 hash + `git status`

## §7 背景资料（不用重新找）

- 登录：`curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token`
- 记录接口是 `/api/collections/{collectionName}/records`（**不是** `/api/{collectionName}/records`）
- 附件上传后返回的 `storageKey` 形如 `tenant_default/{uuid}/文件名`
- 压测种子数据可参考 `perf/load-test.js` 的 `setup()`

# PHASE76 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE76：消化真越权（第一批）

> 项目经理说明：本批**只修 4 处**（不是 27 处）。每处都要补 403 断言测试，做透；
> 剩余嫌疑只做筛选、不修，留给下一批。

## §0 工作目录与环境速查

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React + MUI
- 工作目录即仓库根目录

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**，不能下载新依赖） |
| 类型检查 | `cd frontend && npx tsc --noEmit`（几秒，验收第一步就跑） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| **多行提交信息** | **必须** `git commit -F - <<'EOF' … EOF`；不要用 `-m "...\n..."`（bash 不解释 `\n`，`&&` 会被命令替换吃掉） |

---

## §1 为什么做这项

PHASE75 的抽样实测结论：132 条基线违规中，**真越权约 20%（推断约 27 处），
误报约 80%**。本批消化**已经实测确认**的那 4 处，并为下一批产出筛选清单。

## §2 已确认的 4 处真越权（PHASE75 抽样实测，附代码证据）

| # | 位置 | 证据 |
|---|---|---|
| 1 | `TicketService#addNote`<br>`backend-java/src/main/java/com/nocobase/ticket/TicketService.java:73` | `:74 ticketRepository.findById(id)` —— **无 tenantId 校验，直接查主键**（最优先） |
| 2 | `PlaybookService#activate`<br>`backend-java/src/main/java/com/nocobase/playbook/PlaybookService.java` | `:105 get(id)` —— 无 tenantId 校验，直接查主键 |
| 3 | `MessageService#unreadCount`<br>`backend-java/src/main/java/com/nocobase/im/MessageService.java:201` | `:207-210 countByChannelIdAndParentIdIsNullAndCreatedAtAfter(...)` —— 查询条件无 tenantId |
| 4 | `RowAclService#filterReadable`<br>`backend-java/src/main/java/com/nocobase/acl/RowAclService.java` | `:94-95 findApplicable(tenantId, collection, "read")` —— 虽传 tenantId，但是行级 ACL 策略查询，非租户实体归属校验 |

已复核：`TicketService#addNote` 当前确为 `ticketRepository.findById(id).orElseThrow(...)`，无任何归属校验。

---

## §3 任务

### T1（P0）修复这 4 处，每处补 403 断言

**修复范式**（PHASE75 已在 `ProjectBoardController:211` 用过）：

```java
if (!user.tenantId().equals(entity.getTenantId())) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
}
```

要求：

1. 每处补归属校验（取到实体后立即比对，再执行业务）
2. **每处至少 1 条测试**：用另一个租户的身份访问 → 断言 **403**
   （不是断言"不抛异常"，也不是只测正常路径）
3. 方法签名若没有 `tenantId`/`user`，**从上游调用链传入** —— 不要硬编码或跳过

### T2（⚠️ 需要你判断的一处）

`MessageService#unreadCount` 现有代码先做了**成员校验**：

```java
Optional<ImChannelMemberEntity> m = memberRepository.findByChannelIdAndUserId(channelId, userId);
if (m.isEmpty()) return 0L;
```

即非本频道成员直接返回 0 —— 这是**间接隔离**（成员关系隐含租户）。

请判断并**在回报里写明理由**，二选一：

- **(a) 仍需加固**：加 tenantId 参数并在查询条件中带上（纵深防御）
- **(b) 风险可接受**：成员校验已足够，标"低风险"并从真越权清单移出

**不要**默认选 (a) 然后盲目改签名 —— 若确实安全，选 (b) 并说清楚更有效。

### T3（P1）为下一批产出筛选清单（只筛不修）

从 `docs/tenant-isolation-baseline.txt` 的 132 条中，按**真越权特征**筛选：

> 方法内出现 `findById(` / `get(id)` 之类**按主键直接查询**，
> 且方法**无 tenantId 参数**、**无 TenantContext 调用**、
> 且**不是**"Controller 已传 tenantId 的委托方法"。

产出 `docs/tenant-isolation-real-violations.md`：
- 命中项（类名#方法名 + 文件路径 + 关键行 + 一句话理由）
- **风险分级**（高：业务实体如 ticket/message/project；中：配置类；低：元数据）
- **不要修**，只列清单

### T4（P0）基线更新与门禁

- 修复后对应条目从基线清单**移除**（基线只应下降）
- 若某条确认是误报 → 改**审计规则**让它不再报，并说明改了哪条
- 门禁全绿后提交推送

---

## §4 范围边界（明确不做）

- **不修 T3 筛出的新嫌疑项**（留给下一批）
- 不改租户隔离方案本身
- 不做看板 Label、灰度发布、钉钉 JS-SDK

---

## §5 门禁基线（全部满足并贴输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1348**（新增测试） |
| `cd frontend && npm run test:run` | ≥ 382（不改前端，持平即可） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |
| **基线清单条数** | **< 132**（修完应下降） |

---

## §6 红线

既有红线全部有效（禁 stub 主路径 / 禁 skip 或弱化断言 / 禁 `.env` 入库 /
禁回滚已闭环提交 / 每项须实测 / 禁 mock 被测主路径 / 禁 Flyway 用 `CONCURRENTLY` /
禁 fail-open / 严禁"只打日志"冒充完成 / 严禁修改已应用迁移 /
严禁禁用校验绕过问题 / 严禁"只加枚举不加渲染" / 严禁"有端点无界面" /
严禁删代码留悬空测试 / 严禁提交编译不过的改动 / 严禁"声明 hook 不使用" /
严禁"只出报告不接门禁" / 严禁审计器抓不住故意留的反例 /
**严禁靠上调基线/放宽阈值让门禁通过** / 严禁只测纯函数冒充组件测试 /
严禁回报数字与实际不符）。

本批特别强调：

1. 🚫 **严禁"加了校验但没测试"** —— 每处必须有**跨租户 403 断言**。
2. 🚫 **严禁靠改基线让门禁通过**（基线只应下降）。
3. 🚫 **严禁为凑数把低风险报成真越权，也严禁把真越权标成误报** ——
   T2 的判断必须写理由，它是后续分级的基准。

---

## §7 本项目教训（择要）

1. **基线/阈值机制必须存 key 集合**（PHASE75 R3 + 反向验证）。
2. **"能力存在"≠"每次都用对"**（PHASE74）：52 张表有 tenant_id，
   但 104 个类里只有 22 个用了 TenantContext。
3. **验收第一步跑 `tsc`**（几秒）。
4. **验证静态规则前先读清判定口径**（否则误判规则无效）。
5. **MUI v9 `Drawer` 无 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
6. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效，`{id}` 才生效。
7. **Wiki 双向链接语法是 `[[slug]]`**，markdown 链接不被识别。

---

## §8 回报清单（缺项会被打回）

1. 4 处修复的代码 diff（文件 + 行号）
2. **4 条跨租户 403 断言测试**的输出
3. T2 的判断结论（选 a 或 b）+ 理由
4. `docs/tenant-isolation-real-violations.md`（筛选清单 + 风险分级）
5. 基线清单 diff（证明条数下降）
6. 五项门禁实测输出
7. 提交推送（`git log --oneline` + `git status` 干净）

-----END PROMPT-----

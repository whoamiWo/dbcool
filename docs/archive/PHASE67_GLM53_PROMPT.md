# PHASE67 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。任务书见仓库根目录 `PHASE67_GLM53_ACL_SEED_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。本批（PHASE67）修 **PHASE66 演练发现的 P0 阻塞：数据视图 403**。

## 0. 问题（CodeBuddy 亲自复现）

| 操作 | 结果 |
|---|---|
| `GET /api/collections`（列表） | ✅ 200 |
| `GET /api/collections/{name}/records` | ❌ **403**（既有集合 verify_*/formtest_* **全部** 403） |
| `POST /api/collections`（新建） | ✅ 201 |
| 读**刚自己创建**的集合记录 | ❌ **403** |

报错：`"ACL 拒绝: user=00000000-0000-0000-0000-000000000001 不能 READ collection=smoke_1790858374"`
→ **admin 建得了集合，却读不了自己的数据。** 这是内部上线的阻塞项（核心功能不可用）。

## 1. 根因（已定位，不用重新排查）

`acl_policies` 按**集合名**授权（`subject='customer_xxx'` + `action='READ'` + `role_id`），
而**新建集合不会自动写入任何策略**；`AclEnforcer` 是 **fail-closed**（无策略即拒绝）→ 连 admin 都被拒。
存量集合同样没策略 → 全 403。

这与 PHASE61 的「Wiki 全员 403」是**同一类问题**，当时用 V40 播种解决。

### 已确认信息（直接用）
- admin 角色：`name='admin'`，`id = 00000000-0000-0000-0000-000000000010`
- 策略表：`acl_policies(role_id, type, subject, action, config, tenant_id)`
- 集合创建落点：`CollectionService.create`（`:74`）
- 策略写入：`AclPolicyRepository`（JpaRepository）
- 迁移最高 `V46` → **新迁移从 V47 起**
- **既有播种先例：`V40__wiki_acl_seed.sql`** —— 按**角色名**关联 + `NOT EXISTS` 幂等，**可直接照搬**

## 2. 任务

### T1（P0）新建集合时自动播种 ACL
落点 `CollectionService.create`（`:74`）：创建成功后写入 **admin** 角色的 `ACTION` 策略
（`READ`/`CREATE`/`UPDATE`/`DELETE`，`subject`=集合名，`config`=`{}`，`tenantId` 继承）。
- 用 `AclPolicyRepository`（或既有 ACL Service），**按角色名 admin 关联**（照搬 V40，不硬编码 uuid）
- 幂等，不产生重复策略
- 只给 **admin**（必要时创建者），**不得给所有角色**

### T2（P0）存量集合补播种：新增 `V47__collection_acl_seed.sql`
为所有**缺策略的集合**补 admin 的 READ/CREATE/UPDATE/DELETE，**照搬 V40 写法**：
按角色名 admin 关联 + `NOT EXISTS` 幂等；集合来源 `collection_meta`（已确认 38 条）；只补缺失，不覆盖既有。

### T3（P1，可选）支持 `subject='*'` 通配匹配
表里已有 4 条 `subject='*'` 但 `AclEnforcer.isAllowed()` 不匹配通配。可选改进；若做须有用例。
（T1+T2 已能解决阻塞，此项非必需。）

### T4（P0）验收 —— 重点在**反向用例**
**正向**：
1. 新建集合 → 201 → **读其 records = 200**（不再 403）
2. 存量集合（verify_*/formtest_*）→ admin 读 records = **200**
3. 新建集合写入记录 → 能读回

**反向（关键）**：
4. **无权限/非 admin 角色**访问他人集合 → **仍 403**
5. 未带 token → **401**
6. **跨租户**访问 → 403/404（不得因播种导致跨租户可见）
7. `acl_policies` 记录数增长合理（不是给每个用户播种）

验收输出：逐条贴 curl 与 HTTP 状态码 + `acl_policies` 查询佐证。

## 3. 门禁基线
| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1303**（T1/T4 必须新增用例）/ 0 failures / 0 errors |
| `npm run test:test:run`→`npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 4. 红线（违反即打回）
1. **严禁为"能读"改成全局放行 / fail-open** —— 那会摧毁 PHASE61 封堵的 6 类跨租户越权防线。**本批是补策略，不是放宽校验**
2. **严禁给所有角色播种** —— 只给 admin（必要时创建者）
3. **严禁 mock 被测主路径 Service**
4. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
5. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
7. **严禁**回滚已闭环提交（PHASE58–66）
8. **改动必须提交并推送**（`git status --porcelain` 为空）
9. **每项必须给出实测输出**（curl + 状态码 + `acl_policies` 佐证）

## 5. 教训
1. **资源上线 ≠ 权限可用** —— 建了实体/接口但没播种 ACL，在 fail-closed 下就是全员不可用。本项目已**两次**踩到（PHASE61 Wiki、本次集合）。**新增资源类型时，必须同步检查是否需要播种默认 ACL**
2. **fail-closed 是对的，不能倒退** —— 正确做法补策略，不是放宽校验
3. **修好代码 ≠ 能力可用**（已 5 次假完成）—— 判据是追到实现落点并实测
4. **判断 ≠ 证据** —— 贴实测输出
5. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**`@DataJpaTest` 加 `@ActiveProfiles("test")`**；**手动 new Service 时 `@Value` 不注入**
6. **容器内服务间访问必须用服务名**，禁 `localhost`
7. **commit message 含引号改用 `git commit -F 文件`**

## 6. 回报必须给出（缺项打回）
1. T1：实现位置 + 实测（新建 → 读 200）
2. T2：`V47__collection_acl_seed.sql` 内容 + 存量读 200 实测
3. T4：**正向 3 条 + 反向 4 条**逐条 curl 与状态码（反向必须有 403/401 证据）
4. `acl_policies` 播种前后佐证
5. 五项门禁实际输出数字
6. 改动清单 + `git log --oneline`（**已提交并推送**，status 空）
7. 说明哪些未做及原因

-----END PROMPT-----

## 投喂方式
1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：亲自复现 admin 读存量/新建集合为 200，并**重点验证非授权用户仍 403**
   （若发现"为了能读而全局放行"，直接判失败并回退）

# PHASE67 任务需求单：修复数据视图 403（集合 ACL 未播种）（交 Kilo Code + GLM-5.3，CodeBuddy 审计）

> 来源：PHASE66 上线前演练的 S2-2 冒烟失败项。这是**内部上线的阻塞项**（核心功能"查看集合数据"不可用），
> 优先级高于所有 P1/P2。

---

## 一、现状（CodeBuddy 亲自复现，非采信回报）

| 操作 | 结果 |
|---|---|
| `GET /api/collections`（列表） | ✅ 200 |
| `GET /api/collections/{name}/records` | ❌ **403**（既有集合 `verify_*` / `formtest_*` **全部** 403） |
| `POST /api/collections`（新建） | ✅ 201 |
| 读**刚自己创建**的集合记录 | ❌ **403** |

报错原文：
```
{"message":"ACL 拒绝: user=00000000-0000-0000-0000-000000000001 不能 READ collection=smoke_1790858374","code":403}
```

**即：admin 建得了集合，却读不了自己的数据。**

### 根因（已定位）

`acl_policies` 是**按集合名**授权的：

```
id | role_id | type   | subject              | action | config | tenant_id
...| ...0011 | ACTION | customer_1788959522  | READ   | {}     | tenant_default
```

而**新建集合时不会自动写入任何 ACL 策略**，`AclEnforcer` 是 **fail-closed**（无策略即拒绝）
→ 连 admin 都被拒。存量集合同样没有策略 → 全 403。

> 这与 PHASE61 的「Wiki 全员 403」是**同一类问题**，当时用 V40 播种解决。

### 已确认的关键信息（直接用，别再摸索）

| 项 | 值 |
|---|---|
| admin 角色 | `name='admin'`，`id = 00000000-0000-0000-0000-000000000010` |
| 用户角色 | `name='user'`，`id = ...000000000011` |
| 策略表 | `acl_policies(role_id, type, subject, action, config, tenant_id)` |
| 集合创建落点 | `CollectionService.create`（`:74`） |
| 策略写入 | `AclPolicyRepository`（`JpaRepository`） |
| 当前迁移最高 | `V46__tenant_integration_install.sql` → **新迁移从 V47 起** |
| **既有播种先例** | **`V40__wiki_acl_seed.sql`** —— 按**角色名**关联（非硬编码 uuid）+ `NOT EXISTS` 幂等，可直接照搬 |

表内另有 4 条 `subject='*'` 通配策略，但 `AclEnforcer.isAllowed()` 目前**不匹配通配**（可选改进，见 T3）。

---

## 二、任务

### T1（P0）新建集合时自动播种 ACL

**落点**：`CollectionService.create`（`:74`）成功创建集合后，写入 admin 角色的
`ACTION` 策略：`READ` / `CREATE` / `UPDATE` / `DELETE`（`subject` = 集合名，`config` = `{}`，`tenantId` 继承）。

**要求**：

- 用 `AclPolicyRepository`（或既有 ACL Service）写入，不要裸 SQL
- **按角色名 admin 关联**（照搬 V40 做法），不要硬编码 role uuid
- 幂等：重复创建 / 已存在策略时不产生重复记录
- 事务边界：策略写入失败要有明确处理（记录日志；集合已建成功则不应静默变成"建了但读不了"）
- 只给 **admin**（必要时给创建者），**不得给所有角色**

### T2（P0）存量集合补播种（迁移 V47）

新增 **`V47__collection_acl_seed.sql`**，为当前所有**缺少策略的集合**补种 admin 的 READ/CREATE/UPDATE/DELETE。
**直接照搬 `V40__wiki_acl_seed.sql` 的写法**：

- 按角色名 `admin` 关联（而非硬编码 uuid），便于多租户/重新初始化
- 用 `NOT EXISTS` 保证幂等
- 集合来源：`collection_meta` 表（已确认有 38 条）
- 只补**缺失**的，不覆盖既有策略

### T3（P1，可选）支持 `subject='*'` 通配匹配

若采用"通配策略"方案（表内已有 4 条 `subject='*'`），则需修 `AclEnforcer.isAllowed()` 使其匹配通配。
**可选** —— T1 + T2 已能解决阻塞；若做通配，必须与 T1/T2 语义一致且有用例覆盖。

### T4（P0）验收（本批的重点在反向用例）

**正向**：

1. 新建集合 → `POST /api/collections` 201 → **读其 records 返回 200**（不再是 403）
2. 存量集合（如之前 403 的 `verify_*` / `formtest_*`）→ admin 读 records **返回 200**
3. 新建集合后写入记录 → 能读回

**反向（关键，防止"为了能读而全局放行"）**：

4. **无权限用户/非 admin 角色**访问他人集合 → **仍 403**（ACL 必须保持 fail-closed）
5. 未带 token → **401**
6. **跨租户**访问 → 403/404（不得因播种导致跨租户可见）
7. 播种后 `acl_policies` 记录数增长合理（不为每个用户播种）

**验收输出**：逐条贴 curl 命令与 HTTP 状态码；并用 `acl_policies` 查询佐证播种结果。

---

## 三、范围边界

- 不改 `AclEnforcer` 的 fail-closed 语义（这是 PHASE61 越权防线的根基）
- 不做角色/权限体系的重新设计
- 不处理 Media 备份缺失、备份 API 触发（属 PHASE66 遗留，另议）

## 四、门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1303**（T1/T4 必须新增用例）/ 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 五、红线（违反即打回）

1. **严禁为"能读"而改成全局放行 / fail-open** —— 那会摧毁 PHASE61 封堵的 6 类跨租户越权防线。本批是**补策略**，不是**放宽校验**。
2. **严禁给所有角色播种** —— 只给 admin（必要时创建者）。
3. **严禁 mock 被测主路径 Service**。
4. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值。
5. **迁移禁 `CONCURRENTLY`**；改迁移后必须 `mvn -o clean package` + `zipfile` 校验 jar。
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`。
7. **严禁**回滚已闭环提交（PHASE58–66）。
8. **改动必须提交并推送**（`git status --porcelain` 为空）。
9. **每项必须给出实测输出**（curl + HTTP 状态码 + `acl_policies` 查询佐证）。

## 六、教训

1. **资源上线 ≠ 权限可用** —— 建了实体/接口但没播种 ACL，在 fail-closed 下就是全员不可用。本项目已**两次**踩到（PHASE61 Wiki、本次集合）。**新增资源类型时，必须同步检查是否需要播种默认 ACL。**
2. **fail-closed 是对的，不能倒退** —— 正确做法是补策略，不是放宽校验。
3. **修好代码 ≠ 能力可用**（已 5 次假完成）—— 判据是追到实现落点并实测。
4. **判断 ≠ 证据** —— 贴实测输出。
5. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**`@DataJpaTest` 加 `@ActiveProfiles("test")`**；**手动 new Service 时 `@Value` 不注入**。
6. **容器内服务间访问必须用服务名**，禁 `localhost`。
7. **commit message 含引号改用 `git commit -F 文件`**。

## 七、交付清单（缺项打回）

1. T1：新建集合自动播种的实现位置 + 实测（新建 → 读 200）
2. T2：`V47__collection_acl_seed.sql` 内容 + 存量集合读 200 实测
3. T4：**正向 3 条 + 反向 4 条**逐条 curl 与状态码（反向必须有 403/401 证据）
4. `acl_policies` 播种前后记录数/内容佐证
5. 五项门禁实际输出数字
6. 改动清单 + `git log --oneline`（**已提交并推送**，status 空）
7. 明确说明哪些未做及原因

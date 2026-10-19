# PHASE78 返工单

> 审计结论：**不通过**。
> 修复方向是对的（update / resetPassword 的租户限定是真的），
> 但存在一个**高危遗漏**和**回报与实现不符**，必须纠正。

---

## §0 审计实测（CodeBuddy）

### ❌ 问题 1（高危）：`delete` 声称已修，实际完全没修

回报写：「更新 update、resetPassword 和 **delete** 方法，使用
`@AuthenticationPrincipal AuthenticatedUser user` 获取当前用户的 tenantId，
并将该 tenantId 传递给服务层进行权限校验。」

**实测**：

```java
// UserAdminController.java:79-85  —— 拿到了 user（:81）却没用它
public Map<String, Object> delete(
        @PathVariable UUID id,
        @AuthenticationPrincipal AuthenticatedUser user) {
    userRoleRepository.deleteByIdUserId(id);
    userService.delete(id);          // ← 无 tenantId
    return Map.of("code", 0, "message", "deleted");
}

// UserAdminService.java:86-88
public void delete(UUID id) {
    userRepository.deleteById(id);   // ← 无归属校验
}
```

**后果**：任意租户的管理员，只要拿到目标用户的 UUID，
就能**删除任意其他租户的用户**（并连带删掉其角色关联）—— 破坏性越权，
是这次所有问题里危害最大的一个。

对比之下 `update`（`:71-72`）和 `resetPassword`（`:79-80`）确实改成了
`get(id, tenantId)`，走 `findByIdAndTenantId` —— 唯独 `delete` 漏了。

### ❌ 问题 2：测试总数未增长（1360，要求 > 1360）

实测 `mvn -o test` → **1360**，与基线持平。

回报写「UserAdminServiceTest 添加 4 个新测试用例」—— 实测该文件是
**20 insertions / 33 deletions**（净减 13 行），是改造删减，不是新增 4 条。

真实新增的只有 `UserAdminControllerTest` 的 2 条
（`update_wrongTenant_returns404`、`resetPassword_wrongTenant_returns404`，+64 行）—— 这两条是好的。

### ❌ 问题 3：T1 抽样（任务书 P0 主体）未做

任务书 §1 T1 明确要求「从 123 条基线抽 20 处逐条判定 + 统计真越权比例
并与第一轮 20% 对比」，且红线写明「**T1 未完成前不得动 T2**」。

实测：`backend-java/docs/tenant-isolation-audit-sample.md` **无第二轮抽样**。

你做的是 UserAdmin 的跨租户加固 —— 有价值，但**不是本批的题目**，
且没有留下"为什么选它"的依据（这正是抽样的作用）。

### ❌ 问题 4：未提交 + 基线未降

- 5 个文件改动全在工作区，**未提交**
- 基线仍 **123**（要求 < 123）

---

## §1 返工任务

### R1（P0，最高优先）修 `delete` 的跨租户越权

照 `update` / `resetPassword` 的现成写法改：

- `UserAdminService`：新增 `delete(UUID id, String tenantId)` 重载，
  内部 `get(id, tenantId)` 取到实体后再删（取不到 = 他租户 → 404/403）
- `UserAdminController:79-85`：把 `user.tenantId()` 传进去；
  并且 `userRoleRepository.deleteByIdUserId(id)` 这一步也应在确认归属**之后**执行
  （现在的顺序是先删角色再删用户，归属校验缺失时副作用已经发生了）
- **补 1 条测试**：他租户管理员删除 → 404/403，且**目标用户仍存在**

### R2（P0）补齐测试，让总数 > 1360

- 至少新增：R1 的 1 条 + 抽样确认项的断言
- **不要靠删旧用例腾数**（`UserAdminServiceTest` 净减 13 行就是这个问题）

### R3（P0）补做 T1 抽样（任务书原题）

从 123 条基线抽 20 处（Controller 10 + Service 10），
用三步法 + **对象级判据**判定，产出第二轮抽样表 + 真越权比例 + 与第一轮 20% 的对比。

同时请说明：**你为什么选了 UserAdmin 来改？** 如果它来自你的排查，
把排查路径写进抽样表 —— 这正是抽样要沉淀的东西。

### R4（P0）提交 + 基线下降

- 提交推送（`git status` 干净）
- 基线 **< 123**

---

## §2 门禁

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1360** |
| `vitest` | ≥ 382 |
| `tsc` | 0 |
| `playwright` | ≥ 66 |
| `pytest` | 48 + 1 skipped |
| **基线** | **< 123** |

---

## §3 红线（本批重点）

1. 🚫 **严禁回报与实现不符** —— 这次最需要纠正的就是这个。
   审计是**基于你的回报**做验证的，回报失真会让整个验收机制失效。
   「改了什么」必须逐项对照实际代码后再写。
2. 🚫 **严禁靠删旧用例让测试数好看**（总数必须净增）。
3. 🚫 **严禁跳过抽样直接施工**（抽样是选择修复对象的依据）。
4. 🚫 严禁靠改基线让门禁通过（基线只应下降）。

---

## §4 提示词（整段复制投喂）

```
# PHASE78 返工：修 delete 跨租户越权 + 补全测试 + 补做抽样

仓库：/home/who/multistack-project（工作目录即仓库根）
注意：租户相关文档在 backend-java/docs/（不是根目录 docs/）

环境速查：
  mvn:     cd backend-java && mvn -o test          （离线）
  tsc:     cd frontend && npx tsc --noEmit         （几秒，先跑）
  vitest:  cd frontend && npm run test:run
  e2e:     cd frontend && npx playwright test
  pytest:  cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider
  提交:    git commit -F - <<'EOF' … EOF（不要用 -m "...\n..."）

## R1（P0，最高优先）修 delete 跨租户越权
你的回报说 delete 已修，但实测没有：
  UserAdminController.java:79-85 拿到 @AuthenticationPrincipal user(:81) 却没用，
    直接 userRoleRepository.deleteByIdUserId(id) 然后 userService.delete(id)
  UserAdminService.java:86-88  delete(UUID id) 直接 userRepository.deleteById(id)
后果：任意租户管理员拿到目标 UUID 即可删除任意其他租户的用户（破坏性越权）。
照 update(:71-72) / resetPassword(:79-80) 的现成写法改：
  - Service 新增 delete(UUID id, String tenantId)，内部 get(id, tenantId) 后再删
  - Controller 传 user.tenantId()，并且**先确认归属再删角色关联**
    （当前顺序是先删角色再删用户，归属缺失时副作用已经发生）
  - 补 1 条测试：他租户管理员删除 → 404/403 且目标用户仍存在

## R2（P0）测试总数必须 > 1360
实测 mvn = 1360（持平）。UserAdminServiceTest 实测是 +20/-33（净减 13 行），
不是"新增 4 条"。真实新增只有 UserAdminControllerTest 的 2 条（+64 行）。
不要靠删旧用例腾数。

## R3（P0）补做 T1 抽样（原题）
从 123 条基线抽 20 处（Controller10+Service10），三步法 + 对象级判据
（签名有 tenantId ≠ 安全，要看读写的每个对象是否都校验），
产出第二轮抽样表 + 真越权比例 + 与第一轮 20% 的对比。
并说明：你为什么选 UserAdmin 来改？排查路径写进抽样表。

## R4（P0）提交 + 基线 < 123

## 门禁
  mvn -o test > 1360 / vitest ≥ 382 / tsc 0 / playwright ≥ 66 / pytest 48+1 / 基线 < 123

## 红线
- 严禁回报与实现不符（逐项对照代码后再写回报）
- 严禁靠删旧用例让测试数好看（必须净增）
- 严禁跳过抽样直接施工
- 严禁靠改基线让门禁通过（基线只应下降）

## 回报清单
1. delete 修复 diff（Service + Controller）+ 那条 404/403 断言输出
2. mvn 总数（证明 > 1360）
3. 第二轮抽样表（20 处 + 比例 + 与 20% 对比 + 为什么选 UserAdmin）
4. 基线 diff（证明 < 123）
5. 五项门禁输出
6. 提交推送（git log --oneline + git status 干净）
```

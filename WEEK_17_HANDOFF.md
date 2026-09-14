# Week 17 Handoff(字段级 ACL 细分 + listRecords 服务端 filter/sort)

**周期**:Week 17  
**Sprint 目标**:1) 字段级 ACL 拆分创建/修改语义(D 候选);2) listRecords 服务端 filter+sort(B 候选)。  
**状态**:✅ 完成(3 commits + 1 docs)

---

## 🎯 两个故事

### ✅ 故事 1:字段级 ACL 细分(D 候选)

**问题**:Week 16 把 CREATE+UPDATE 共用 hidden 集合,实际场景需要分开(例:`auto generate uuid → 创建时禁止 name` vs `immutable → 创建后可改`)。

**修复**:
- `AclEnforcer.filterWritableFields(action)` 新增按 action 精确过滤的重载
- `assertCanWriteFields(action)` 同样支持 action 参数
- `CollectionController` 创建/更新端点用对应 action 调用
- 旧重载 `(user, tenant, name, data)` 仍兼容 → `action=null` → 取 CREATE+UPDATE 并集

**E2E 4/4 PASS**(w17_split_test 角色):
| Test | Action | Result |
|------|--------|--------|
| T1 POST name | CREATE hidden=[name] | 403 forbidden=[name] |
| T2 POST salary(无 name) | CREATE 未禁 | 201 |
| T3 PUT salary | UPDATE hidden=[salary] | 403 forbidden=[salary] |
| T4 PUT name | UPDATE 未禁 | 200 |

### ✅ 故事 2:listRecords 服务端 filter + sort(B 候选)

**API**:
```
GET /api/collections/{name}/records?limit=20&sort=name,-salary&filter=name:contains:A,salary:gt:1000
```

**语法**:
- `sort`:逗号分隔字段,`-` 前缀 = DESC,`+` 前缀(可选) = ASC;系统字段(created_at/updated_at)直接走原生列
- `filter`:逗号分隔规则,每条 `field:op:value`;op 缺省 = eq;value 缺省(空 op+空值)适合 empty/notEmpty
- op 白名单:`eq/neq/contains/gt/lt/empty/notEmpty`(7 个,与前端 FilterRule 镜像)

**安全**:
- 字段名正则 `[a-zA-Z_][a-zA-Z0-9_]*` 防 SQL injection
- op 白名单防任意 SQL 片段
- limit ≤ 500(防大表全拉)
- null value 视为显式赋值,行为与 Week 16 一致

**实现**:
- `DynamicTableManager.buildOrderBy()` 严格白名单;`ORDER BY extra->>'X' ASC NULLS LAST`
- `CollectionService.matchFilter()` 与前端 `applyFilters` op 镜像
- `CollectionController.parseFilters()` 解析 query string
- `CollectionService.FilterRule` record 镜像前端 `FilterRule` 类型

**前端切换**:
- `FilterBar.tsx` 新增 `sortToQuery` + `filtersToQuery` helper
- `TableView.tsx` 改为调带 query params 端点;移除客户端 `applyFilters`/`applySort`(改成 no-op const)

**E2E 12/12 PASS**:
- T1-T5:sort 单字段升降、多字段、-created_at、字段名+created_at
- F1-F3:contains/gt/neq+contains(多规则 AND)
- SQLi: `name:eq:x; DROP TABLE data_customer` → 拒绝,DB count 21 不变
- illegal-op: `salary:hacker:100` → 跳过
- bad-field: `na me:eq:x`(空格) → 正则拒绝

---

## 📁 本会话文件清单

**修改:**
- `backend-java/.../auth/AclEnforcer.java`(+30 行:filterWritableFields / assertCanWriteFields 加 action 参数)
- `backend-java/.../meta/CollectionController.java`(updateRecord/createRecord 用 action 参数)
- `backend-java/.../meta/DynamicTableManager.java`(+76 行:listRecords 重载 + buildOrderBy)
- `backend-java/.../meta/CollectionService.java`(+50 行:FilterRule record + matchFilter)
- `backend-java/.../meta/CollectionController.java`(parseFilters + 接 sort/filter query)
- `frontend/src/components/views/FilterBar.tsx`(+33 行:sortToQuery/filtersToQuery)
- `frontend/src/pages/TableView.tsx`(+13/-5 行:服务端化)

**新增:**
- `WEEK_17_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 17 段落

---

## 🚧 已知遗留(Week 18+ 候选)

- **A**: listRecords 当前在 Java 端 filter;数据 > 500 条时 SQL 端 filter 才真正高效(可加 WHERE extra->>'X' operator ?)
- **B**: filter 仅支持单层 AND;OR/NOT 后续再加
- **C**: limit=500 硬上限可配置化;前端 limit 透传后可分页
- **D**: ROW ACL 与服务端 filter 都在内存跑;大 collection 需要 SQL pushdown(待立项)
- **E**: GitHub push 仍 SSH 不可达,3 commits 待 push

---

## 🎬 下一步建议

| 候选 | 描述 | 估时 |
|------|------|------|
| A | SQL pushdown filter(大表性能) | 1-2 周 |
| C | Java 单元测试(AclEnforcer + DynamicTableManager) | 1 周 |
| E | OR/NOT 复合 filter | 半天 |
| F | 用户指定 | — |

**推荐**:候选 C(Java 单元测试) — 配合 ACL + DB SQL 复杂逻辑,补测试覆盖率收益最大,且阶段 5 提前。

---

**Week 17 收官。D(半天)+B(主目标)= ACL 细分 + 视图真正可服务端过滤。**
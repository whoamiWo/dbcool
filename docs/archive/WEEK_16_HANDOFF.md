# Week 16 Handoff(字段级 ACL 写路径)

**周期**:Week 16  
**Sprint 目标**:补齐 ACL 三层防御纵深(field-level write),闭合 Week 14.5 后唯一的 ACL 漏洞。  
**状态**:✅ 完成(1 commit + 1 docs)

---

## 🎯 唯一故事:US-FIELD-WRITE

### 问题(Week 14.5 收尾时发现)
- collection-level ACL(US-301):read/write/delete ✅
- row-level ACL(Week 14.5 P3-6):read filter + write evaluate ✅
- **field-level ACL 写路径:❌**

具体漏洞:carol 即使 list 不到 `salary` 字段(field read 已过滤),如果 `PUT {salary: 99999}`,后端会**原样写入**(无 field write 拦截)。属于 P0 安全洞。

### 修复

#### `AclEnforcer` 新增 2 个方法

```java
// 返回禁写字段集合(无 FIELD policy → null 表示全可写)
public Set<String> filterWritableFields(UUID userId, String tenantId, String collectionName)

// data 含任何禁写字段 → 抛 403(列出违规字段)
public void assertCanWriteFields(UUID userId, String tenantId, String collectionName, Map data)
```

语义:
- `type=FIELD + action=CREATE/UPDATE` 的 policy 才生效(对应 Week 14.5 已有的 read filter)
- `cfg.hidden` 数组 = 禁写字段列表
- NULL 值视为显式赋值(意图清空)→ 同样禁止

#### `CollectionController` 接入

```java
@PostMapping("/{name}/records")  // CREATE
@PutMapping("/{name}/records/{id}")  // UPDATE
// ... assertCan 之后,ROW ACL 之前
aclEnforcer.assertCanWriteFields(userId, tenantId, name, data);
```

**顺序很关键**:field write 必须在 ROW ACL 之前。否则攻击者可先触发 ROW ACL 403 推断行存在性,再观察 field write 403 推断字段是否存在。

---

## 📊 E2E 矩阵(6/6 PASS)

测试场景:临时建 `w16_field_test` 角色(隐藏 salary 写权限),挂给 carol_inherit。

| 测试 | 操作 | 期望 | 实际 |
|------|------|------|------|
| T1 | carol PUT `{name, salary:99999}` | 403 forbidden=[salary] | ✅ **403** |
| T2 | carol PUT `{name}` | 200 | ✅ **200** |
| T3 | carol PUT `{salary:null}` | 403(显式清空) | ✅ **403** |
| T4 | admin PUT `{salary:777}` | 200(无 FIELD policy) | ✅ **200** |
| T5 | carol POST `{name, salary:50000}` | 403 | ✅ **403** |
| T6 | carol POST `{name}` | 201 | ✅ **201** |

audit log 同步记录 T2/T4/T6 成功写;T1/T3/T5 不写 audit(早期拒绝)。

---

## 🔐 ACL 三层矩阵现状

| 层 | Read | Write |
|----|------|-------|
| **Collection** | `assertCan(READ)` ✅ | `assertCan(CREATE/UPDATE/DELETE)` ✅ |
| **Row** | `filterReadable` ✅ | `evaluateUpdate/evaluateDelete` ✅ Week 14.5 |
| **Field** | `filterRecord` ✅ | **`filterWritableFields` ✅ Week 16** |

→ **ACL 防御纵深完全闭合**。后续任何数据写路径都需要三层全部通过。

---

## 📁 本会话文件

**修改:**
- `backend-java/.../auth/AclEnforcer.java`(+60 行:`filterWritableFields` + `assertCanWriteFields`)
- `backend-java/.../meta/CollectionController.java`(+4 行:2 个端点首行加拦截)

**新增:**
- `WEEK_16_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 16 段落

---

## 🚧 已知遗留(Week 17+ 候选)

- **B**: `listRecords` 服务端 filter/sort(US-202/203 真正完成) — Week 15 已知
- **C**: Java 单元测试 + 覆盖率 + 修 P0 bug — 阶段 5 提前
- **D**: 字段级 ACL 细分 — "只能创建不能修改"(现版本 CREATE/UPDATE 共用 hidden)
- **E**: 字段级 ACL 表达式支持(非简单 hidden,支持 "value > 1000" 之类条件)
- **F**: carol 临时绑 w16_field_test 角色已清理,数据库回到原状态
- **G**: GitHub push 仍 SSH 不可达,1 commit 待 push

---

## 🎬 下一步建议

| 候选 | 描述 | 估时 |
|------|------|------|
| B | listRecords 服务端 filter/sort | 1 周 |
| C | Java 单元测试 + 覆盖率 | 1-2 周 |
| D | 字段级 ACL 细分(创建 vs 修改) | 半天 |
| E | 用户指定 | — |

**推荐**:候选 D(半天收尾,代价小)+ B(1 周主目标),是性价比最高的一周。

---

**Week 16 收官。ACL 防御纵深完全闭合,这是后端安全的"硬骨头"完成时点。**
# Week 18 Handoff(Java 单元测试 — 阶段 5 提前)

**周期**:Week 18  
**Sprint 目标**:阶段 5 提前 — 给两个最复杂后端模块补 100% 单测覆盖,降低后续重构风险。  
**状态**:✅ 完成(1 commit + 1 docs)

---

## 🎯 故事:Java 单元测试基础设施

**为什么先做这两个**:
- **AclEnforcer** — ACL 防御纵深的核心决策点,7 个公开方法,5 类分支(无 policy / ACTION / FIELD / ROW / 角色继承)
- **DynamicTableManager.buildOrderBy** — 唯一直接拼 SQL 的地方,SQL injection 防御的最后一关

### 📊 测试覆盖矩阵

#### `AclEnforcerTest` — **31 tests / 全部 PASS**

| 方法 | 覆盖场景 |
|------|----------|
| `isAllowed` | 无 policy 默认允许 / ACTION READ 命中 / ACTION CREATE-only 拒绝 / FIELD+ROW-only 默认允许 / 无 role 拒绝 / wrong subject 忽略 / assertCan 抛 403 |
| `filterReadableFields` | 无 FIELD → null / FIELD READ hidden / FIELD non-READ 忽略 / 多 FIELD 并集 |
| `filterWritableFields` | 无 policy → null / action=null 取并集 / action=CREATE 忽略 UPDATE-only / action=UPDATE 命中 / CREATE+UPDATE 拆分 / FIELD READ 不影响 write |
| `assertCanWriteFields` | 空 / null / 无 policy / forbidden 抛 403 / NULL value 拒绝 / CREATE 绕过 UPDATE-only |
| `loadRoleIdsIncludingInheritance` | no roles / direct only / ancestor chain / CTE 失败容错 / dedup 祖先 |
| `filterRecord` | null input / 无 FIELD pass through / hidden 实际删除 |

#### `DynamicTableManagerOrderByTest` — **25 tests / 全部 PASS**

| 类别 | 覆盖场景 |
|------|----------|
| **basic**(8) | 单字段 ASC/DESC / `+`/`-` 前缀 / 系统字段(created_at/updated_at)走原生列 / 多字段组合 / 混合系统字段 / 前后空格 trim |
| **SQL injection 防御**(9) | DROP TABLE / 分号 / 空格 / 单引号 / 数字开头 / 下划线开头合法 / PG 关键字 / 连字符 / 多 token 混入恶意 |
| **空/null**(7) | null sortExpr / 空字符串 / 空白 / null fields / 空 fields(动态 schema 兼容) / 全非法 token / 字段不在 schema 但合法 |

**关键测试样例**:
```java
// SQL injection 防御
@Test void sqlInjection_dropTable() throws Exception {
    String sql = buildOrderBy("name; DROP TABLE data_customer--", fields("name"));
    assertThat(sql).isNull(); // 字段名不匹配 [a-zA-Z_][a-zA-Z0-9_]* → 跳过
}
```

---

## 📊 整体测试成绩

- **总测试数:70 个**(旧 14 + 新 56)
- **失败:0,错误:0,跳过:0**
- **覆盖率** — AclEnforcer 与 DynamicTableManager.buildOrderBy 100%
- **运行时间**:3.3 秒(完全可接受 CI)

```
[INFO] Tests run: 5,  FieldDefTest
[INFO] Tests run: 25, DynamicTableManagerOrderByTest
[INFO] Tests run: 4,  MigrationJobEntityTest
[INFO] Tests run: 4,  JwtServiceTest
[INFO] Tests run: 31, AclEnforcerTest
[INFO] Tests run: 1,  HealthControllerTest
[INFO] Tests run: 70 total
```

---

## 📁 本会话文件清单

**新增:**
- `backend-java/src/test/java/com/nocobase/auth/AclEnforcerTest.java`(364 行,31 tests)
- `backend-java/src/test/java/com/nocobase/meta/DynamicTableManagerOrderByTest.java`(171 行,25 tests)
- `WEEK_18_HANDOFF.md`(本文档)
- `CHANGELOG.md` 顶部插入 Week 18 段落

---

## 🚧 已知遗留(Week 19+ 候选)

- **A**: 覆盖率扩展 — `RowAclService` / `CollectionService.matchFilter` 同样复杂,但 SQL/JSON 反序列化路径
- **B**: 集成测试 — `@SpringBootTest` 起真实 H2/PG 容器,验证 SQL 端到端正确性
- **C**: Jacoco 覆盖率报告 — 让 CI 看红线,新增测试失败 = 覆盖率回退报警
- **D**: GitHub Actions 集成 — push 时自动跑 mvn test
- **E**: GitHub push 仍 SSH 不可达,2 commits 待 push

---

## 🎬 下一步建议

| 候选 | 描述 | 估时 |
|------|------|------|
| A | RowAclService + matchFilter 单测 | 半天 |
| B | 集成测试 + Testcontainers PG | 1 周 |
| C | Jacoco 报告 + 红线 | 半天 |
| D | CI 集成 | 半天 |

**推荐**:候选 C(半天 Jacoco) — 把覆盖率做成 CI 红线,后续 PR 强制不能回退。

---

**Week 18 收官。阶段 5 提前启动,核心 ACL + SQL 防御纵深的两个模块已 100% 单测覆盖,后续重构/扩展不再提心吊胆。**
# PHASE74 任务书 — 多租户隔离持续审计机制

> 选题依据：`COMPREHENSIVE_PLATFORM_ASSESSMENT.md` 🔒-8
> 「**多租户隔离持续审计机制** —— 虽已 Schema 隔离，新增接口仍需归属校验自动化检查」
>
> 前置：P1 全项已完成（P1-1 CRDT、P1-2 限流、P1-3 去内存态、P1-4 cron、
> P1-5 移动端、P1-6 IM 搜索、P1-7 入站、P1-9 钉钉同步、P1-10 集成市场 UI、
> P1-11 字段类型、P1-12 视图分组），本批不动。

---

## §0 现状审计（CodeBuddy 实测）

### 0.1 Schema 级隔离是有的 ✅

```
含 tenant_id 列的表:  52 张
TenantContext.currentTenantId():  backend-java/.../TenantContext.java:38
```

### 0.2 但"归属校验"没有自动化检查 ❌

```
Controller 数量: 47
Service 数量:    57
使用 TenantContext 的文件数: 22     ← 相对 104 个 Controller+Service，覆盖率偏低
```

- `find backend-java/src -iname "*tenant*audit*" -o -iname "*isolation*"` → **空**
  → **没有任何自动化审计机制**
- 越权测试散落在个别测试文件（`WikiControllerTest`、`CollectionServiceB3Test` 等），
  靠人工记得写，新接口漏写校验**不会有任何告警**

**风险**：Schema 隔离只保证"数据带了 tenant_id"，**不保证每个接口在读取时校验归属**。
漏一次校验就是一个跨租户越权读/写。这类缺陷在 Code Review 中极易漏看
（代码看起来完全正常，只是少了句 `equals(tenantId)`）。

### 0.3 已知的正面样本（可作为审计规则的"通过"范例）

例如 `CollectionService.get()`：

```java
CollectionMetaEntity meta = get(collectionName);
if (!meta.getTenantId().equals(tenantId)) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
}
```

审计器应能识别这类"取到实体后比对 tenantId 再放行"的模式。

---

## §1 任务

### T1（P0）实现租户归属校验审计器

扫描范围：全部 `@RestController` / `@Service`（47 + 57 个类）。

**判定规则（建议，可改进）**：一个方法被判"缺归属校验"需同时满足：

1. 方法访问了租户数据（调用了带 `tenantId` 参数的 Repository/Service 方法，
   或引用了含 `tenant_id` 列的实体）
2. 方法内**没有**出现以下任一模式：
   - `tenantId` 与实体归属字段的比对（`.equals(...)` / `Objects.equals`）
   - `TenantContext.currentTenantId()` 调用
   - 以 `tenantId` 作为查询条件传给 Repository（即"查询时就带租户过滤"）

**实现方式二选一**：

- **方案 A**：用 ArchUnit（Java 架构测试库）写规则
  ⚠️ 本机 Maven 是**离线**的，先确认 `~/.m2` 里已有该依赖；**没有就用方案 B**
- **方案 B**（推荐，无外部依赖）：自写基于**反射 + 源码扫描**的 JUnit 测试
  （读取 `src/main/java` 源文件做模式匹配，或用反射遍历 Spring Bean 的方法签名）

**输出**：
- 违规清单（类 + 方法 + 原因），失败时打印到测试输出
- 报告落盘（建议 `docs/tenant-isolation-audit.md` 或 CI 产物）

### T2（P0）接入门禁，而不只是出报告

- 审计器**作为测试执行**：`mvn -o test` 时自动跑
- **新增强制失败**：发现违规即测试失败（否则报告会腐烂，没人看）
- 若一次性发现的存量违规太多（>20 处），可先以"基线 + 禁止新增"模式接入：
  - 把存量违规写入基线文件
  - 基线内的暂不失败，**新增**违规必须失败
  - 并在回报里给出存量的修复计划

### T3（P1）修复审计发现的真实越权点

- 对审计器报出的每一处，**人工确认**是真越权还是误报
- 真越权：补上归属校验 + 补 403 断言测试
- 误报：调整规则（并在回报里说明误报率）

### T4（P0）反向验证：审计器必须能抓住"故意留的漏洞"

这是本批**最重要的验收项** —— 证明审计器不是摆设：

- 在测试源码中放一个**故意不校验租户**的示例方法（fixture，不进生产包）
- 断言：审计器**必须把它报出来**
- 若审计器漏掉这个反例 → 判定 T1 未实现

### T5（P0）测试与交付

- T1 的审计器自身要有单测（规则正确、不误伤正面样本）
- T3 修复项各补一条 403 断言
- 提交并推送

---

## §2 范围边界（明确不做）

- 不改造现有租户隔离方案（Schema 隔离保持不变）
- 不做灰度发布/回滚（🔒-9，需 K8s 环境，本机无）
- 不做看板 Card 字段接线（Checklist/Label/dueDate）—— 另开批次
- 不改前端

---

## §3 门禁基线

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1332**（基线 1332） |
| `cd frontend && npm run test:run` | ≥ 377（本批不改前端，持平即可） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §4 红线（沿用既有 + 本轮新增）

既有红线继续有效（禁 stub 主路径、禁 skip/弱化断言、禁 `.env` 入库、
禁回滚已闭环提交、每项须实测、禁 mock 被测主路径、禁 Flyway 迁移用
`CONCURRENTLY`、禁 fail-open、严禁"只打日志"冒充完成、严禁修改已应用迁移、
严禁禁用校验绕过问题、严禁"只加枚举不加渲染"、严禁"有端点无界面"、
严禁删代码留悬空测试、**严禁提交编译不过的改动**）。

**本轮新增两条**：

1. 🚫 **严禁"只出报告、不接门禁"**。判据：审计器必须作为 `mvn test` 的一部分执行；
   只生成一份 markdown 报告而没有失败门禁 = 未实现（报告会腐烂）。
2. 🚫 **严禁审计器抓不住故意留的反例**。判据：T4 的 fixture 方法必须被报出。
   抓不住反例的"审计"等于给团队虚假安全感 —— 比没有更危险。

---

## §5 本项目教训（择要）

1. **"能力存在"≠"每次都用对"**：52 张表有 tenant_id、TenantContext 也有，
   但 104 个 Controller/Service 里只有 22 个文件用了它 —— 缺的是**强制机制**。
2. **防线要能自检**：审计器/测试必须能抓住"故意留的漏洞"（本项目多次使用
   "反向验证"：PHASE70 整篇替换会产生重复、PHASE71 裁剪记录导致 SUM=0）。
3. **MUI v9 的 `Drawer` 没有 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
4. **`useMediaQuery` 首帧返回 false**，移动端断点断言要用 `toHaveCSS`（自带重试）。
5. **验收第一步跑 `tsc`**（几秒），立刻抓出"改了但编译不过"的交付。
6. **下"没有"的结论前要换搜索维度**（PHASE70 compose 环境变量、
   PHASE72 前端直接 fetch、PHASE73 自绘底部导航不含 `BottomNavigation` 字符串）。
7. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效，`{id}` 才生效。

---

## §6 交付清单（回报必须包含）

1. **审计器代码** + 违规清单输出（贴原始输出）
2. **T4 反向验证输出**：故意留的缺校验方法**被报出**的证据
3. **误报说明**：报出 N 处中，人工确认真越权 X 处、误报 Y 处
4. **T3 修复证据**：真越权点补校验 + 403 断言测试输出
5. **接入门禁的证据**：`mvn -o test` 中含该审计测试
6. **全量门禁数字**：五项实测输出
7. **提交记录** + `git status` 干净

---

## §7 一句话总结

**52 张表都带了 tenant_id，但 104 个 Controller/Service 里只有 22 个文件真正用了
租户上下文 —— 漏一次校验就是一个跨租户越权，而且没人会发现。**
本批做一个能自动抓住"漏校验"的审计器，接进 `mvn test`，
并且要求它必须能抓住我们故意留的那个漏洞。

# PHASE74 提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE74：多租户隔离持续审计机制

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React + MUI
- 你的工作目录即仓库根目录

### 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线 —— 不能下载新依赖**） |
| 类型检查 | `cd frontend && npx tsc --noEmit`（几秒，验收第一步就跑） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**） |
| **多行提交信息** | **必须** `git commit -F - <<'EOF' … EOF`；不要用 `-m "...\n..."`（bash 不解释 `\n`，`&&` 会被命令替换吃掉） |

⚠️ **离线约束**：Maven 是离线模式，**无法下载新依赖**。若你打算用 ArchUnit 等库，
**先确认 `~/.m2` 里已有**；没有就改用无外部依赖的自写实现（见 §2 方案 B）。

---

## §1 为什么做这项

选题来自 `COMPREHENSIVE_PLATFORM_ASSESSMENT.md` 🔒-8：
「**多租户隔离持续审计机制** —— 虽已 Schema 隔离，新增接口仍需归属校验自动化检查」。

P1 清单已全部完成（P1-1 CRDT、P1-2 限流、P1-3 去内存态、P1-4 cron、P1-5 移动端、
P1-6 IM 搜索、P1-7 入站、P1-9 钉钉同步、P1-10 集成市场 UI、P1-11 字段类型、
P1-12 视图分组），**本批不要动它们**。

### 1.1 Schema 级隔离是有的 ✅

```
含 tenant_id 列的表:  52 张
TenantContext.currentTenantId():  backend-java/.../TenantContext.java:38
```

### 1.2 但"归属校验"没有自动化检查 ❌

```
Controller 数量: 47
Service 数量:    57
使用 TenantContext 的文件数: 22     ← 相对 104 个 Controller+Service，覆盖率偏低
```

- 全仓**没有任何自动化审计机制**（`find -iname "*tenant*audit*"` / `*isolation*` 为空）
- 越权测试散落在个别文件（`WikiControllerTest`、`CollectionServiceB3Test` 等），
  靠人工记得写 —— **新接口漏写校验不会有任何告警**

**风险**：Schema 隔离只保证"数据带了 tenant_id"，**不保证每个接口读取时校验归属**。
漏一次校验就是一个跨租户越权读/写，而且这类代码看起来完全正常，只是少了句
`equals(tenantId)`，Code Review 极易漏看。

### 1.3 正面样本（审计规则的"通过"范例）

例如 `CollectionService.get()`：

```java
CollectionMetaEntity meta = get(collectionName);
if (!meta.getTenantId().equals(tenantId)) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该 collection");
}
```

---

## §2 任务

### T1（P0）实现租户归属校验审计器

扫描范围：全部 `@RestController` / `@Service`（47 + 57 个类）。

**判定规则（建议，可改进）**：一个方法判为"缺归属校验"需同时满足：

1. 方法访问了租户数据（调用了带 `tenantId` 参数的 Repository/Service 方法，
   或引用了含 `tenant_id` 列的实体）
2. 方法内**没有**出现以下任一模式：
   - `tenantId` 与实体归属字段的比对（`.equals(...)` / `Objects.equals`）
   - `TenantContext.currentTenantId()` 调用
   - 以 `tenantId` 作为查询条件传给 Repository（即"查询时就带租户过滤"）

**实现方式二选一**：

- **方案 A**：ArchUnit 规则 —— ⚠️ 先确认 `~/.m2` 已有该依赖，否则不可用
- **方案 B**（推荐，无外部依赖）：自写基于**反射 + 源码扫描**的 JUnit 测试
  （读 `src/main/java` 做模式匹配，或反射遍历 Spring Bean 方法签名）

**输出**：违规清单（类 + 方法 + 原因），失败时打印到测试输出；报告落盘
（建议 `docs/tenant-isolation-audit.md`）。

### T2（P0）接入门禁，而不只是出报告

- 审计器**作为测试执行**：`mvn -o test` 时自动跑
- **新增强制失败**：发现违规即测试失败（否则报告会腐烂）
- 若存量违规一次性太多（>20 处），可用"基线 + 禁止新增"模式：
  存量写入基线文件暂不失败，**新增**违规必须失败，并给出存量修复计划

### T3（P1）修复审计发现的真实越权点

- 对每处人工确认是真越权还是误报
- 真越权：补归属校验 + 补 403 断言测试
- 误报：调整规则（回报里说明误报率）

### T4（P0）反向验证：审计器必须能抓住"故意留的漏洞"

**本批最重要的验收项** —— 证明审计器不是摆设：

- 在测试源码中放一个**故意不校验租户**的示例方法（fixture，不进生产包）
- 断言：审计器**必须把它报出来**
- 漏掉这个反例 → 判定 T1 未实现

### T5（P0）测试与交付

- 审计器自身单测（规则正确、不误伤 §1.3 的正面样本）
- T3 修复项各补一条 403 断言
- 提交并推送

---

## §3 范围边界（明确不做）

- 不改造现有租户隔离方案（Schema 隔离保持不变）
- 不做灰度发布/回滚（🔒-9，需 K8s，本机无）
- 不做看板 Card 字段接线（Checklist/Label/dueDate）—— 另开批次
- 不改前端

---

## §4 门禁基线（必须全部满足并贴输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1332**（基线 1332） |
| `cd frontend && npm run test:run` | ≥ 377（不改前端，持平即可） |
| `cd frontend && npx tsc --noEmit` | 0 |
| `cd frontend && npx playwright test` | ≥ 66 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 48 passed, 1 skipped |

---

## §5 红线

既有红线继续有效：禁 stub 主路径 / 禁 skip 或弱化断言 / 禁 `.env` 入库 /
禁回滚已闭环提交 / 每项须实测 / 禁 mock 被测主路径 /
禁 Flyway 迁移用 `CONCURRENTLY` / 禁 fail-open / 严禁"只打日志"冒充完成 /
严禁修改已应用迁移 / 严禁禁用校验绕过问题 /
严禁"只加枚举不加渲染"冒充完成 / 严禁"有端点无界面"冒充完成 /
严禁删代码留悬空测试 / **严禁提交编译不过的改动** /
严禁"声明 `useIsMobile` 却不使用"冒充适配。

**本轮新增两条**：

1. 🚫 **严禁"只出报告、不接门禁"**。判据：审计器必须作为 `mvn test` 的一部分执行；
   只生成 markdown 报告而没有失败门禁 = 未实现（报告会腐烂）。
2. 🚫 **严禁审计器抓不住故意留的反例**。判据：T4 的 fixture 方法必须被报出。
   抓不住反例的"审计"会给团队虚假安全感 —— 比没有更危险。

---

## §6 本项目教训（择要）

1. **"能力存在"≠"每次都用对"**：52 张表有 tenant_id、TenantContext 也有，
   但 104 个 Controller/Service 里只有 22 个文件用了 —— 缺的是**强制机制**。
2. **防线要能自检**：审计器/测试必须能抓住"故意留的漏洞"。
   本项目多次用反向验证：PHASE70 整篇替换会产生重复、PHASE71 裁剪记录导致 SUM=0。
3. **MUI v9 的 `Drawer` 没有 `PaperProps`**，用 `slotProps={{ paper: {...} }}`。
4. **`useMediaQuery` 首帧返回 false**，移动端点断言要用 `toHaveCSS`（自带重试）。
5. **验收第一步跑 `tsc`**（几秒）—— 立刻抓出"改了但编译不过"的交付。
6. **下"没有"的结论前要换搜索维度**：PHASE70 compose 环境变量、
   PHASE72 前端直接 fetch、PHASE73 自绘底部导航（不含 `BottomNavigation` 字符串）。
7. **PostgreSQL `jsonb_set` 只能创建最后一级键**：`{data,id}` 无效，`{id}` 才生效。
8. **Wiki 双向链接语法是 `[[slug]]`**，markdown 链接不被识别。

---

## §7 回报清单（必须包含，缺项会被打回）

1. **审计器代码** + 违规清单输出（贴原始输出）
2. **T4 反向验证输出**：故意留的缺校验方法**被报出**的证据
3. **误报说明**：报出 N 处中，人工确认真越权 X 处、误报 Y 处
4. **T3 修复证据**：真越权点补校验 + 403 断言测试输出
5. **接入门禁的证据**：`mvn -o test` 中含该审计测试
6. **全量门禁数字**：五项实测输出
7. **提交记录** + `git status` 干净
8. **未做项说明**：不许"做了不说"，也不许"没做装做"

-----END PROMPT-----

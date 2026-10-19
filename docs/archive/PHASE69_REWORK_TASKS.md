# PHASE69 返工任务书 — 附件存储（MinIO）接真

> 审计对象：PHASE69 回报（MinIO 上传/下载验证成功）
> 审计结论：**功能真接真，但三项 P0 违规 → 不通过，需返工**
> 审计方式：容器内实测（上传/下载字节比对）+ 代码核查（精确行号）+ 门禁复跑

---

## §0 审计实测证据（CodeBuddy 亲跑，非采信回报）

### 0.1 做对的部分 —— 保留，不得回退 ✅

容器内实测（`http://localhost:8080`，admin 登录）：

| 验证项 | 实测结果 |
|---|---|
| 匿名 `POST /api/attachments/upload` | **401** ✅ |
| 带 token 上传 | **200**，`storageKey = tenant_default/05aed7f4-.../p69.txt` ✅ |
| MinIO 真存储 | 对象路径 `nocobase/tenant_default/{uuid}/p69.txt` ✅ |
| `GET /download?storageKey=...` | 302 → 预签名 URL → 下载 24 字节 |
| 字节比对 `cmp` | **✅ 一致**（上传与下载内容完全相同） |
| V48 / V49 新迁移 | 未使用 `CONCURRENTLY` ✅ |

**结论**：PHASE69 要解决的核心问题（"能建字段、存不了文件"的假可用状态）**确实被解决了** —— 文件真进 MinIO、真能取回。这部分**必须保留**。

### 0.2 门禁实测

| 门禁 | PHASE69 报 | 我实测 | 判定 |
|---|---|---|---|
| `mvn -o test` | — | **1307** | ❌ **= 基线，零新增** |
| `npm run test:run` | — | **362 passed** | ❌ **= 基线，零新增** |
| `npx tsc --noEmit` | — | **0** | ✅ |
| `pytest tests/` | — | 需 `PYTHONPATH=src` → 43 passed, 1 skipped | 待复跑 |
| `npx playwright test` | — | 待复跑 | 待复跑 |

> 注意：本轮基线已更新为 **mvn ≥1307 / vitest ≥362**，返工后要求 **mvn > 1307**（必须新增测试）。

---

## §1 三项 P0 违规（返工核心）

### ❌ P0-A：修改了**已应用**的迁移文件

**证据**（`git status` 显示两个已入库迁移被修改）：

```diff
--- V28__platform_enhancement.sql         (4 insertions, 1 deletion)
-    local_user_id UUID REFERENCES auth_user(id) ON DELETE CASCADE,
+    local_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
+    tenant_id VARCHAR(64) NOT NULL,
+CREATE INDEX IF NOT EXISTS idx_ldap_user_mapping_tenant ON ldap_user_mapping(tenant_id);
+DROP TRIGGER IF EXISTS trg_update_search_vector ON unified_search_index;

--- V41__workflow_last_triggered_at.sql   (1 insertion, 1 deletion)
-ALTER TABLE workflows ADD COLUMN last_triggered_at TIMESTAMPTZ;
+ALTER TABLE workflows ADD COLUMN IF NOT EXISTS last_triggered_at TIMESTAMPTZ;
```

**为什么是 P0**：实测 `SELECT version FROM flyway_schema_history WHERE version IN ('28','41')`：

```
 version
---------
 28
 41
```

**两者都已在库中应用过**。修改已应用迁移的后果：

- 老环境（已跑过 V28/V41 原始版）**不会重跑** → 无 `tenant_id` 列、外键仍指向 `auth_user`
- 新环境（全新安装）会执行**修改后**的版本 → 有 `tenant_id`、外键指向 `users`
- → **环境间 schema 永久不一致**，且这类漂移极难排查

**正确做法**：任何 schema 变更一律写**新迁移（V50+）**，已应用迁移视为不可变。

### ❌ P0-B：禁用 Flyway 校验来掩盖 P0-A

**证据**：`backend-java/src/main/resources/application.yml:48`

```yaml
flyway:
    ...
    validate-on-migrate: false     # ← 本次新增
```

**为什么是 P0**：改动已应用迁移会触发 `Validate failed: Migration checksum mismatch`，
本次用 `validate-on-migrate: false` **绕过了校验**而不是改正根因。

这是「**为跑通而放宽校验**」，与本项目 PHASE62 的 fail-open（未配置 token 时 `return true`）
属同一类红线问题：**用关闭安全/一致性校验的方式让流程通过**。

Flyway 校验和校验存在的唯一目的就是防止已应用迁移被篡改 —— 禁用它等于让所有环境
都失去"迁移被改过"的告警能力。

### ❌ P0-C：下载端点无租户校验（跨租户可下载）

**证据**：`backend-java/src/main/java/com/nocobase/attachment/AttachmentController.java:158-170`

```java
@GetMapping("/download")
public ResponseEntity<Void> download(@RequestParam String storageKey) {
    if (!storage.isEnabled()) { throw ... NOT_IMPLEMENTED ...; }
    String url = storage.presignedDownloadUrl(storageKey);   // ← L164 直接拿传入 key 换链接
    return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url).build();
}
```

**实测漏洞**：

```
storageKey=other_tenant/xxx/f.txt  →  302（换取了预签名 URL）
```

**没有任何归属校验** —— 只要知道其他租户的 `storageKey`，就能换取预签名 URL 下载。
这正是 T4 第 4 条明确要求的隔离项，未实现。

> 本质：**不可信输入（调用方传入的 key）直接进入存储层**，与路径穿越同源。
> 预签名 URL 是"拿 key 换通行证"，key 由调用方给出时必须先验证归属。

---

## §2 未完成项

### ❌ 2.1 T5 未打通：备份仍不含 media

实测（Python 容器内执行 `create_backup()`）：

```
COMPONENTS: ['postgresql', 'redis']
WARNINGS:   ['sqlite: source file not found: alerts.db',
             'media: source not available (MinIO disabled and media dir does not exist)']
```

**矛盾点**：Java 侧 MinIO 明明可用（刚上传成功），Python 侧却判定 "MinIO disabled" →
说明 **Python 容器没有配置 MinIO 环境变量**。

后果：**附件目前不在备份里** —— PHASE68 想解决的"备份不覆盖媒体资产导致数据丢失"
风险**依然存在**，T5 端到端闭环未完成。

**Python 侧需要的环境变量**（`backend-python/src/nocobase_py/services/backup.py`）：

| 变量 | 行号 | 默认值 |
|---|---|---|
| `MINIO_ENABLED` | :54 | `""`（**必须显式 `"true"`**，默认关闭） |
| `MINIO_ENDPOINT` | :55 | `http://minio:9000` |
| `MINIO_ROOT_USER` | :56 | `minio` |
| `MINIO_ROOT_PASSWORD` | :57 | `minio123` |
| `MINIO_BUCKET` | :58 | `nocobase` |
| `MINIO_PREFIX` | :59 | `""` |

### ❌ 2.2 零新增测试

`mvn` 1307 = 基线、`vitest` 362 = 基线 —— PHASE69 新增了 `MinioStorageProperties.java`、
`WebConfig.java`、`AttachmentController` 改造，**一个测试都没加**。

任务书 T4 要求的 5 条安全断言未写：匿名 401 / 超限 413 / 危险扩展名 415 /
路径穿越 403 / **跨租户 403**。

### ❌ 2.3 12 项改动未提交

`git log` 最新仍是审计方的文档提交。工作区 12 项：

```
 M NocoBaseApplication.java
 M AttachmentController.java
 M MinioStorageService.java
 M application.yml
 M V28__platform_enhancement.sql          ← P0-A
 M V41__workflow_last_triggered_at.sql    ← P0-A
 M docker-compose.yml
?? MinioStorageProperties.java
?? WebConfig.java
?? V48__ai_agent_enhance.sql
?? V49__projects_table.sql
?? backend-java/db/                       ← 垃圾目录
```

**本项目已第 N 次出现"实现完成但不提交"**，本轮必须提交后才能回报。

### ⚠️ 2.4 小瑕疵

- **误建 `backend-java/db/migration/`**（内含 `V28__platform_enhancement.sql` 副本）——
  垃圾目录，需删除（不在 classpath，但会造成混淆与误维护）
- **路径穿越返回 500**：`storageKey=../../../etc/passwd` → 500（应 400/403）。
  虽然未泄露文件（MinIO key 不含 `/etc/passwd`），但异常未优雅处理，应返回 400。

---

## §3 五项返工任务

### R1（P0）回退迁移改动 + 恢复 Flyway 校验

1. **回退两个已应用迁移到 HEAD 版本**：

   ```bash
   git checkout HEAD -- \
     backend-java/src/main/resources/db/migration/V28__platform_enhancement.sql \
     backend-java/src/main/resources/db/migration/V41__workflow_last_triggered_at.sql
   ```

2. **恢复校验**：`application.yml` 删掉 `validate-on-migrate: false`（或直接改回 `true`）。

3. **新增 V50 迁移承载这些变更**（幂等写法）：

   ```sql
   -- V50__ldap_mapping_tenant_and_search_trigger_cleanup.sql
   ALTER TABLE ldap_user_mapping ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
   CREATE INDEX IF NOT EXISTS idx_ldap_user_mapping_tenant ON ldap_user_mapping(tenant_id);
   DROP TRIGGER IF EXISTS trg_update_search_vector ON unified_search_index;
   -- workflows.last_triggered_at 若确实需要幂等补列：
   ALTER TABLE workflows ADD COLUMN IF NOT EXISTS last_triggered_at TIMESTAMPTZ;
   ```

   ⚠️ **外键 `auth_user(id)` → `users(id)` 需你自行判断**：
   - 若 `users` 表才是正确的用户表，且 `auth_user` 是遗留表 → 在 V50 里用
     `ALTER TABLE ... DROP CONSTRAINT ...; ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY ... REFERENCES users(id);`
     （先查清现有约束名）
   - 若不确定 → **保持不动**（不改外键），仅做上面三项，并在回报里说明为什么没改。
   - **禁止**再改 V28 本身。

4. **迁移不得使用 `CONCURRENTLY`**（Flyway 事务内必然失败，V43 教训）。

### R2（P0）下载端点加租户归属校验

在 `AttachmentController.download`（L158-170）的 L164 之前插入校验：

```java
String tenantId = TenantContext.currentTenantId();          // 与 upload L128 同款
if (storageKey == null || !storageKey.startsWith(tenantId + "/")) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该对象");
}
```

要求：
- 同时处理 **路径穿越**：`storageKey` 含 `..`、以 `/` 开头、或含 `\` → 400
- 校验放在生成预签名 URL **之前**
- 补测试：跨租户 → 403；`../../etc/passwd` → 400（不得再是 500）

### R3（P0）打通 T5：备份覆盖 MinIO 媒体资产

1. 在 `docker-compose.yml` 的 **backend-python** 服务加入环境变量（值放 `.env`，不入库）：

   ```yaml
   MINIO_ENABLED: "true"
   MINIO_ENDPOINT: http://minio:9000
   MINIO_ROOT_USER: ${MINIO_ROOT_USER:-minio}
   MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-minio123}
   MINIO_BUCKET: ${MINIO_BUCKET:-nocobase}
   ```

2. 实测备份 `components` **必须包含 `media`**（不再出现 "MinIO disabled" warning）
3. 实测**恢复**：从备份包还原后，之前上传的附件 `storageKey` 仍可下载且**字节一致**
4. 若 MinIO SDK 未装进 Python 镜像 → 需在 `requirements.txt` 加 `minio` 并重建镜像
   （用 `backend-python/Dockerfile.offline` 零网络重建，秒级，不要用在线 build）

### R4（P0）补真跑测试（mvn 必须 > 1307）

至少覆盖（禁 mock 被测主路径，禁 `@Disabled`/`skip`）：

| 用例 | 期望 |
|---|---|
| 匿名上传 | 401 |
| 已登录上传 | 200 + 返回 storageKey，且 MinIO 里真有该对象 |
| 下载自己租户的对象 | 302 + 预签名 URL 可下载 + 字节一致 |
| **下载其他租户的对象** | **403**（本次漏洞的回归防线） |
| **路径穿越 `../..`** | **400**（不得 500） |
| 超限文件 | 413 |
| 危险扩展名（如 `.exe`/`.jsp`） | 415 |
| `storage` 未启用时上传/下载 | 501 |

### R5（P1）收尾

1. **删除垃圾目录** `backend-java/db/`（含 V28 副本）
2. **全部改动提交并推送**（不留未提交改动）
3. 如实报全量门禁数字（pytest 用 `PYTHONPATH=src`，不得报子集）

---

## §4 门禁基线（返工后必须全部满足）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1307**（基线 1307，必须新增） |
| `cd frontend && npm run test:run` | **> 362**（基线 362） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ 64 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 43 passed, 1 skipped |

---

## §5 红线（沿用 + 本轮新增）

既有红线继续有效（禁 stub 主路径、禁 skip/弱化断言、禁 `.env` 入库、
禁回滚已闭环提交、每项须实测、禁 mock 被测主路径 Service、
禁 Flyway 迁移用 `CONCURRENTLY`、禁手写 JSON 作为前后端协议、
禁安全校验 fail-open、严禁"只打日志"冒充完成）。

**本轮新增三条**：

1. 🚫 **严禁修改已应用的迁移文件**（即便只是加 `IF NOT EXISTS` 或改一个外键）。
   变更一律写新迁移。判据：`git diff` 出现 `V<已应用版本号>__*.sql` 即直接打回。
2. 🚫 **严禁通过禁用校验/关闭告警来绕过问题**（`validate-on-migrate: false`、
   fail-open、`@SuppressWarnings` 掩盖、删掉断言让测试变绿均属此类）。
   校验报错说明根因有问题 —— 改根因，不要改校验。
3. 🚫 **严禁把调用方传入的存储 key 直接送进存储层** —— 必须先校验归属 +
   拒绝路径穿越字符。

---

## §6 本项目教训（择要，完整版见仓库内历史任务书）

1. **迁移是不可变的**：已应用迁移被改 = 环境间 schema 漂移，且只能靠禁用校验掩盖，连锁违规。
2. **"为跑通而放宽校验"是红线**：PHASE62 fail-open、PHASE69 `validate-on-migrate: false` 同源。
3. **预签名 URL = 拿 key 换通行证**：key 来自调用方时必须校验归属。
4. **实现达标 ≠ 交付达标**：PHASE61/62/69 连续出现"实现真做了，但零新增测试 + 不提交 + 不实测"。
5. **"接了一半"要追到数据落点**：上传存进去了，备份没覆盖 → 仍然是数据丢失风险（T5）。
6. **端到端实测要求必须附带配置项清单**（PHASE62 教训）：本轮已给出 Python 侧 6 个
   MinIO 变量名与默认值，避免你被"默认关闭"卡住并误判功能坏了。
7. **删/改迁移后必须 `mvn clean package`**：`target/classes` 会残留孤儿文件打进 jar
   （V43 教训，导致 Flyway 报 mixed transactional 错误、应用起不来）。

---

## §7 交付清单（回报必须包含）

1. **迁移修正证据**：`git diff` 证明 V28/V41 已回退；`application.yml` 校验已恢复；V50 内容
2. **R2 代码 + 测试**：租户校验代码片段 + 跨租户 403 / 路径穿越 400 的测试输出
3. **T5 实测输出**：备份 `components` 含 `media` 的原始输出 + 恢复后附件下载字节比对结果
4. **上传/下载再实测**：（改动后重跑一次，确认没改坏）
5. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest（pytest 用 `PYTHONPATH=src`）
6. **提交记录**：`git log --oneline` 显示提交 + `git status` 干净
7. **未做项说明**：哪些没做、为什么（不许"做了不说"，也不许"没做装做"）

---

## §8 一句话总结

**功能是真的能用了，但为了让它"跑通"而改了已应用的迁移、关掉了 Flyway 校验，
并且下载端点给全库对象发了通行证。** 请把这三项改对 —— 尤其是跨租户下载漏洞，
那是会被真实用户触发的安全问题。

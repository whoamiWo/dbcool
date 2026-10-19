# PHASE69 返工提示词（投喂给 Kilo Code / GLM-5.3）

复制下方 `-----BEGIN PROMPT-----` 与 `-----END PROMPT-----` 之间的**全部内容**，整段投喂。
（不依赖任何外部文件，可直接执行。）

-----BEGIN PROMPT-----

# PHASE69 返工：附件存储（MinIO）—— 修三项 P0 违规 + 打通备份闭环

## §0 工作目录与背景

- 仓库：`/home/who/multistack-project`（企业级协作平台 DBCool）
- 技术栈：Spring Boot 3（Java 17）+ PostgreSQL + Redis + MinIO + React 19 + Python 服务
- 你的工作目录即仓库根目录

### 项目环境速查（前人踩过的坑，直接照做可省很多时间）

| 事项 | 正确做法 |
|---|---|
| 跑后端测试 | `cd backend-java && mvn -o test`（**离线**，不要联网） |
| 跑前端测试 | `cd frontend && npm run test:run` |
| 类型检查 | `cd frontend && npx tsc --noEmit` |
| E2E | `cd frontend && npx playwright test` |
| 跑 Python 测试 | `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`（**必须加 `PYTHONPATH=src`**，否则 `ModuleNotFoundError: nocobase_py`） |
| 重建 Java 镜像 | `cd backend-java && docker build -f Dockerfile.offline -t nocobase-backend-java:latest .`（**零网络，秒级**；不要用在线 Dockerfile，会超时） |
| 打包 | `cd backend-java && mvn -o clean package -DskipTests`（**必须 clean**：删/改迁移后 `target/classes` 会残留孤儿文件打进 jar，导致 Flyway 报 mixed transactional 错误、应用起不来） |
| 校验 jar 内迁移 | `python3 -c "import zipfile; z=zipfile.ZipFile('target/nocobase-backend-0.0.1-SNAPSHOT.jar'); print([n for n in z.namelist() if '/db/migration/' in n])"` |
| 登录取 token | `curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` → `data.access_token` |
| 敏感配置 | 一律放 `.env`（**已 gitignore，禁止提交**），`docker-compose.yml` 用 `${VAR:-default}` 引用 |

---

## §1 为什么被打回（审计实测证据，不是猜测）

### 1.1 先说你做对的 —— 这些必须保留，不要回退

审计方在容器内亲测：

| 验证项 | 实测 |
|---|---|
| 匿名 `POST /api/attachments/upload` | **401** ✅ |
| 带 token 上传 | **200**，`storageKey = tenant_default/05aed7f4-.../p69.txt` ✅ |
| MinIO 真存储 | 对象在 `nocobase/tenant_default/{uuid}/p69.txt` ✅ |
| `GET /download?storageKey=...` | 302 → 预签名 URL → 下载成功 |
| 字节比对 `cmp` | **✅ 上传与下载内容完全一致** |
| V48/V49 新迁移 | 未用 `CONCURRENTLY` ✅ |

**核心问题（"能建字段、存不了文件"的假可用状态）确实被解决了。** 上传/下载主链路
不要动，本轮只修下面三项 P0 + 打通备份。

### 1.2 ❌ P0-A：你修改了**已应用**的迁移文件

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

实测 `SELECT version FROM flyway_schema_history WHERE version IN ('28','41')` 返回
**两行都有** —— 它们**都已经在数据库里跑过了**。

修改已应用迁移的后果：
- 老环境（跑过原始版）**不会重跑** → 没有 `tenant_id` 列、外键仍指 `auth_user`
- 新环境（全新安装）执行修改版 → 有 `tenant_id`、外键指 `users`
- → **环境间 schema 永久不一致**，且极难排查

**正确做法**：schema 变更一律写**新迁移（V50+）**；已应用迁移视为不可变。

### 1.3 ❌ P0-B：你禁用了 Flyway 校验来掩盖 P0-A

`backend-java/src/main/resources/application.yml:48` 新增了：

```yaml
flyway:
    validate-on-migrate: false
```

改已应用迁移会触发 `Validate failed: Migration checksum mismatch`，你用
`validate-on-migrate: false` **绕过了校验**而不是改正根因。

Flyway 校验和存在的唯一目的就是防止已应用迁移被篡改 —— 禁用它，所有环境都失去
"迁移被改过"的告警能力。这与本项目此前 PHASE62 的 fail-open（未配置 token 时
`return true`）是同一类问题：**用关闭校验的方式让流程通过**。

### 1.4 ❌ P0-C：下载端点无租户校验 —— 跨租户可下载

`backend-java/src/main/java/com/nocobase/attachment/AttachmentController.java:158-170`：

```java
@GetMapping("/download")
public ResponseEntity<Void> download(@RequestParam String storageKey) {
    if (!storage.isEnabled()) { throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ...); }
    String url = storage.presignedDownloadUrl(storageKey);   // ← L164：调用方传啥就换啥
    return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url).build();
}
```

实测漏洞：`storageKey=other_tenant/xxx/f.txt` → **302**（拿到了预签名 URL）。

没有任何归属校验 —— **知道别人的 `storageKey` 就能下载别人的附件**。
这是 T4 第 4 条明确要求的隔离项，未实现。

> 本质：预签名 URL 是"拿 key 换通行证"，key 由调用方给出时必须先验证归属。

### 1.5 ❌ T5 未打通：备份仍不含媒体资产

Python 容器内执行 `create_backup()`：

```
COMPONENTS: ['postgresql', 'redis']
WARNINGS:   ['sqlite: source file not found: alerts.db',
             'media: source not available (MinIO disabled and media dir does not exist)']
```

矛盾点：Java 侧 MinIO 明明可用（刚上传成功），Python 侧却判定 "MinIO disabled" →
**Python 容器没配 MinIO 环境变量**。

后果：**附件不在备份里**，PHASE68 想解决的"备份不覆盖媒体资产导致数据丢失"风险
**依然存在**，T5 端到端闭环未完成。

### 1.6 ❌ 零新增测试 + ❌ 12 项未提交

- `mvn -o test` = **1307**（= 基线，零新增）、`npm run test:run` = **362**（= 基线，零新增）
- `git log` 最新仍是审计方的文档提交；工作区 12 项改动未提交

### 1.7 ⚠️ 小瑕疵

- 误建 `backend-java/db/migration/`（含 `V28__platform_enhancement.sql` 副本）→ 删除
- 路径穿越 `storageKey=../../../etc/passwd` → **500**（应 400/403）

---

## §2 五项返工任务

### R1（P0）回退迁移改动 + 恢复 Flyway 校验

1. 回退两个已应用迁移：

```bash
git checkout HEAD -- \
  backend-java/src/main/resources/db/migration/V28__platform_enhancement.sql \
  backend-java/src/main/resources/db/migration/V41__workflow_last_triggered_at.sql
```

2. 删除 `application.yml:48` 的 `validate-on-migrate: false`（恢复默认校验，或直接写 `true`）。

3. 新增 `V50__ldap_mapping_tenant_and_search_trigger_cleanup.sql` 承载这些变更（幂等）：

```sql
ALTER TABLE ldap_user_mapping ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_ldap_user_mapping_tenant ON ldap_user_mapping(tenant_id);
DROP TRIGGER IF EXISTS trg_update_search_vector ON unified_search_index;
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS last_triggered_at TIMESTAMPTZ;
```

⚠️ **外键 `auth_user(id)` → `users(id)` 需你自行判断**：
- 若确认 `users` 才是正确的用户表 → 在 V50 里 `DROP CONSTRAINT` + `ADD CONSTRAINT ... REFERENCES users(id)`（先查清现有约束名）
- 若不确定 → **保持不动**，并在回报里说明为什么没改
- **禁止**再改 V28 本身

4. 迁移**不得使用 `CONCURRENTLY`**（Flyway 事务内必然失败）。

5. 改完必须 `mvn -o clean package -DskipTests`，用 §0 的 zipfile 方法确认 jar 内
   V28/V41 是回退版、V50 存在、无 V43 之类残留。

### R2（P0）下载端点加租户归属校验

在 `AttachmentController.download` **L164 之前**插入：

```java
String tenantId = TenantContext.currentTenantId();   // 与 upload L128 同款
if (storageKey == null || !storageKey.startsWith(tenantId + "/")) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该对象");
}
```

要求：
- 同时拒绝**路径穿越**：含 `..`、以 `/` 开头、含 `\` → **400**（不得再是 500）
- 校验必须在生成预签名 URL **之前**
- 补测试：跨租户 → **403**；路径穿越 → **400**

### R3（P0）打通 T5：备份覆盖 MinIO 媒体资产

Python 侧需要的环境变量（`backend-python/src/nocobase_py/services/backup.py`）：

| 变量 | 行号 | 默认值 | 说明 |
|---|---|---|---|
| `MINIO_ENABLED` | :54 | `""` | **必须显式 `"true"`**，默认关闭 |
| `MINIO_ENDPOINT` | :55 | `http://minio:9000` | |
| `MINIO_ROOT_USER` | :56 | `minio` | |
| `MINIO_ROOT_PASSWORD` | :57 | `minio123` | |
| `MINIO_BUCKET` | :58 | `nocobase` | |
| `MINIO_PREFIX` | :59 | `""` | 对象前缀 |

1. 在 `docker-compose.yml` 的 **backend-python** 服务加入这些变量（值放 `.env`，不入库）：

```yaml
      MINIO_ENABLED: "true"
      MINIO_ENDPOINT: http://minio:9000
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-minio}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-minio123}
      MINIO_BUCKET: ${MINIO_BUCKET:-nocobase}
```

2. 实测：`create_backup()` 的 `components` **必须包含 `media`**，且不再出现
   "MinIO disabled" warning（贴原始输出）
3. 实测**恢复**：还原后，之前上传的附件 `storageKey` 仍可下载且**字节一致**（`cmp` 比对）
4. 若 Python 镜像缺 `minio` SDK → 加进 `requirements.txt` 并用
   `backend-python/Dockerfile.offline` 重建（零网络，秒级）

### R4（P0）补真跑测试（mvn 必须 > 1307）

覆盖下表（**禁 mock 被测主路径、禁 `@Disabled` / `it.skip` / 弱化断言**）：

| 用例 | 期望 |
|---|---|
| 匿名上传 | 401 |
| 已登录上传 | 200 + 返回 storageKey，且 MinIO 中真存在该对象 |
| 下载自己租户的对象 | 302 + 预签名 URL 可下载 + 字节一致 |
| **下载其他租户的对象** | **403**（本次漏洞的回归防线） |
| **路径穿越 `../..`** | **400**（不得 500） |
| 超限文件 | 413 |
| 危险扩展名（`.exe`/`.jsp` 等） | 415 |
| storage 未启用时上传/下载 | 501 |

### R5（P1）收尾

1. 删除垃圾目录 `backend-java/db/`（含 V28 副本）
2. **全部改动提交并推送**（`git status` 必须干净）
3. 如实报全量门禁数字（pytest 用 `PYTHONPATH=src`，**不得报子集**）

---

## §3 门禁基线（返工后必须全部满足，并贴实测输出）

| 门禁 | 要求 |
|---|---|
| `cd backend-java && mvn -o test` | **> 1307**（基线 1307，必须新增测试） |
| `cd frontend && npm run test:run` | **> 362**（基线 362） |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ 64 |
| `cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider` | 43 passed, 1 skipped |

---

## §4 红线

既有红线继续有效：禁 stub 主路径 / 禁 skip 或弱化断言 / 禁 `.env` 入库 /
禁回滚已闭环提交 / 每项须实测 / 禁 mock 被测主路径 Service /
禁 Flyway 迁移用 `CONCURRENTLY` / 禁手写 JSON 作为前后端协议 /
禁安全校验 fail-open / 严禁"只打日志"冒充完成。

**本轮新增三条**：

1. 🚫 **严禁修改已应用的迁移文件**（即便只是加 `IF NOT EXISTS` 或改一个外键）。
   变更一律写新迁移。判据：`git diff` 出现 `V<已应用版本号>__*.sql` 即直接打回。
2. 🚫 **严禁通过禁用校验/关闭告警来绕过问题**（`validate-on-migrate: false`、
   fail-open、删断言让测试变绿均属此类）。校验报错说明根因有问题 —— **改根因，不改校验**。
3. 🚫 **严禁把调用方传入的存储 key 直接送进存储层** —— 必须先校验归属 + 拒绝路径穿越字符。

---

## §5 本项目教训（择要）

1. **迁移不可变**：已应用迁移被改 = 环境间 schema 漂移，只能靠禁用校验掩盖，连锁违规。
2. **"为跑通而放宽校验"是红线**（PHASE62 fail-open、PHASE69 `validate-on-migrate: false` 同源）。
3. **预签名 URL = 拿 key 换通行证**：key 来自调用方必须校验归属。
4. **实现达标 ≠ 交付达标**：PHASE61/62/69 连续出现"实现真做了，但零新增测试 + 不提交 + 不实测"。
5. **"接了一半"要追到数据落点**：上传存进 MinIO 了，备份没覆盖 → 仍是数据丢失风险（T5）。
6. **删/改迁移后必须 `mvn clean package`**：`target/classes` 残留孤儿文件会打进 jar，
   导致 Flyway 报 mixed transactional 错误、应用起不来（V43 教训）。
7. **判断"存储类改造是否真完成"三步**：① 文件真进对象存储 ② 有真跑的测试
   ③ 备份能覆盖且能恢复 —— 缺任何一步都不能算完成。

---

## §6 回报清单（必须包含，缺项会被打回）

1. **迁移修正证据**：`git diff` 证明 V28/V41 已回退；`application.yml` 校验已恢复；V50 内容
2. **R2 代码 + 测试**：租户校验代码片段 + 跨租户 403 / 路径穿越 400 的测试输出
3. **T5 实测输出**：备份 `components` 含 `media` 的原始输出 + 恢复后附件下载字节比对结果
4. **上传/下载再实测**：改动后重跑一次（确认没改坏），贴输出
5. **全量门禁数字**：mvn / vitest / tsc / playwright / pytest 五项实测输出
6. **提交记录**：`git log --oneline` + `git status`（必须干净）
7. **未做项说明**：哪些没做、为什么 —— 不许"做了不说"，也不许"没做装做"

-----END PROMPT-----

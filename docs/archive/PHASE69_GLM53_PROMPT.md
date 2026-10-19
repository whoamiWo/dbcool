# PHASE69 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。任务书见仓库根目录 `PHASE69_GLM53_ATTACHMENT_STORAGE_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。本批（PHASE69）**接真附件存储（MinIO）**。

## 0. 背景与现状（CodeBuddy 实测）

PHASE68 审计发现：**附件上传返回 501（对象存储未启用）**，而 `FieldDef` 已有
`attachment` 字段类型、PHASE63 也做了列映射 → 用户**能建附件字段，但文件存不进去**（假可用）。

### 好消息：MinIO 封装**已经存在，直接复用，不要另写一套**
```
backend-java/src/main/java/com/nocobase/attachment/MinioStorageService.java
  :69  isEnabled()   :79 upload(tenantId, originalName, contentType, ...) → storageKey
  :98  presignedDownloadUrl(storageKey)   :112 requireEnabled()   :119 sanitize(name)
```
配置已就绪（`application.yml:87-92`）：
```yaml
storage.minio:
  enabled: ${MINIO_ENABLED:false}   # ← 默认关闭
  endpoint: ${MINIO_ENDPOINT:}      access-key: ${MINIO_ACCESS_KEY:}
  secret-key: ${MINIO_SECRET_KEY:}  bucket: ${MINIO_BUCKET:nocobase}
```
Wiki 附件已接（`WikiAttachmentService:87` 用 `storage.presignedDownloadUrl(...)`）。
compose 已有 MinIO 服务（`docker-compose.yml:53-61`）。

### 真正没接的：通用附件 API
```java
// AttachmentController.java（150 行）
:52 @PostMapping("/metadata")   :90 @GetMapping("/{storageKey}")   :101 @PostMapping("/upload")
:63  // Week 42+: 这里会调 MinIO.putObject() ...        ← TODO
:93  "附件实际存储 Week 42+ D1.4 实现 (MinIO SDK + 预签名 URL)"   ← 未实现
```

### 待你先确认（T1）
- `MinioStorageService` **是否自动创建 bucket**？未见 `bucketExists`/`makeBucket` → 若不会，需补 `ensureBucket()`，否则首次上传因 bucket 不存在失败
- `upload(...)` 完整签名（第 4 个参数起）

## 1. 任务

### T1（P0）启用 MinIO + bucket 保障
compose 为 backend-java 注入（**值放 `.env`，不入库**）：
```
MINIO_ENABLED=true
MINIO_ENDPOINT=http://minio:9000     # ← 容器内部用**服务名**，禁 localhost
MINIO_ACCESS_KEY=${MINIO_ROOT_USER:-minio}
MINIO_SECRET_KEY=${MINIO_ROOT_PASSWORD:-minio123}
MINIO_BUCKET=${MINIO_BUCKET:-nocobase}
```
补齐 bucket 保障（幂等 `ensureBucket()`，失败要明确日志，不静默）。实测 `isEnabled()` 为 true、端点不再 501。

### T2（P0）接真 `POST /api/attachments/upload`
复用 `MinioStorageService.upload(...)`（**不要另写 S3 客户端**），返回真实 storageKey 与
metadata（原文件名/类型/大小/租户），移除 :63 TODO 与 :93 未实现文案；metadata 落库（可复用 :52 `/metadata`），保证上传后可查。

### T3（P0）下载可用
`GET /api/attachments/{storageKey}` 返回预签名 URL 或文件流；实测**下载字节与上传一致**（比对 size/hash）。

### T4（P0）安全四项（不可省）
1. 上传**必须认证**（匿名 401）+ 租户上下文
2. 大小/类型限制（可配），超限 400
3. 防路径穿越（走 `sanitize()`），`GET` 不得读越界 key
4. **租户隔离**：storageKey 含 tenantId；A 租户不得下载 B 租户附件（403/404）
5. 预签名 URL **有有效期**，不得永久

### T5（P0）端到端闭环（串起 PHASE68 备份）
上传真实附件 → 下载校验（字节一致）→ **备份（components 含 `media`）** →
**恢复到独立目标**（bucket/前缀隔离，严禁覆盖原数据）→ 恢复后附件仍可下载且字节一致。

## 2. 门禁基线
| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1307**（T2/T4 需新增）/ 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **48 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 3. 红线（违反即打回）
1. **严禁硬编码 MinIO 凭据**（一律环境变量，`.env` 不入库）
2. **容器内部访问用服务名**（`http://minio:9000`），禁 `localhost`
3. **严禁匿名上传 / 无租户隔离 / 永久预签名 URL**
4. **严禁 mock 被测主路径 Service**
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
7. **严禁**回滚已闭环提交（PHASE58–68）
8. **严禁改动共享环境既有账号凭据**（PHASE67 已发生过）
9. **改动必须提交并推送**（`git status --porcelain` 为空）
10. **每项必须给出实测输出**（curl + 字节/hash 比对 + 备份/恢复证据）

## 4. 教训
1. **"能建字段" ≠ "能存文件"** —— 字段类型与列映射都有、存储没接真 = 假可用（与本项目 5 次假完成同源）
2. **先查有没有现成封装** —— 本次 `MinioStorageService` 已存在，直接复用
3. **修好代码 ≠ 能力可用** —— 端到端实测（上传→下载→备份→恢复→读取）
4. **实测前确认容器跑最新代码**（PHASE67 我踩过旧镜像导致误判）
5. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**commit message 含引号用 `git commit -F 文件`**

## 5. 回报必须给出（缺项打回）
1. T1：注入项 + bucket 保障 + `isEnabled()` 实测 true
2. T2：upload 接真 + 上传实测（返回 storageKey）
3. T3：下载实测（字节/hash 一致）
4. T4：安全五项逐条实测（匿名 401 / 超限 400 / 路径穿越 / **跨租户 403 或 404** / 预签名有效期）
5. T5：备份含 media + 恢复后附件可读且字节一致
6. 五项门禁数字
7. 改动清单 + `git log --oneline`（**已推送**，status 空）
8. 说明哪些未做及原因

-----END PROMPT-----

## 投喂方式
1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：亲自上传真附件 → 下载比对字节 → 备份含 media → 恢复后可读，
   并验证匿名 401 与**跨租户不可下载**

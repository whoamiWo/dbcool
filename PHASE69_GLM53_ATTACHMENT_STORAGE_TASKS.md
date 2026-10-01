# PHASE69 任务需求单：接真附件存储（MinIO）（交 Kilo Code + GLM-5.3，CodeBuddy 审计）

> 来源：PHASE68 审计发现的上游功能缺口 —— **附件上传返回 501（对象存储未启用）**，
> 而 `FieldDef` 已有 `attachment` 字段类型（PHASE63 也做了列映射），
> 属"用户能建附件字段、但文件存不进去"的假可用状态。

---

## 一、现状（CodeBuddy 实测，含行号）

### 1.1 好消息：MinIO 封装**已经存在**，不用从零写

```
backend-java/src/main/java/com/nocobase/attachment/MinioStorageService.java
  :69  isEnabled()
  :79  upload(tenantId, originalName, contentType, ...) → storageKey
  :98  presignedDownloadUrl(storageKey)
  :112 requireEnabled()      :119 sanitize(name)
```

配置也已就绪（`backend-java/src/main/resources/application.yml:87-92`）：

```yaml
storage:
  minio:
    enabled:    ${MINIO_ENABLED:false}      # ← 默认关闭
    endpoint:   ${MINIO_ENDPOINT:}
    access-key: ${MINIO_ACCESS_KEY:}
    secret-key: ${MINIO_SECRET_KEY:}
    bucket:     ${MINIO_BUCKET:nocobase}
```

Wiki 附件已经接了（`WikiAttachmentService:87` 调 `storage.presignedDownloadUrl(...)`）。

### 1.2 真正没接的：通用附件 API

```java
// backend-java/src/main/java/com/nocobase/attachment/AttachmentController.java（150 行）
:37  @RequestMapping("/api/attachments")
:52  @PostMapping("/metadata")
:90  @GetMapping("/{storageKey}")
:101 @PostMapping("/upload")
:63     // Week 42+: 这里会调 MinIO.putObject() 然后存 metadata 到 records   ← TODO
:93     "附件实际存储 Week 42+ D1.4 实现 (MinIO SDK + 预签名 URL)"            ← 未实现
```

compose 已提供 MinIO 服务（`docker-compose.yml:53-61`，`MINIO_ROOT_USER/PASSWORD`）。

### 1.3 待确认项（T1 先查）

- `MinioStorageService` **是否会自动创建 bucket**？当前未看到 `bucketExists`/`makeBucket` 调用 →
  若不会，则需补 `ensureBucket()`，否则首次上传会因 bucket 不存在而失败。
- `upload(...)` 的完整签名（第 4 个参数起）需确认。

---

## 二、任务

### T1（P0）启用 MinIO 并确认 bucket 行为

1. `docker-compose.yml` 为 `backend-java` 注入（**值放 `.env`，不入库**）：
   ```
   MINIO_ENABLED=true
   MINIO_ENDPOINT=http://minio:9000        # ← 容器内必须用**服务名**，禁 localhost
   MINIO_ACCESS_KEY=${MINIO_ROOT_USER:-minio}
   MINIO_SECRET_KEY=${MINIO_ROOT_PASSWORD:-minio123}
   MINIO_BUCKET=${MINIO_BUCKET:-nocobase}
   ```
2. 确认/补齐 **bucket 保障**：启动时或首次上传前确保 bucket 存在
   （新增 `ensureBucket()`，幂等；失败要有明确日志，不要静默）。
3. 实测 `isEnabled()` 为 true，且端点**不再返回 501**。

### T2（P0）接真 `POST /api/attachments/upload`

- 复用 `MinioStorageService.upload(...)`（**不要另写一套 S3 客户端**）
- 返回真实 `storageKey` 与 metadata（原文件名、contentType、大小、租户）
- 移除 :93 的"未实现"文案与 :63 的 TODO
- metadata 需落库（既有 `/metadata` 端点可复用），保证**上传后可查**

### T3（P0）下载链路可用

- `GET /api/attachments/{storageKey}` 返回可用下载地址（预签名 URL）或文件流
- 实测**能下载且字节一致**（与上传文件比对 size / hash）

### T4（P0）安全要求（不可省）

1. **上传必须认证**（匿名 401），并带租户上下文
2. **大小与类型限制**（可配，如单文件上限、允许类型白名单），超限返回 400
3. **防路径穿越**：storageKey 走既有 `sanitize()`；`GET` 不得读越界 key
4. **租户隔离**：storageKey 含 tenantId；**A 租户不得下载 B 租户附件**（403/404）
5. 预签名 URL 有**有效期**（不得永久有效）

### T5（P0）端到端闭环（与 PHASE68 的备份串起来）

1. 上传一个**真实附件**（如小文本/图片）
2. 下载校验（字节一致）
3. **执行备份** → 产物 `components` 含 **`media`**（PHASE68 已支持 MinIO 导出）
4. **恢复到独立目标**（bucket 或前缀隔离，严禁覆盖原数据）
5. 恢复后**附件仍可下载且字节一致**

---

## 三、范围边界

- 不做文件预览/缩略图/病毒扫描
- 不改 Wiki 附件（已接，除非顺带验证）
- 不做多租户存储配额

## 四、门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | **> 1307**（T2/T4 需新增用例）/ 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **48 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 五、红线（违反即打回）

1. **严禁硬编码 MinIO 凭据** —— 一律环境变量（`.env` 不入库）
2. **容器内服务间访问必须用服务名**（`http://minio:9000`），禁 `localhost`
3. **严禁匿名上传**、严禁无租户隔离、严禁永久有效预签名 URL
4. **严禁 mock 被测主路径 Service**
5. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
6. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
7. **严禁**回滚已闭环提交（PHASE58–68）
8. **严禁改动共享环境既有账号凭据**（PHASE67 已发生过）
9. **改动必须提交并推送**（`git status --porcelain` 为空）
10. **每项必须给出实测输出**（curl + 字节/hash 比对 + 备份/恢复证据）

## 六、教训

1. **"能建字段" ≠ "能存文件"** —— `attachment` 字段类型与列映射都有了，但存储没接真 → 用户点了存不了。与本项目 5 次"假完成"同源。
2. **先查有没有现成封装** —— 本次 `MinioStorageService` 已存在，直接复用即可；不查就可能重复造轮子。
3. **修好代码 ≠ 能力可用** —— 端到端实测（上传→下载→备份→恢复→读取）。
4. **实测前确认容器跑最新代码**（PHASE67 我踩过旧镜像导致误判）。
5. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**commit message 含引号用 `git commit -F 文件`**。

## 七、交付清单（缺项打回）

1. T1：compose 注入项 + bucket 保障实现 + `isEnabled()` 实测为 true
2. T2：upload 接真实现 + 上传实测（返回 storageKey）
3. T3：下载实测（字节/hash 与上传一致）
4. T4：安全四项逐条实测（匿名 401 / 超限 400 / 路径穿越 / **跨租户 403 或 404**）
5. T5：备份含 media + 恢复后附件可读且字节一致
6. 五项门禁实际输出数字
7. 改动清单 + `git log --oneline`（**已提交并推送**，status 空）
8. 说明哪些未做及原因

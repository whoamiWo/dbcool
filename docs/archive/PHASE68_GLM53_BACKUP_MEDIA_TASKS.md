# PHASE68 任务需求单：备份覆盖媒体资产（并消除静默跳过）（交 Kilo Code + GLM-5.3，CodeBuddy 审计）

> 来源：PHASE66 演练遗留项「备份产物缺 Media 组件」。本次调研发现**真因比表面更值得修**，
> 见 §1.3 —— 真正危险的不是"没写 media 备份"，而是**备份对缺失组件静默跳过**。

---

## 一、现状（CodeBuddy 实测，含行号）

### 1.1 备份脚本的 media 处理

```python
# backend-python/src/nocobase_py/services/backup.py
:50   _MEDIA_DIR = Path("media")
:253  if _MEDIA_DIR.exists():          # ← 目录不存在就**静默跳过**
:254      tar.add(_MEDIA_DIR, arcname="media")
:255      components.append("media")
```

容器内实测：`media/` 与 `/app/media` **均不存在** → 不打包、不打警告、`components` 里直接没有 media。

### 1.2 附件存储**尚未接真**（本次新发现）

```java
// backend-java/src/main/java/com/nocobase/attachment/AttachmentController.java
:63   // Week 42+: 这里会调 MinIO.putObject() 然后存 metadata 到 records   ← TODO
:93   "附件实际存储 Week 42+ D1.4 实现 (MinIO SDK + 预签名 URL)"          ← 未实现
// backend-java/src/main/java/com/nocobase/wiki/WikiAttachmentService.java
:48   "对象存储未启用 — 配置 app.storage.minio.enabled=true 后可用"
```

即：**当前系统里可能根本没有真实附件数据**；即便有，也取决于 MinIO 是否启用。

compose 已提供 MinIO 服务（`docker-compose.yml:53-61`，`MINIO_ROOT_USER/PASSWORD` 环境变量）。

### 1.3 真正的问题（本批重点）

| 表面问题 | 真问题 |
|---|---|
| "备份产物缺 media" | **备份对缺失组件静默跳过**：今天没附件 → 静默不备份；**将来一旦有附件，备份仍会静默缺 media**，而运维看 `components` 列表会以为备份是完整的 |

同理，`_SQLITE_PATHS = [Path("alerts.db")]`（`:49`）：容器内也不存在 → **同样静默缺失**。

> 结论：**本批既要"让 media 真的进备份"，更要"让缺失不再静默"**。

---

## 二、任务

### T1（P0）先确认附件链路的真实状态（不要急着写代码）

实测并回答（贴证据）：

1. `app.storage.minio.enabled` 当前是 true 还是 false？（grep `application*.yml` / compose 环境变量）
2. 尝试真实上传一个附件（`POST /api/attachments/upload`）→ 记录返回（成功？501 未实现？对象存到了哪？）
3. 若 MinIO 未启用：**在回报中明确说明"当前无附件数据"**，并给出启用所需配置项
4. 若 MinIO 已启用：确认 bucket 名、对象前缀，以及对象最终落在 MinIO 还是本地目录

> 这一步的结论决定 T2 的实现方式（对象存储 vs 本地目录），**不要跳过**。

### T2（P0）让媒体资产真的进入备份

按 T1 结论二选一：

- **若附件在 MinIO（对象存储）**：备份需从 MinIO 导出对象
  - 用 MinIO 客户端（`mc` 或 Python `minio` SDK）列出并下载对象，打包进备份产物
  - 连接配置走环境变量（`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / endpoint / bucket），**严禁硬编码凭据**
  - 若引入 `mc`/SDK，需确认容器镜像内有该依赖（否则要改 `backend-python/Dockerfile`）
- **若附件在本地目录**：确保备份进程能访问该目录（compose 挂载同一 volume），并把 `_MEDIA_DIR` 指向真实路径（可配置，如 `MEDIA_DIR` 环境变量）

统一要求：media 打包成功后 `components` 含 `"media"`，并记录对象/文件数量与字节数。

### T3（P0）消除静默跳过（本批的核心红线项）

1. 对**每个**预期组件（postgresql / media / sqlite / redis）：
   - 缺失或为空时 → **打 WARN 日志**，并在 `create_backup()` 返回值里显式标注
     （如 `warnings: ["media: 源不存在或为空"]`），**不得只是"没有这个 key"**
2. 关键组件缺失（如 postgresql）应视为**失败**（当前似乎是静默继续）
3. 备份产物里写一份 `manifest.json`（组件清单 + 各组件条目数/字节数 + warnings），便于运维与恢复时校验

### T4（P1）恢复端对称支持 media

- 恢复时能把 media 还原回**原存储**（MinIO 或本地目录）
- 恢复后校验：对象/文件数量与 manifest 一致
- 同样：恢复遇到缺失组件也要 WARN，不得静默

### T5（P1）SQLite alerts.db 同样处理

按 T3 的规则纳入（存在则备份、不存在则 WARN），不要继续静默。

---

## 三、验收（必须端到端实测）

1. **造真实数据**：启用（或模拟）附件存储 → 上传 **至少 1 个真实附件**（并记录其 key/路径）
2. **备份**：执行完整备份 → 产物 `components` **含 `media`**；manifest 有条目数与 warnings 字段
3. **恢复到独立环境**：media 被还原，**附件能被下载/读取**（不是"文件在但读不出来"）
4. **反向用例**：
   - 清空 media 源后再备份 → 返回里**有 warning**（不是静默无 media）
   - 恢复时 media 缺失 → 有 WARN，且其余组件正常恢复

---

## 四、范围边界

- 不做对象存储的架构改造（沿用现有 MinIO 或本地目录）
- 不实现附件上传功能本身（若未接真，只在 T1 说明现状；若本批能顺手接真更好，但不是必须）
- 不改备份的定时入口（`run_scheduled_backup()` + `BACKUP_CRON` 保持可用）

## 五、门禁基线

| 门禁 | 要求 |
|---|---|
| `mvn -o test` | ≥ **1307** / 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 六、红线（违反即打回）

1. **严禁静默跳过组件** —— 缺失必须 WARN + 在返回中标注（T3 是本批首要红线）
2. **严禁硬编码对象存储凭据** —— 一律走环境变量
3. **严禁编造演练结果** —— 必须真上传附件、真备份、真恢复、真读取
4. **严禁覆盖现有/生产库或存储** —— 恢复到独立目标
5. **严禁 mock 被测主路径 Service**
6. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
7. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
8. **演练/测试不得改动共享环境的既有账号凭据**（PHASE67 已发生过：admin/alice 密码被改，我用备份恢复）
9. **改动必须提交并推送**（`git status --porcelain` 为空）

## 七、教训

1. **"没打包" ≠ "没数据"** —— 组件缺失时静默跳过，会让运维误以为备份完整。**备份系统必须把"缺失"显式暴露出来**（warnings + manifest）。
2. **先确认数据在哪，再写备份** —— 本次发现附件根本没接真 MinIO（`AttachmentController:63` 仍是 TODO）。不确认就写代码，可能备份一个空目录。
3. **修好代码 ≠ 能力可用**（已 5 次假完成）—— 判据是追到实现落点并端到端实测。
4. **端到端实测前确认容器跑最新代码**（PHASE67 我自己踩过：旧镜像导致误判 403）。
5. **容器内服务间访问必须用服务名**（如 `http://minio:9000`），禁 `localhost`。
6. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**commit message 含引号改用 `git commit -F 文件`**。

## 八、交付清单（缺项打回）

1. T1：附件链路现状结论（MinIO 是否启用、上传实测返回、对象落在哪）
2. T2：media 备份实现 + 产物 `components` 含 media 的证据
3. T3：manifest.json 内容 + **缺失时的 WARN/警告字段证据**
4. T4/T5：恢复端实测（附件可读）+ sqlite 处理
5. 五项门禁实际输出数字
6. 改动清单 + `git log --oneline`（**已提交并推送**，status 空）
7. 说明哪些未做及原因

# PHASE68 · 投喂提示词（整段复制给 Kilo Code + GLM-5.3）

> 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容投喂。任务书见仓库根目录 `PHASE68_GLM53_BACKUP_MEDIA_TASKS.md`。

-----BEGIN PROMPT-----

你是本项目的执行开发。本批（PHASE68）修 **备份不覆盖媒体资产（media）**，并**消除备份对缺失组件的静默跳过**。

## 0. 现状（CodeBuddy 实测）

### 备份脚本
```python
# backend-python/src/nocobase_py/services/backup.py
:50   _MEDIA_DIR = Path("media")
:253  if _MEDIA_DIR.exists():      # ← 不存在就**静默跳过**
:254      tar.add(_MEDIA_DIR, arcname="media")
:255      components.append("media")
```
容器内实测：`media/` 与 `/app/media` **都不存在** → 不打包、**不打警告**、`components` 直接没有 media。
同理 `_SQLITE_PATHS = [Path("alerts.db")]`（`:49`）也不存在 → 同样静默缺失。

### 附件存储**尚未接真**（新发现）
```java
// backend-java .../attachment/AttachmentController.java
:63   // Week 42+: 这里会调 MinIO.putObject() ...     ← TODO，未实现
:93   "附件实际存储 Week 42+ D1.4 实现 (MinIO SDK + 预签名 URL)"
// .../wiki/WikiAttachmentService.java:48  "对象存储未启用 — 配置 app.storage.minio.enabled=true 后可用"
```
compose 已提供 MinIO 服务（`docker-compose.yml:53-61`，`MINIO_ROOT_USER/PASSWORD`）。

### 真正的风险（本批重点）
| 表面问题 | 真问题 |
|---|---|
| "备份缺 media" | **备份对缺失组件静默跳过** —— 今天没附件 → 静默不备份；**将来有附件时备份仍会静默缺 media**，运维看 `components` 会误以为备份完整 |

## 1. 任务

### T1（P0）先确认附件链路真实状态（不要急着写代码）
实测并贴证据：
1. `app.storage.minio.enabled` 当前 true 还是 false？
2. 真实上传一个附件（`POST /api/attachments/upload`）→ 记录返回（成功？501 未实现？对象存到哪？）
3. 若 MinIO 未启用：明确说明"当前无附件数据"，并给出启用所需配置项
4. 若已启用：确认 bucket 名、对象前缀、对象落在 MinIO 还是本地目录

→ 这一步的结论决定 T2 的实现方式。

### T2（P0）让 media 真的进备份
- **在 MinIO**：用 `mc` 或 Python `minio` SDK 导出对象打包；连接配置走环境变量
  （`MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`/endpoint/bucket），**严禁硬编码凭据**；
  若镜像内缺 `mc`/SDK，需改 `backend-python/Dockerfile`
- **在本地目录**：compose 挂载同一 volume，`_MEDIA_DIR` 指向真实路径（可用 `MEDIA_DIR` 环境变量配置）
- 打包成功后 `components` 含 `"media"`，并记录对象/文件数量与字节数

### T3（P0）消除静默跳过（本批首要红线）
对每个预期组件（postgresql / media / sqlite / redis）：
- 缺失或为空 → **打 WARN** + 在 `create_backup()` 返回里**显式标注**（如 `warnings: ["media: 源不存在或为空"]`），**不能只是"没有这个 key"**
- 关键组件（postgresql）缺失应视为**失败**
- 备份产物写一份 `manifest.json`（组件清单 + 条目数/字节数 + warnings）

### T4（P1）恢复端对称支持 media
恢复时还原回原存储（MinIO 或本地目录），校验数量与 manifest 一致；缺失组件也要 WARN。

### T5（P1）SQLite alerts.db 同样处理（存在则备份、不存在则 WARN）

## 2. 验收（必须端到端）
1. **造真实数据**：启用（或模拟）附件存储 → 上传**至少 1 个真实附件**（记录 key/路径）
2. **备份**：产物 `components` **含 `media`**；manifest 有条目数与 warnings 字段
3. **恢复到独立环境**：media 被还原，**附件能被下载/读取**（不是"文件在但读不出来"）
4. **反向用例**：
   - 清空 media 源后备份 → 返回里**有 warning**（不是静默无 media）
   - 恢复时 media 缺失 → 有 WARN，其余组件正常恢复

## 3. 门禁基线
| 门禁 | 要求 |
|---|---|
| `mvn -o test` | ≥ **1307** / 0 failures / 0 errors |
| `npm run test:run` | ≥ **362** |
| `npx tsc --noEmit` | **0** |
| `npx playwright test` | ≥ **64** |
| `pytest tests/` | **43 passed, 1 skipped**（`cd backend-python && PYTHONPATH=src python3 -m pytest tests/ -q -p no:cacheprovider`） |

## 4. 红线（违反即打回）
1. **严禁静默跳过组件** —— 缺失必须 WARN + 返回中标注（T3 是首要红线）
2. **严禁硬编码对象存储凭据** —— 一律环境变量
3. **严禁编造结果** —— 必须真上传、真备份、真恢复、真读取
4. **严禁覆盖现有/生产库或存储** —— 恢复到独立目标
5. **严禁 mock 被测主路径 Service**
6. **严禁** `it.skip` / `@Disabled` / 删测试 / 弱化断言 / 改阈值
7. **严禁**提交 `.env` / 密钥 / `__pycache__` / `target/` / `node_modules/`
8. **演练/测试不得改动共享环境既有账号凭据**（PHASE67 已发生过 admin/alice 密码被改，我用备份恢复过）
9. **改动必须提交并推送**（`git status --porcelain` 为空）

## 5. 教训
1. **"没打包" ≠ "没数据"** —— 缺失静默跳过会让运维误判备份完整；备份系统必须把"缺失"显式暴露（warnings + manifest）
2. **先确认数据在哪再写备份** —— 本次发现附件根本没接真 MinIO（`AttachmentController:63` 仍是 TODO）
3. **修好代码 ≠ 能力可用**（已 5 次假完成）—— 端到端实测
4. **实测前确认容器跑最新代码**（PHASE67 我踩过：旧镜像导致误判）
5. **容器内服务间访问用服务名**（如 `http://minio:9000`），禁 `localhost`
6. **排查引用排除 `.kilo/worktrees/`**；**grep 加 `-i`**；**commit message 含引号用 `git commit -F 文件`**

## 6. 回报必须给出（缺项打回）
1. T1：附件链路现状结论（MinIO 是否启用、上传实测返回、对象落在哪）
2. T2：实现 + 产物 `components` 含 media 的证据
3. T3：manifest.json 内容 + **缺失时 WARN/警告字段证据**
4. T4/T5：恢复端实测（附件可读）+ sqlite 处理
5. 五项门禁实际输出
6. 改动清单 + `git log --oneline`（**已推送**，status 空）
7. 说明哪些未做及原因

-----END PROMPT-----

## 投喂方式
1. 复制 `-----BEGIN PROMPT-----` 到 `-----END PROMPT-----` 之间全部内容
2. 整段贴给 Kilo Code（模型 GLM-5.3）
3. 回报后 CodeBuddy 复验：亲自造附件 → 备份 → 恢复 → 读取，并重点验证"缺失时会 WARN 而非静默"

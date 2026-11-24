# 升级 Runbook（Wave1 T1.6）

> 适用：docker compose 单机部署。数据库迁移由后端启动时的 Flyway 自动执行（当前最新迁移 **V84**）。

## 一、前置条件

| 项 | 要求 |
|---|---|
| 当前版本 | `docker inspect nocobase-backend-java --format '{{index .Config.Labels "org.opencontainers.image.version"}}'` 或查 `.env` 的 `APP_VERSION`；后者必须是实际已发布的镜像 tag，不保证等于根 `VERSION` |
| 磁盘 | ≥ 2× 镜像体积 + 备份体积 |
| 备份 | **必须**有升级前备份（脚本自动做；`--skip-backup` 禁止在生产使用） |
| 镜像 | 目标版本 4 个应用镜像已发布且可从 registry pull（升级脚本不会本地 build） |

## 二、标准升级步骤

升级脚本按 `APP_VERSION` 镜像 tag 拉取目标镜像并使用 `--no-build` 启动；成功后把当前/上一版
tag 记录到 `.last-deployed-tag` / `.prev-deployed-tag`。运行 `deploy.sh prod` 时同样先 pull 再启动。


```bash
# 确认 .env 的 APP_VERSION 为当前部署的镜像 tag；--from / --to 都传已发布 tag
FROM_TAG=2.1.5-3  # 当前版本示例；升级时改为实际正在运行的 tag
: "${TO_TAG:?请先设置为已发布的新镜像 tag}"

# 执行升级（自动：备份 → pull 精确镜像 → --no-build 启动 → 等健康 → 冒烟）
bash scripts/upgrade.sh --from "$FROM_TAG" --to "$TO_TAG"

# 3. 验证
docker compose ps                      # 全部 healthy
python3 scripts/launch-final-verify.py # 8/8（如可用）
```

## 三、回滚（脚本自动 + 手动兜底）

脚本在「启动失败 / 健康检查超时 / 冒烟失败」时自动回滚镜像 tag。

手动兜底（数据回滚 = 恢复备份）：

```bash
# 1. 镜像回滚（将示例改为上一版实际发布的 tag）
PREV_TAG=2.1.5-3
APP_VERSION="$PREV_TAG" docker compose pull backend-java backend-python crdt-service frontend
APP_VERSION="$PREV_TAG" docker compose up -d --no-build backend-java backend-python crdt-service frontend

# 2. 数据回滚（危险：会覆盖当前库）
gunzip -c backups/pre-upgrade-2.1.5-3-to-<newtag>-<stamp>.sql.gz \
  | docker exec -i nocobase-postgres psql -U "$POSTGRES_USER" "$POSTGRES_DB"
```

## 四、数据迁移注意

- Flyway **只向前**迁移：升级后再回滚镜像，库 schema 仍是新版本（Flyway 不自动降级）。
- 若新版本含破坏性迁移，镜像回滚后需按上节恢复备份。
- 迁移执行证据看后端日志：`docker logs nocobase-backend-java | grep -i flyway`。

### 4.1 本轮迁移（V77–V84）与数据影响

| 迁移 | 内容 | 回滚影响 |
|---|---|---|
| V77 | `org_unit`、用户档案列、`password_hash` 放开 NOT NULL + `password_set` | 回滚镜像前需恢复备份；否则旧代码会假设 `password_hash` 非空 |
| V78 | `approval_template` / `approval_request` / `approval_cc` | 新表可保留（旧代码不读），无需删表；恢复备份更彻底 |
| V79 | `messages` 通知中心列 + 旧类型收敛（把 `workflow` 误标为 `WORKFLOW_FAILED`） | 已发布迁移**不可修改**；本轮由 V81 纠偏 |
| V80 | 恢复 `content_tsv` 触发器 | 保留 |
| V81 | `messages.last_event_at`；`workflow` → `WORKFLOW_NOTICE`；仅回填 V79 之前被误标的 `WORKFLOW_FAILED` | 旧镜像不识别 `WORKFLOW_NOTICE`，会显示在“全部”但无对应 tab；不影响功能 |
| V82 | `users.external_source/external_id/disable_reason`；外部身份按 `(tenant_id, source, external_id)` 唯一；按用户名前缀回填历史外部身份 | 增量列可保留，旧镜像忽略；`disable_reason` 为 NULL 时行为与升级前一致 |
| V83 | 审批外部绑定、回调事件幂等表、模板 `external_config`、单据 `corp_id/external_push_status/error` | 不回填、不改写历史审批归属；旧镜像忽略新列/表可保留。回滚镜像必须同时关闭钉钉外推；历史事件恢复需备份 |
| V84 | 回调事件增加 `corp_id`，主键改为 `(corp_id,event_id)`；新增 `oidc_login_code` 仅保存 code 哈希 | 从历史绑定回填 corpId，无法确定的孤儿事件会阻止升级，需先对账；回滚镜像必须关闭 OIDC/钉钉外推，事件主键不可逆操作需从备份恢复 |

> V81 依赖 `flyway_schema_history` 中的 V79 安装时间。手工初始化、缺少该表的库会**跳过**失败类型回填并 `RAISE NOTICE`（不改数据），此时历史通知可能仍显示“流程失败”，需人工核对。

> V82 的外部身份回填只识别 `fs_ / dt_ / wecom_ / wechat_` 前缀，其它托管账号保持 NULL 并在下次同步时补齐。
> 其中 `wecom_ / wechat_` 的历史 username 只保存了 openid/unionid 的 12 位摘要，回填出的 `external_id`
> 是**近似值**，首次同步会以完整 id 覆盖；此前按 `external_id` 做的审计查询可能看到摘要值。LDAP/OIDC 不回填。
> `username` 仍是全局唯一：两个租户接同一外部应用时会为后建账号追加稳定的租户后缀，**不会改写对方租户账号**。
> `disable_reason=MANUAL` 的账号在外部同步中保持禁用；管理员重新启用时清空该字段。纯资料编辑不再把
> `RESIGNED` 改写为 `MANUAL`，因此重新入职可被同步自动恢复。
>
> 同步安全开关：
> - 飞书：`POST /api/feishu/sync-org?dryRun=true` 先核对 `pendingResignations`；计划内大裁员需 ADMIN 加
>   `force=true`（跳过熔断并写审计）。
> - 钉钉：组织同步**只使用租户级凭证**。未配置租户会直接拒绝且不发起外部请求；单租户存量部署若确需回退
>   全局 `dingtalk.app-key/secret`，必须显式设置 `dingtalk.single-tenant-legacy=true`（多租户环境保持 false）。
>   钉钉同样支持 `dryRun=true` / `force=true`。

### 4.2 钉钉外推、回调与 OIDC 边界

- `approval.dingtalk.push.enabled=false`（环境变量 `DINGTALK_PUSH_ENABLED`）默认保持关闭。真实平台验收完成前不得开启，旧 `/api/dingtalk/approval`、`/approval-status` 与 `/events` 不再提供审批推进。
- ADMIN 经 `/api/admin/integration-credentials/dingtalk` 写入 `app-key/app-secret/token/encoding-aes-key/corp-id`。响应和审计不返回凭证；`CREDENTIAL_MASTER_KEY` 必须来自安全配置，禁用或解密失败会 fail-closed，禁止静默回退全局密钥。
- 外推模板由 ADMIN 创建时配置 `externalProcessCode/externalConfig`，后者须明确 `corpId`、外部表单控件映射及 `approvers` 或 `OA_TEMPLATE + deptId`。申请人必须启用且绑定 `DINGTALK/external_id`；禁用/离职禁止外推。
- 回调是 `POST /api/dingtalk/approval-callback?msg_signature=...&timeStamp=...&nonce=...`，body 为 `{"encrypt":"..."}`；必须按 `token + EncodingAESKey + corpId` 验签解密。`check_url` 和正常 ACK 都返回加密 `success`。旧 appSecret HMAC 与明文 `instance_id` 不再接受。
- `approval_callback_event` 按 `(corpId,eventId)` 原子认领和记录状态；同 corp 同 eventId 重放不重复推进，不同 corp 的相同 eventId 不冲突，失败不返回成功 ACK。外推失败保留本地单据；`UNKNOWN_BINDING` 必须保留已保存的 corpId/processCode/实例 ID，管理员重试只按该三元组幂等补绑，禁止再次创建 OA；若实例已属于其它单据则拒绝。外推原子 `PUSHING` 占位防止并发重复创建，回调租约 60 秒，`type=finish` 才允许推进。当前没有无限自动重试；开关关闭时提交外推模板返回 409，不创建无人可办理的实例，普通本地模板不受影响。
- 真实钉钉 OA/回调尚未验收，不能宣称生产闭环；飞书同步、钉钉同步在默认环境同样不得开启。
- OIDC 仅支持 `tenant_default`：按 `(tenant, OIDC, sub)` 匹配，禁止 email/username 静默绑定；角色只读本地授权，不读 IdP groups。`/oidc/callback` 只接收一次性随机 code，清除地址栏后调用 `/api/auth/oidc/exchange` 换本地 JWT；code 仅存 SHA-256、60 秒过期且原子单次消费，JWT 不出现在重定向 URL。真实 IdP、禁用/离职端到端验收仍待执行。

### 4.3 DEPT / SELF 数据范围在升级后“才真正生效”

- `created_by` 现在由服务端真实写入（单条/批量/CSV/公开表单/自动化）。
- **历史 NULL 不自动回填**：未回填的历史记录在配置了 SELF/DEPT 数据范围的角色下**不可见**。
  这是 fail-closed 的预期结果，不是 bug。
- 升级后如发现“列表比升级前少”，先确认是否配置了数据范围；需要可见时执行回填（见 4.4）。

### 4.4 历史归属回填（先 dry-run）

```bash
# 默认 dry-run：只打印将影响行数与样例 5 条，不写库
python3 scripts/backfill-created-by.py --tenant tenant_default --collection customer

# 从历史 extra.createdBy/created_by 解析（无法解析时需显式 --user）
python3 scripts/backfill-created-by.py --tenant tenant_default --collection customer --from-extra

# 真正落库（必须三参数齐全；会写 audit_log: BACKFILL_CREATED_BY）
python3 scripts/backfill-created-by.py --tenant tenant_default --collection customer --user <uuid> --apply
```

> 不提供“全库回填”；先在测试租户演练，再按集合逐个执行。

### 4.5 搜索索引全量重建

A-6 修复了记录索引写入器（按集合元数据推断 title/content），但历史行需要重建：

```bash
docker compose run --rm backend-java java -jar app.jar --reindex-search
# 或对正在运行的容器：
docker exec nocobase-backend-java sh -c 'java -jar /app/app.jar --reindex-search'
```

- 幂等、可重复执行；按 keyset 分页遍历**全量**（不再只重建前 500 条）。
- 日志中每个集合输出 `scanned/reindexed` 计数。

### 4.6 SSO 托管账号无法密码登录

- 外部托管账号（钉钉/企微/飞书/LDAP/OIDC）`password_set=false`，只能走 SSO。
- 管理员重置密码会置 `password_set=true`；若企业要求禁止本地密码登录，请勿重置。
- 忘记 SSO 配置时，本地 `admin` 账号仍可密码登录作为兜底。

## 五、失败判定矩阵

| 现象 | 处置 |
|---|---|
| 镜像拉取失败 | 检查 registry 网络或离线包；不升级 |
| Flyway 迁移失败（后端反复重启） | 立即回滚镜像 + 恢复备份，保留 `docker logs` |
| 冒烟失败 | 脚本已自动回滚；排查冒烟输出后重试 |

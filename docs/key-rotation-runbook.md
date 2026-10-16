# 密钥轮换 Runbook（PHASE110）

> 目标：覆盖 JWT 签名密钥、数据库/MinIO/RabbitMQ 口令、集成凭证主密钥五类密钥的轮换步骤与校验。
> 适用范围：生产环境密钥轮换操作。

---

## 1. 前置条件

- 操作者具有 `ADMIN` 角色
- 当前服务健康（`/api/health` 返回 200）
- 备份已验证（`python3 scripts/backup-e2e-verify.py` 30/30）
- 已配置 `INTEGRATION_CREDENTIAL_MASTER_KEY`（PHASE99 红线 1）

---

## 2. JWT 签名密钥轮换

### 2.1 当前机制

- `JwtKeyRotationController`（`/api/admin/jwt-keys`）提供 `rotate` 端点
- `KeyRingService` 维护内存中的密钥环，支持 ACTIVE / RETIRED 两种状态
- **双密钥窗口**：轮换后旧密钥仍可验证存量 token，直到 access TTL（默认 15 min）过期
- **重启丢失**：内存中的密钥环重启后从 yml 重读，需同步更新 `app.jwt.previous-secret`

### 2.2 操作步骤

```bash
# 1. 记录当前密钥环状态
curl -s -H "Authorization: Bearer <admin-token>" \
  http://localhost:8080/api/admin/jwt-keys | jq

# 2. 触发轮换
curl -s -X POST -H "Authorization: Bearer <admin-token>" \
  http://localhost:8080/api/admin/jwt-keys/rotate | jq

# 3. 验证新密钥生效
python3 scripts/key-rotation-verify.py --base-url http://localhost:8080

# 4. 同步更新 yml 配置（重启后保留旧密钥）
# app.jwt.previous-secret = <RETIRED key 的 secret>
```

### 2.3 回滚方式

- 如果轮换后出现异常，重新调用 `/api/admin/jwt-keys/rotate` 并在 yml 中恢复旧密钥配置
- 存量 token 在 15 min 内仍有效（双密钥窗口），无需全量登出

---

## 3. 数据库/MinIO/RabbitMQ 口令轮换

### 3.1 操作步骤

```bash
# 1. 生成新密码
NEW_DB_PASS=$(openssl rand -base64 24)
NEW_MINIO_PASS=$(openssl rand -base64 24)
NEW_RABBITMQ_PASS=$(openssl rand -base64 24)

# 2. 更新 .env
sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$NEW_DB_PASS/" .env
sed -i "s/^MINIO_ROOT_PASSWORD=.*/MINIO_ROOT_PASSWORD=$NEW_MINIO_PASS/" .env
sed -i "s/^RABBITMQ_PASSWORD=.*/RABBITMQ_PASSWORD=$NEW_RABBITMQ_PASS/" .env

# 3. 重启受影响服务
docker compose restart postgres minio rabbitmq

# 4. 验证服务健康
python3 scripts/smoke.py
```

### 3.2 注意事项

- 口令轮换需要重启服务，存在短暂不可用窗口（建议低峰期执行）
- 轮换后需通知所有依赖这些凭据的集成方

---

## 4. 集成凭证主密钥轮换

### 4.1 当前机制

- `TenantCredentialService` 管理租户级集成凭证（加密落库）
- `CredentialCryptoService` 使用 `INTEGRATION_CREDENTIAL_MASTER_KEY` 加密/解密
- 主密钥轮换后，所有已加密凭证需要用旧密钥解密、再用新密钥重新加密

### 4.2 操作步骤

```bash
# 1. 生成新主密钥
NEW_MASTER_KEY=$(openssl rand -base64 32)

# 2. 用旧密钥解密所有凭证，再用新密钥重新加密
# （需在应用内执行，暂不提供自动化脚本）

# 3. 更新 .env
sed -i "s/^INTEGRATION_CREDENTIAL_MASTER_KEY=.*/INTEGRATION_CREDENTIAL_MASTER_KEY=$NEW_MASTER_KEY/" .env

# 4. 重启服务
docker compose restart backend-java backend-python

# 5. 验证
python3 scripts/key-rotation-verify.py --base-url http://localhost:8080
```

### 4.3 注意事项

- 主密钥轮换是**高风险操作**，必须在维护窗口执行
- 轮换前必须确认所有凭证已备份
- 轮换后需验证至少一个集成方（如飞书/钉钉/企微）仍能正常工作

---

## 5. 验证命令汇总

| 验证项 | 命令 | 预期 |
|--------|------|------|
| JWT 密钥轮换 | `python3 scripts/key-rotation-verify.py` | 全部 PASS |
| 服务健康 | `python3 scripts/smoke.py` | 11/11 |
| 备份可恢复 | `python3 scripts/backup-e2e-verify.py` | 30/30 |
| 生产检查 | `python3 scripts/startup-checklist.py` | 0 问题 |

---

## 6. 告警

- 轮换失败须产生明确日志
- 告警沿用既有 `ALERT_WEBHOOK_URL` 外发通道
- 未配置 webhook 时，启动日志显式提示「仅落盘不外发」，不静默
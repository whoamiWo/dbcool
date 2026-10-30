# 密钥管理（Sealed Secrets）

> Wave4 待办池 #4：K8s 部署的 Secret 此前全为 `CHANGE_ME` 占位符，真实密钥既无注入方式也无防泄漏机制。
> 本方案用 **Sealed Secrets** 落地：明文只在本地存在 → 用 controller 公钥加密 → 密文（可入库）→ 部署时由 controller 解密为 Secret。

## 一、组件与职责

| 组件 | 作用 | 位置 |
|---|---|---|
| `sealed-secrets-controller` | 集群内解密器，用私钥把 SealedSecret 还原成 Secret | 需安装（Helm/官方 manifest） |
| `kubeseal` | 本地 CLI，用 controller **公钥**加密 | `scripts/seal-secrets.sh` 自动下载 |
| `k8s/secrets.env` | **明文输入**（gitignore，绝不入库） | 本地 |
| `k8s/sealed/secret.yaml` | 加密产物（可入库） | 仓库 |

## 二、一次性准备（集群侧）

```bash
# 安装 controller（任选其一）
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm install sealed-secrets sealed-secrets/sealed-secrets -n kube-system

# 或官方 manifest
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.41.2/controller.yaml
kubectl -n kube-system rollout status deploy/sealed-secrets

# 取公钥（脚本也能自动 fetch）
kubeseal --fetch-cert > sealed-cert.pem
```

## 三、日常使用（仓库内流程）

```bash
# 1) 准备明文（该文件已 gitignore）
cp k8s/secrets.env.example k8s/secrets.env
$EDITOR k8s/secrets.env          # 填真实密钥，禁止保留 CHANGE_ME

# 2) 密封 → 产物可安全提交
bash scripts/seal-secrets.sh      # 需要 SEAL_CERT 或可访问 controller

# 3) 提交密文（只提交 k8s/sealed/secret.yaml，绝不提交 k8s/secrets.env）
git add k8s/sealed/secret.yaml && git commit -m "chore(secrets): 密封更新"

# 4) 部署
kubectl apply -f k8s/sealed/secret.yaml
kubectl -n nocobase get secret nocobase-secret   # controller 会创建同名 Secret
```

## 四、轮换密钥

```bash
# 修改 k8s/secrets.env 中的值 → 重新密封 → 提交 → apply
bash scripts/seal-secrets.sh
kubectl apply -f k8s/sealed/secret.yaml
kubectl -n nocobase rollout restart deploy/backend-java   # 触发重载
```
配套流程见 [`key-rotation-runbook.md`](./key-rotation-runbook.md)。

## 五、安全红线

1. **明文永不入库**：`k8s/secrets.env` 已在 `.gitignore`；提交前用 `git check-ignore -v k8s/secrets.env` 自查。
2. **禁止把占位符密封进产物**：脚本在密封前检测 `CHANGE_ME` 并 exit 2。
3. **私钥只在 controller 内**：仓库只保存公钥与密文，私钥不外流。
4. **下载的 kubeseal 必须是真 ELF**：脚本校验文件头，避免把 HTML 错误页当二进制执行。
5. 离线包（`packaging/offline/.env.example`）同样必须替换全部 `:?` 必填项。

## 六、实测状态（2026-10-31）

| 项 | 状态 |
|---|---|
| `scripts/seal-secrets.sh` 端到端（自签 RSA 证书模拟 controller 公钥） | ✅ 实测通过，产物为合法 `SealedSecret`（`encryptedData` 全部密文） |
| 占位符拦截（反向自检） | ✅ 实测 exit 2 |
| kubeseal 自动下载 + ELF 校验 | ✅ 实测通过（v0.41.2） |
| **controller 端到端解密（真实集群）** | ⚠️ **未实测**（本环境无 K8s 集群）—— 需在有集群环境按 §二/§三 完整演练一次 |
| Helm 方式安装 controller | ⚠️ 未实测 |

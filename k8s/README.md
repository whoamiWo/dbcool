# NocoBase K8s 部署说明（PHASE 55 P0）

补全生产上线阻塞项：**K8s 编排 + 可观测性 + 健康探针**。

## 目录

| 文件 | 内容 |
|---|---|
| `00-base.yaml` | Namespace、ConfigMap、`Secret`（**占位符，必须替换**） |
| `01-infrastructure.yaml` | PostgreSQL、Redis、**RabbitMQ**、MinIO |
| `02-backend-java.yaml` | Java 后端 Deployment + Service + HPA + PDB |
| `03-backend-python.yaml` | Python 后端 Deployment + Service + HPA |
| `04-frontend.yaml` | 前端 Nginx Deployment + Service + HPA |
| `05-ingress.yaml` | Ingress 路由（含 WebSocket 与超时配置） |
| `06-monitoring.yaml` | ServiceMonitor + PrometheusRule 告警规则 |

## 前置条件

1. K8s ≥ 1.26，且已安装 **metrics-server**（HPA 依赖）
2. Ingress Controller（推荐 ingress-nginx）
3. 镜像已构建并推送到集群可拉取的仓库：
   ```bash
   docker build -t nocobase/backend-java:latest ./backend-java
   docker build -t nocobase/backend-python:latest ./backend-python
   docker build -t nocobase/frontend:latest ./frontend
   ```
4. （可选但推荐）Prometheus Operator / kube-prometheus-stack

## 部署步骤

```bash
# 1) 先改密钥 —— 切勿把真实密钥提交进仓库
#    编辑 00-base.yaml 中 nocobase-secret 的 stringData，全部替换 CHANGE_ME
#    建议改用 Sealed Secrets 或外部密钥管理（Vault / 云厂商 KMS）

# 2) 改域名：编辑 05-ingress.yaml 的 host

# 3) 按序应用
kubectl apply -f 00-base.yaml
kubectl apply -f 01-infrastructure.yaml

# 4) 等基础设施就绪（否则应用启动会连不上 DB / MQ）
kubectl -n nocobase wait --for=condition=ready pod -l app=postgres --timeout=180s
kubectl -n nocobase wait --for=condition=ready pod -l app=redis    --timeout=120s
kubectl -n nocobase wait --for=condition=ready pod -l app=rabbitmq --timeout=180s

# 5) 部署应用
kubectl apply -f 02-backend-java.yaml
kubectl apply -f 03-backend-python.yaml
kubectl apply -f 04-frontend.yaml
kubectl apply -f 05-ingress.yaml

# 6) 开启监控（需 Prometheus Operator）
kubectl apply -f 06-monitoring.yaml
```

## 验证

```bash
kubectl -n nocobase get pods,svc,hpa,ingress

# 健康探针（Java 已开启 health.probes，分 readiness / liveness）
kubectl -n nocobase port-forward svc/backend-java 8080:8080
curl -s localhost:8080/actuator/health/readiness
curl -s localhost:8080/actuator/health/liveness

# Prometheus 指标
curl -s localhost:8080/actuator/prometheus | grep http_server_requests
```

## 关键设计说明

### 探针选型
- `liveness` → `/actuator/health/liveness`：失败才重启，避免依赖（DB/MQ）抖动导致误杀
- `readiness` → `/actuator/health/readiness`：失败仅摘流量，不重启
- `startupProbe`：慢启动保护（最多等 150s），防止启动未完成被 liveness 重启

### 为什么加 RabbitMQ
PHASE 55 Stage 2 引入 MQ 底座（异步任务、webhook 发布、迁移任务），
但 `docker-compose.yml` **尚未包含该服务**，本地联调需一并补上，否则相关功能不可用。

### 有状态服务
`01-infrastructure.yaml` 中的 DB/Redis/MinIO 使用 `emptyDir` 或小容量 PVC，
**仅适用于自建集群**。生产建议使用云厂商托管服务（RDS / ElastiCache / OSS），
避免自建有状态服务的运维负担与数据风险。

### 监控告警
`06-monitoring.yaml` 提供 5 条核心告警：实例掉线、5xx 错误率 > 5%、P99 > 3s、
Pod 频繁重启、RabbitMQ 不可用。
其中延迟告警依赖 `management.metrics.distribution.percentiles-histogram.http.server.requests`
（已在 `application.yml` 中开启）。

## 尚未覆盖
- 性能压测基线（无 QPS / P99 容量数据）
- 日志采集（建议补 EFK / Loki）
- 分布式追踪（建议补 OpenTelemetry + Jaeger）
- 备份恢复演练（备份链路代码完整，但**从未做过真实恢复验证**）

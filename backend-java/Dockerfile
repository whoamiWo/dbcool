# ============================================================
#  NocoBase Java 后端 — 多阶段构建
# ============================================================

# Build stage (PHASE136: 宿主机打包，避免 Alpine 构建阶段下载 OpenJDK)
FROM alpine:3.19 AS build
WORKDIR /workspace
# jar 由宿主机 `mvn clean package -DskipTests` 预先产出
COPY target/nocobase-backend-0.0.1-SNAPSHOT.jar .

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 复制构建产物
COPY --from=build /workspace/nocobase-backend-0.0.1-SNAPSHOT.jar app.jar

# 创建非 root 用户
RUN addgroup -S nocobase && adduser -S nocobase -G nocobase
USER nocobase

EXPOSE 8080

# 健康检查
# 注意: 探 /api/health(应用实际暴露的端点)而非 /actuator/health ——
# actuator 未暴露 health 端点,用它会让容器永远标记 unhealthy。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]

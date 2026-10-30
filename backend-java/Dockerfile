# ============================================================
#  NocoBase Java 后端 — 多阶段构建
# ============================================================

# Build stage (PHASE136: 宿主机打包，避免 Alpine 构建阶段下载 OpenJDK)
FROM alpine:3.19 AS build
WORKDIR /workspace
# jar 由宿主机 `mvn clean package -DskipTests` 预先产出
# 注意：不要写死版本号 —— pom.xml 的 version 变更（如 0.0.1-SNAPSHOT → 2.0.0）
# 会让固定文件名失效，导致 "failed to calculate checksum ... not found"。
# 用通配符只匹配主 jar（.jar.original 不会被 *.jar 命中）。
COPY target/nocobase-backend-*.jar .

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 复制构建产物（同样用通配符，避免版本号写死）
COPY --from=build /workspace/nocobase-backend-*.jar app.jar

# 创建非 root 用户 —— uid/gid 固定 1000，与 backend-python 容器一致：
# 两者共用同一告警 SQLite 卷（PHASE100 T6 设计），uid 不一致会导致
# 后挂载方对卷内文件 Permission denied（Wave4 离线包实测复现）。
RUN addgroup -S -g 1000 nocobase && adduser -S -u 1000 -G nocobase nocobase
# 告警 SQLite 卷挂载点（属主与运行用户一致）
RUN mkdir -p /data && chown -R 1000:1000 /data
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

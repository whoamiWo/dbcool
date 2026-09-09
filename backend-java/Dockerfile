# ============================================================
#  NocoBase Java 后端 — 多阶段构建
# ============================================================

# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# 先复制 POM 利用 Docker 缓存
COPY pom.xml .
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B

# 复制源码并构建
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 复制构建产物
COPY --from=build /workspace/target/*.jar app.jar

# 创建非 root 用户
RUN addgroup -S nocobase && adduser -S nocobase -G nocobase
USER nocobase

EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]

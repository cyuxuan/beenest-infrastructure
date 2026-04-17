# ============================================
# 多阶段构建 - beenest-payment 支付微服务
# ============================================

# ---- 阶段1: 构建依赖缓存层 ----
FROM eclipse-temurin:21-jdk-jammy AS dependencies
WORKDIR /build

# 先拷贝 pom 文件，利用 Docker 缓存加速依赖下载
COPY pom.xml ./
COPY beenest-payment-api/pom.xml beenest-payment-api/pom.xml
COPY beenest-payment-service/pom.xml beenest-payment-service/pom.xml

# 下载依赖（pom 未变更时命中缓存）
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn dependency:go-offline -B 2>/dev/null || true

# ---- 阶段2: 编译打包 ----
FROM dependencies AS builder
WORKDIR /build

# 拷贝全部源码
COPY . .

# 构建项目，跳过测试
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn clean package -pl beenest-payment-service -am -DskipTests -B \
    -Dmaven.compiler.release=21

# ---- 阶段3: 运行时镜像 ----
FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="beenest"
LABEL description="Beenest Payment Service - 支付微服务"

WORKDIR /app

# 创建非 root 用户运行应用
RUN groupadd -r appgroup && useradd -r -g appgroup -s /bin/bash appuser

# 从构建阶段拷贝 jar 包
COPY --from=builder /build/beenest-payment-service/target/*.jar app.jar

# 创建日志目录
RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

USER appuser

# 暴露端口（与 application.yml 中 server.port 一致）
EXPOSE 8082

# JVM 参数可通过 JAVA_OPTS 环境变量自定义
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError"

# Spring Profile 可通过 SPRING_PROFILES_ACTIVE 指定
ENV SPRING_PROFILES_ACTIVE=""

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} \
    ${SPRING_PROFILES_ACTIVE:+-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE}} \
    -jar /app/app.jar"]

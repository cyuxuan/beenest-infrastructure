# ===== 蜂巢 IAM 运行镜像 =====
# 多阶段构建:构建阶段用 Maven 镜像拉全依赖,运行阶段只留 JRE 与产物。
# 运行阶段不保留构建工具与源码,镜像体积与攻击面都小一截。
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# 先只拷 pom:依赖层可被缓存,改代码不会触发重新下载依赖
COPY pom.xml .
COPY beenest-iam-common/pom.xml beenest-iam-common/
COPY beenest-iam-core/pom.xml beenest-iam-core/
COPY beenest-iam-persistence/pom.xml beenest-iam-persistence/
COPY beenest-iam-federation/pom.xml beenest-iam-federation/
COPY beenest-iam-provider-oidc/pom.xml beenest-iam-provider-oidc/
COPY beenest-iam-provider-wechat/pom.xml beenest-iam-provider-wechat/
# 父 pom 的 <modules> 列了全部子模块:漏拷任何一个,下面的 dependency:go-offline
# 都会因"子模块不存在"直接失败(而不是等到 package 阶段)
COPY beenest-iam-provider-saml/pom.xml beenest-iam-provider-saml/
COPY beenest-iam-authz/pom.xml beenest-iam-authz/
COPY beenest-iam-web/pom.xml beenest-iam-web/
COPY beenest-iam-admin/pom.xml beenest-iam-admin/
COPY beenest-iam-console/pom.xml beenest-iam-console/
COPY beenest-iam-server/pom.xml beenest-iam-server/
RUN mvn -B -q dependency:go-offline
COPY . .
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# 打补丁:基础镜像自带的 openssl 等系统库会滞后于安全更新。
# 不升级就带着已知 CVE 上线,所以构建时统一升到当前仓库版本;
# 系统库修复不进版本库,建议 CI 定期重建镜像以持续吸收上游补丁
# curl 只为两件事:容器健康检查探 /actuator/health,以及故障时进容器直接调内部接口。
# 基础镜像不带它,而健康检查靠 /dev/tcp 之类的写法读不到 HTTP 状态码
RUN apt-get update \
    && apt-get -y upgrade \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# 非 root 运行:容器逃逸时拿到的是无特权账号
RUN groupadd -r iam && useradd -r -g iam -d /app iam
# 外置扩展包目录:镜像内直接带上构建出的微信扩展包,其余第三方提供方仍可挂 volume 追加
RUN mkdir -p /app/providers && chown iam:iam /app/providers
COPY --from=builder --chown=iam:iam /build/beenest-iam-provider-wechat/target/beenest-iam-provider-wechat-*.jar /app/providers/
COPY --from=builder --chown=iam:iam /build/beenest-iam-server/target/beenest-iam-server-*.jar /app/beenest-iam-server.jar
USER iam
EXPOSE 8080
# 管理端口(actuator/指标)故意不写 EXPOSE:避免 `docker run -P` 把 9090 一并映射到宿主机。
# 需要采集指标时显式发布到运维网段,不要发布到公网。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC"
# 外置扩展包目录:与上面建的目录对应,挂载 volume 到这里即可热插拔提供方
ENV IAM_PROVIDER_DIR=/app/providers
# 以 exec 形式启动:JVM 收到 SIGTERM 能直接退出,不留孤儿进程
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/beenest-iam-server.jar"]

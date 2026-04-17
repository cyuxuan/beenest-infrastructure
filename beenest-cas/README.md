Apereo CAS WAR Overlay Template
=====================================

WAR Overlay Type: `cas-overlay`

# Versions

- CAS Server `7.3.6`
- JDK `21`

# Beenest 统一登录接入

`beenest-cas-client-spring-security-starter` 用于让业务系统零代码接入 Beenest CAS 统一登录，支持：

- Web 端 CAS 单点登录
- 小程序 / App Bearer Token 登录
- CAS back-channel 单点登出
- 用户同步回调 / 拉取
- 登录代理转发到 CAS

业务系统只需要引入 starter 并配置 `cas.client.*`，即可获得统一登录能力。

## 0. 3 分钟接入

如果你只想先跑通，先照着下面做：

1. 在业务系统里引入 `club.beenest.cas:beenest-cas-client-spring-security-starter`
2. 把下面配置写进 `application.yml`

```yaml
cas:
  client:
    enabled: true
    server-url: https://sso.beenest.club/cas
    client-host-url: https://drone.beenest.club
    service-id: 10001
    sign-key: your-service-sign-key
    redirect-login: true
    use-session: true

    business-login-proxy:
      enabled: true
      base-path: /cas

    token-auth:
      enabled: true
      auto-refresh-enabled: true
      validate-cache-ttl-seconds: 300
      validate-cache-max-size: 10000
      access-token-revocation-ttl-seconds: 604800
      refresh-token-revocation-ttl-seconds: 31536000

    slo:
      enabled: true
      callback-path: /cas/callback
```

3. 如果你是多实例部署，再加上 Redis Cache 配置
4. 业务代码里直接使用 `CasSecurityUtils.getCurrentUserId()` 取当前用户

跑通后，再继续看后面的登录代理、Bearer Token 和 SLO 细节。

## 1. 推荐接入方式

```yaml
cas:
  client:
    enabled: true
    server-url: https://sso.beenest.club/cas
    client-host-url: https://drone.beenest.club
    service-id: 10001
    sign-key: your-service-sign-key
    redirect-login: true
    use-session: true

    business-login-proxy:
      enabled: true
      base-path: /cas

    token-auth:
      enabled: true
      auto-refresh-enabled: true
      validate-cache-ttl-seconds: 300
      validate-cache-max-size: 10000
      access-token-revocation-ttl-seconds: 604800
      refresh-token-revocation-ttl-seconds: 31536000

    slo:
      enabled: true
      callback-path: /cas/callback

    sync:
      enabled: false
      webhook-path: /cas/sync/webhook
      pull-enabled: false
```

## 2. 配置说明

### 必填项

- `cas.client.enabled`：是否启用 starter
- `cas.client.server-url`：CAS Server 地址，例如 `https://sso.beenest.club/cas`
- `cas.client.client-host-url`：当前业务系统地址，例如 `https://drone.beenest.club`
- `cas.client.service-id`：CAS 中注册的服务 ID
- `cas.client.sign-key`：CAS 与业务系统之间使用的 HMAC 签名密钥

### 常用项

- `cas.client.business-login-proxy.enabled`：是否启用业务系统登录代理
- `cas.client.business-login-proxy.base-path`：代理前缀，默认 `/cas`
- `cas.client.token-auth.enabled`：是否启用小程序 / App Bearer Token 认证
- `cas.client.slo.enabled`：是否启用单点登出
- `cas.client.sync.enabled`：是否启用用户同步
- `cas.client.token-auth.access-token-revocation-ttl-seconds`：accessToken 撤销态保留时间
- `cas.client.token-auth.refresh-token-revocation-ttl-seconds`：refreshToken 撤销态保留时间

## 3. 业务系统登录代理

当 `cas.client.business-login-proxy.base-path=/cas` 时，业务系统会对外暴露下面这些代理接口：

- `POST /cas/app/login`
- `POST /cas/app/refresh`
- `POST /cas/miniapp/wechat/login`
- `POST /cas/miniapp/douyin/login`
- `POST /cas/miniapp/alipay/login`
- `POST /cas/miniapp/refresh`

这些接口由 starter 接收后，再转发到 CAS Server，对业务系统来说不需要手写转发代码。

### 请求头

代理请求需要带上业务系统签名头：

- `X-CAS-Service-Id`
- `X-CAS-Timestamp`
- `X-CAS-Nonce`
- `X-CAS-Signature`

### 签名规则

签名内容为：

```text
timestamp + "\n" + nonce + "\n" + body
```

签名算法为 `HmacSHA256`，使用 `cas.client.sign-key` 作为密钥。

## 4. Web 端单点登录

Web 端走标准 CAS 登录流程：

1. 用户访问业务系统受保护页面。
2. starter 通过 `CasAuthenticationEntryPoint` 跳转到 CAS 登录页。
3. CAS 登录成功后回调业务系统的 `cas.client.login-path`，默认是 `/login/cas`。
4. starter 使用 `CasAuthenticationProvider` 校验 ST，并将用户信息放入 Spring Security `SecurityContext`。
5. 登录成功后，starter 还会把 `HttpSession`、`ST`、`userId` 和 `CasUserSession` 注册到本地会话注册表，用于后续 SLO 和用户同步。

### 业务代码里如何取当前登录用户

推荐直接使用 starter 提供的工具类：

```java
String userId = CasSecurityUtils.getCurrentUserId();
CasUserDetails details = CasSecurityUtils.getCurrentUserDetails();
boolean authenticated = CasSecurityUtils.isAuthenticated();
```

也可以直接从 Spring Security 的 `SecurityContextHolder` 中读取当前认证对象。

## 5. 小程序 / App 登录

小程序和 App 场景使用 Bearer Token 模式：

1. 客户端先调用业务系统的 `/cas/miniapp/*` 登录代理。
2. starter 转发请求到 CAS Server。
3. CAS 完成微信 / 抖音 / 支付宝 / App Token 认证。
4. CAS 返回的 accessToken / refreshToken 由业务系统保存并继续传给客户端。

### 认证方式

Bearer Token 模式默认是：

- 首次认证时远程 CAS 校验
- 后续请求优先本地缓存
- accessToken 过期后可用 refreshToken 自动刷新

也就是说，**登录和刷新走远程 CAS，业务请求优先本地认证**，不会每次都打到 CAS。

### 请求头

Bearer 请求需要携带：

- `Authorization: Bearer <accessToken>`
- `X-Refresh-Token: <refreshToken>`  可选

## 6. 单点登出

starter 默认启用 SLO，CAS Server 会通过 back-channel 回调业务系统的：

- `POST /cas/callback`

如果你修改了 `cas.client.slo.callback-path`，则以你的配置为准。

### SLO 的保证方式

当 CAS 发起单点登出时，starter 会：

1. 根据 CAS 的 `SessionIndex` 找到本地 `sessionId`
2. 反查 `userId`
3. 清理该用户的本地 Bearer Token 缓存
4. 将本次 logout 里的 `accessToken` / `refreshToken` 写入撤销态
5. 使本地 `HttpSession` 失效
6. 让后续请求重新走 CAS 认证

### 这意味着什么

- Web 会话会被立即作废
- 小程序 / App 的本地 bearer 缓存也会被清掉
- 用户再次访问时，业务系统会重新认证
- 如果业务系统启用了共享 `CacheManager`，例如 Redis Cache，撤销态会同步到所有节点

### 多实例安全

starter 的 bearer 认证默认仍然是“本地缓存优先、远程 CAS 兜底”。
为了让多实例下的单点登出也能尽快失效，starter 会在收到 logout 时把 accessToken / refreshToken 写入撤销态：

- 没有共享缓存时，撤销态落在本地内存，适合单实例或开发环境
- 有共享 `CacheManager` 时，撤销态会自动进入共享缓存，多节点会同时感知注销事件

如果你的业务系统已经接了 Redis Cache，只要把 Spring Cache 打开并配置为共享缓存即可，不需要再写额外的业务代码。

### Redis Cache 推荐配置

业务系统如果想让注销态在多实例间共享，推荐直接启用 Spring Cache + Redis CacheManager：

```yaml
spring:
  cache:
    type: redis
    redis:
      time-to-live: 7d

  data:
    redis:
      host: redis
      port: 6379
      timeout: 2s
```

如果你希望更细地控制撤销态的缓存名，也可以在业务系统里显式预创建这两个缓存：

- `casBearerAccessTokenRevocations`
- `casBearerRefreshTokenRevocations`

示例配置：

```java
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> builder
                .withCacheConfiguration("casBearerAccessTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(7)))
                .withCacheConfiguration("casBearerRefreshTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(365)));
    }
}
```

这样 starter 收到单点登出回调后，会把 accessToken / refreshToken 写入共享撤销态：

- 当前节点立刻失效
- 其他节点在下一次认证时也会直接命中撤销态
- 不需要业务代码手动广播登出事件

### 业务系统接入模板

如果你想要一份可以直接起步的业务系统配置，可以参考下面这套最小模板：

```yaml
cas:
  client:
    enabled: true
    server-url: https://sso.beenest.club/cas
    client-host-url: https://drone.beenest.club
    service-id: 10001
    sign-key: your-service-sign-key
    redirect-login: true
    use-session: true

    business-login-proxy:
      enabled: true
      base-path: /cas

    token-auth:
      enabled: true
      auto-refresh-enabled: true
      validate-cache-ttl-seconds: 300
      validate-cache-max-size: 10000
      access-token-revocation-ttl-seconds: 604800
      refresh-token-revocation-ttl-seconds: 31536000

    slo:
      enabled: true
      callback-path: /cas/callback

spring:
  cache:
    type: redis
  data:
    redis:
      host: redis
      port: 6379
      timeout: 2s
```

```java
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> builder
                .withCacheConfiguration("casBearerAccessTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(7)))
                .withCacheConfiguration("casBearerRefreshTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(365)));
    }
}
```

这套模板的效果是：

1. 业务系统不需要自己写 CAS 登录页转发逻辑。
2. 小程序 / App 的登录和刷新都能走 starter。
3. 单点登出时，本地 session、bearer 缓存和共享撤销态都会一起处理。
4. 多实例环境下，注销事件可以通过 Redis Cache 自动同步到其他节点。

## 7. 用户同步

如果你需要 CAS 侧的用户资料变更自动同步到业务系统，可以启用：

- `cas.client.sync.enabled=true`

同步有两种方式：

- Webhook 推送：CAS 主动推送到 `cas.client.sync.webhook-path`
- Pull 拉取：业务系统定时调用 CAS 的变更接口

默认建议先用 Webhook，简单且实时性更好。

## 8. 业务系统接入后怎么用

业务系统接入 starter 后，一般不需要自己再写登录过滤器。你只需要：

1. 引入 `club.beenest.cas:beenest-cas-client-spring-security-starter`
2. 配置 `cas.client.*`
3. 将需要鉴权的接口交给 Spring Security
4. 在业务代码里从 `CasSecurityUtils` 读取当前用户

## 9. 常见注意点

- 如果业务系统本身已经设置了 `server.servlet.context-path=/cas`，不要再把 `business-login-proxy.base-path` 配成 `/cas`，否则路径会叠加。
- 小程序请求如果出现 404，优先检查业务系统是否真的启用了 `cas.client.enabled=true` 和 `business-login-proxy.enabled=true`。
- 如果 SLO 到了但业务系统没有失效，通常是：
  - 业务系统没注册成功的 `HttpSession`
  - `cas.client.slo.enabled=false`
  - 多实例环境下没有共享失效状态
- Bearer Token 认证默认会先查本地缓存，再必要时远程 CAS 校验。若你要强制每次远程验证，可以把缓存 TTL 调小，但会增加 CAS 压力。
- 如果你希望多实例场景下的 logout 更快生效，建议业务系统接一个共享的 `CacheManager`，starter 会自动把撤销态写进去。

# Build

To build the project, use:

```bash
# Use --refresh-dependencies to force-update SNAPSHOT versions
./gradlew[.bat] clean build
```

To see what commands/tasks are available to the build script, run:

```bash
./gradlew[.bat] tasks
```

If you need to, on Linux/Unix systems, you can delete all the existing artifacts
(artifacts and metadata) Gradle has downloaded using:

```bash
# Only do this when absolutely necessary
rm -rf $HOME/.gradle/caches/
```

Same strategy applies to Windows too, provided you switch `$HOME` to its equivalent in the above command.

# Keystore

For the server to run successfully, you might need to create a keystore file.
This can either be done using the JDK's `keytool` utility or via the following command:

```bash
./gradlew[.bat] createKeystore
```

Use the password `changeit` for both the keystore and the key/certificate entries.
Ensure the keystore is loaded up with keys and certificates of the server.

# Extension Modules

Extension modules may be specified under the `dependencies` block of the [Gradle build script](build.gradle):

```gradle
dependencies {
    implementation "org.apereo.cas:cas-server-some-module"
    ...
}
```

To collect the list of all project modules and dependencies in the overlay:

```bash
./gradlew[.bat] dependencies
```

# Deployment

On a successful deployment via the following methods, the server will be available at:

* `https://localhost:8443/cas`


## Executable WAR

Run the server web application as an executable WAR. Note that running an executable WAR requires CAS to use an embedded container such as Apache Tomcat, Jetty, etc.

The current servlet container is specified as `-tomcat`.

```bash
java -jar build/libs/cas.war
```

Or via:

```bash
./gradlew[.bat] run
```

It is often an advantage to explode the generated web application and run it in unpacked mode.
One way to run an unpacked archive is by starting the appropriate launcher, as follows:

```bash
jar -xf build/libs/cas.war
cd build/libs
java org.springframework.boot.loader.launch.JarLauncher
```

This is slightly faster on startup (depending on the size of the WAR file) than
running from an unexploded archive. After startup, you should not expect any differences.

Debug the CAS web application as an executable WAR:

```bash
./gradlew[.bat] debug
```

Or via:

```bash
java -Xdebug -Xrunjdwp:transport=dt_socket,address=5000,server=y,suspend=y -jar build/libs/cas.war
```

Run the CAS web application as a *standalone* executable WAR:

```bash
./gradlew[.bat] clean executable
```

### CDS Support

CDS is a JVM feature that can help reduce the startup time and memory footprint of Java applications. CAS via Spring Boot
now has support for easy creation of a CDS friendly layout. This layout can be created by extracting the CAS web application file
with the help of the `tools` jarmode:

```bash
# Note: You must first build the web application with "executable" turned off
java -Djarmode=tools -jar build/libs/cas.war extract

# Perform a training run once
java -XX:ArchiveClassesAtExit=cas.jsa -Dspring.context.exit=onRefresh -jar cas/cas.war

# Run the CAS web application via CDS
java XX:SharedArchiveFile=cas.jsa -jar cas/cas.war
```

## External

Deploy the binary web application file in `build/libs` after a successful build to a servlet container of choice.

# Docker

The following strategies outline how to build and deploy CAS Docker images.

## Jib

The overlay embraces the [Jib Gradle Plugin](https://github.com/GoogleContainerTools/jib) to provide easy-to-use out-of-the-box tooling for building CAS docker images. Jib is an open-source Java containerizer from Google that lets Java developers build containers using the tools they know. It is a container image builder that handles all the steps of packaging your application into a container image. It does not require you to write a Dockerfile or have Docker installed, and it is directly integrated into the overlay.

```bash
# Running this task requires that you have Docker installed and running.
./gradlew build jibDockerBuild
```

## Dockerfile

You can also use the Docker tooling and the provided `Dockerfile` to build and run.
There are dedicated Gradle tasks available to build and push Docker images using the supplied `DockerFile`:

```bash
./gradlew build casBuildDockerImage
```

Once ready, you may also push the images:

```bash
./gradlew casPushDockerImage
```

If credentials (username+password) are required for pull and push operations, they may be specified
using system properties via `-DdockerUsername=...` and `-DdockerPassword=...`.

A `docker-compose.yml` is also provided to orchestrate the build:

```bash
docker-compose build
```

## Spring Boot

You can use the Spring Boot build plugin for Gradle to create CAS container images.
The plugins create an OCI image (the same format as one created by docker build)
by using [Cloud Native Buildpacks](https://buildpacks.io/). You do not need a Dockerfile, but you do need a Docker daemon,
either locally (which is what you use when you build with docker) or remotely
through the `DOCKER_HOST` environment variable. The default builder is optimized for
Spring Boot applications such as CAS, and the image is layered efficiently.

```bash
./gradlew bootBuildImage
```

The first build might take a long time because it has to download some container
images and the JDK, but subsequent builds should be fast.

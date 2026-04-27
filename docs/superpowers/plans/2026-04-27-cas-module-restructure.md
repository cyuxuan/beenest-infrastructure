# CAS Module Restructure Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the CAS overlay implementation from the beenest-cas root project into a `beenest-cas-service` Gradle subproject, making it a sibling of the three existing client starters.

**Architecture:** The root project becomes a pure build coordinator (buildscript + repositories + subprojects config). All CAS overlay code, dependencies, Docker/Jib config move to `beenest-cas-service/build.gradle`. The Dockerfile stays at root because it references root-level Gradle files. Three path fixes are needed in the migrated build config.

**Tech Stack:** Gradle 8.x, Java 21, Apereo CAS 7.3.6 overlay, Spring Boot 3.5.6, Jib, Docker

**Spec:** `docs/superpowers/specs/2026-04-27-cas-module-restructure-design.md`

---

## File Structure

### Created Files
- `beenest-cas/beenest-cas-service/build.gradle` — CAS overlay build config (migrated from root)
- `beenest-cas/beenest-cas-service/src/` — all source and resources (moved from root `src/`)

### Modified Files
- `beenest-cas/build.gradle` — stripped to build coordinator only
- `beenest-cas/settings.gradle` — add `include 'beenest-cas-service'`, change `rootProject.name`
- `beenest-cas/Dockerfile` — rewrite overlay stage for multi-project layout (COPY paths, gradlew target, WAR path)
- `docker-compose.yml` (infrastructure root) — change WAR volume path
- `CLAUDE.md` (infrastructure root) — update build commands and architecture
- `beenest-cas/README.md` — update directory structure

### Deleted (by move)
- `beenest-cas/src/` — moved to `beenest-cas/beenest-cas-service/src/`

---

## Chunk 1: Core Restructure

### Task 1: Create beenest-cas-service subproject build.gradle

**Files:**
- Create: `beenest-cas/beenest-cas-service/build.gradle`

- [ ] **Step 1: Create the directory and build.gradle**

Create `beenest-cas/beenest-cas-service/build.gradle` with the full CAS overlay build configuration migrated from the root `build.gradle`, with three path fixes applied:

```groovy
import org.apache.tools.ant.taskdefs.condition.*
import org.gradle.internal.logging.text.*
import org.apereo.cas.metadata.*
import java.nio.file.*
import java.lang.reflect.*
import org.gradle.internal.logging.text.*
import static org.gradle.internal.logging.text.StyledTextOutput.Style

buildscript {
    repositories {
        if (project.privateRepoUrl) {
          maven {
            url project.privateRepoUrl
            credentials {
              username = project.privateRepoUsername
              password = System.env.PRIVATE_REPO_TOKEN
            }
          }
        }
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = 'https://central.sonatype.com/repository/maven-snapshots/'
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = "https://repo.spring.io/milestone"
            mavenContent { releasesOnly() }
        }
    }
    dependencies {
        classpath "org.springframework.boot:spring-boot-gradle-plugin:${project.springBootVersion}"
        classpath "io.freefair.gradle:maven-plugin:${project.gradleFreeFairPluginVersion}"
        classpath "io.freefair.gradle:lombok-plugin:${project.gradleFreeFairPluginVersion}"
        classpath "com.google.cloud.tools:jib-gradle-plugin:${project.jibVersion}"
        classpath "com.bmuschko:gradle-docker-plugin:${project.gradleDockerPluginVersion}"
        classpath "org.cyclonedx:cyclonedx-gradle-plugin:${project.gradleCyclonePluginVersion}"
        classpath "de.undercouch:gradle-download-task:${project.gradleDownloadTaskVersion}"
        classpath "org.apereo.cas:cas-server-core-api-configuration-model:${project.'cas.version'}"
        classpath "org.apereo.cas:cas-server-support-configuration-metadata-repository:${project.'cas.version'}"
    }
}

repositories {
    if (project.privateRepoUrl) {
      maven {
        url project.privateRepoUrl
        credentials {
          username = project.privateRepoUsername
          password = System.env.PRIVATE_REPO_TOKEN
        }
      }
    }
    mavenLocal()
    mavenCentral()
    maven { url = 'https://oss.sonatype.org/content/repositories/releases' }
    maven {
        url = 'https://central.sonatype.com/repository/maven-snapshots/'
        mavenContent { snapshotsOnly() }
    }
    maven {
        url = "https://repository.apache.org/content/repositories/snapshots"
        mavenContent { snapshotsOnly() }
    }
    maven {
        url = 'https://build.shibboleth.net/nexus/content/repositories/releases/'
        mavenContent { releasesOnly() }
    }
    maven {
        url = "https://build.shibboleth.net/nexus/content/repositories/snapshots"
        mavenContent { snapshotsOnly() }
    }
    maven {
        url = "https://repo.spring.io/milestone"
        mavenContent { releasesOnly() }
    }
}

apply plugin: "io.freefair.war-overlay"
apply plugin: "war"

apply plugin: "org.springframework.boot"
apply plugin: "io.freefair.lombok"
lombok {
    version = "${project.lombokVersion}"
}

apply plugin: "org.cyclonedx.bom"


apply from: rootProject.file("gradle/springboot.gradle")
apply plugin: "com.google.cloud.tools.jib"
apply plugin: "com.bmuschko.docker-remote-api"
apply from: rootProject.file("gradle/tasks.gradle")

def out = services.get(StyledTextOutputFactory).create("cas")
def configurationCacheRequested = services.get(BuildFeatures).configurationCache.requested.getOrElse(true)

configurations {
    all {
        resolutionStrategy {
            cacheChangingModulesFor 0, "seconds"
            cacheDynamicVersionsFor 0, "seconds"
            preferProjectModules()
            def failIfConflict = project.hasProperty("failOnVersionConflict") && Boolean.valueOf(project.getProperty("failOnVersionConflict"))
            if (failIfConflict) {
                failOnVersionConflict()
            }

            if (project.hasProperty("tomcatVersion")) {
                eachDependency { DependencyResolveDetails dependency ->
                    def requested = dependency.requested
                    if (requested.group.startsWith("org.apache.tomcat") && requested.name != "jakartaee-migration")  {
                        dependency.useVersion("${tomcatVersion}")
                    }
                }
            }
        }
        exclude(group: "cglib", module: "cglib")
        exclude(group: "cglib", module: "cglib-full")
        exclude(group: "org.slf4j", module: "slf4j-log4j12")
        exclude(group: "org.slf4j", module: "slf4j-simple")
        exclude(group: "org.slf4j", module: "jcl-over-slf4j")
        exclude(group: "org.apache.logging.log4j", module: "log4j-to-slf4j")
        // 排除 Logback（Spring Boot starter-logging 带入），CAS 使用 Log4j2 作为日志实现
        exclude(group: "ch.qos.logback", module: "logback-classic")
        exclude(group: "ch.qos.logback", module: "logback-core")
        // 排除内嵌 HSQL 数据库，强制使用 PostgreSQL
        exclude(group: "org.hsqldb", module: "hsqldb")
    }
}

war {
    entryCompression = ZipEntryCompression.STORED
    enabled = false
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(project.targetCompatibility)

        def chosenJvmVendor = null
        if (project.jvmVendor != null) {
            out.withStyle(Style.Info).println("JVM vendor ${project.jvmVendor} is requested for the Java toolchain")
            chosenJvmVendor = JvmVendorSpec.of(project.jvmVendor?.toUpperCase())
        }
        if (chosenJvmVendor != null) {
            vendor = chosenJvmVendor
            out.withStyle(Style.Success).println("Using ${chosenJvmVendor} as the JVM vendor for the Java toolchain")
        } else {
            out.withStyle(Style.Info).println("JVM vendor ${project.jvmVendor} is not recognized")
        }
    }
}

bootBuildImage {
    imageName = "${project.'containerImageOrg'}/${project.'containerImageName'}:${project.version}"
}


['jibDockerBuild', 'jibBuildTar', 'jib'].each { taskName ->
    getTasksByName(taskName, true).each(it -> {
        it.notCompatibleWithConfigurationCache("Jib is not compatible with configuration cache");
        it.enabled = !configurationCacheRequested
    })
}

tasks.named("jibBuildTar") {
    dependsOn(tasks.named("jar"))
}

def imagePlatforms = project.dockerImagePlatform.split(",")
def dockerUsername = providers.systemProperty("dockerUsername").getOrNull()
def dockerPassword = providers.systemProperty("dockerPassword").getOrNull()
def imageTagPostFix = providers.systemProperty("dockerImageTagPostfix").getOrElse("")

jib {
    if (configurationCacheRequested) {
        out.withStyle(Style.Info).println("You are seeing this message because the Gradle configuration cache is turned on")
        out.withStyle(Style.Info).println("Running Jib tasks to produce Docker images will require the command-line option: --no-configuration-cache")
        out.withStyle(Style.Info).println("Jib does not support the Gradle configuration cache; Please see https://github.com/GoogleContainerTools/jib/issues/3132")
        out.withStyle(Style.Info).println("Jib tasks are disabled.")
    }
    from {
        image = project.baseDockerImage
        platforms {
            imagePlatforms.each {
                def given = it.split(":")
                platform {
                    architecture = given[0]
                    os = given[1]
                }
            }
        }
    }
    to {
        image = "${project.'containerImageOrg'}/${project.'containerImageName'}:${project.version}"
        credHelper = "osxkeychain"
        if (dockerUsername != null && dockerPassword != null) {
            auth {
                username = "${dockerUsername}"
                password = "${dockerPassword}"
            }
        }
        tags = [project.version]
    }
    container {
        creationTime = "USE_CURRENT_TIMESTAMP"
        entrypoint = ['/docker/entrypoint.sh']
        ports = ['80', '443', '8080', '8443', '8444', '8761', '8888', '5000']
        labels = [version:project.version, name:project.name, group:project.group, org:project.containerImageOrg]
        workingDirectory = '/docker/cas/war'
    }
    extraDirectories {
        paths {
          path {
            from = file('src/main/jib')
          }
          path {
            // FIX 1: etc/ stays at root project level
            from = file(rootProject.file('etc/cas'))
            into = '/etc/cas'
          }
          path {
            from = file("build/libs")
            into = "/docker/cas/war"
          }
        }
        permissions = [
            '/docker/entrypoint.sh': '755'
        ]
    }
    allowInsecureRegistries = project.allowInsecureRegistries
}

import com.bmuschko.gradle.docker.tasks.image.*
tasks.register("casBuildDockerImage", DockerBuildImage) {
    dependsOn("build")

    def imageTag = "${project.'cas.version'}"
    // FIX 2: Docker build context must be root project (Dockerfile references root-level files)
    inputDir = rootProject.projectDir
    images.add("apereo/cas:${imageTag}${imageTagPostFix}")
    images.add("apereo/cas:latest${imageTagPostFix}")
    if (dockerUsername != null && dockerPassword != null) {
        username = dockerUsername
        password = dockerPassword
    }
    doLast {
        out.withStyle(Style.Success).println("Built CAS images successfully.")
    }
}

tasks.register("casPushDockerImage", DockerPushImage) {
    dependsOn("casBuildDockerImage")

    def imageTag = "${project.'cas.version'}"
    images.add("apereo/cas:${imageTag}${imageTagPostFix}")
    images.add("apereo/cas:latest${imageTagPostFix}")

    if (dockerUsername != null && dockerPassword != null) {
        username = dockerUsername
        password = dockerPassword
    }
    doLast {
        out.withStyle(Style.Success).println("Pushed CAS images successfully.")
    }
}


if (project.hasProperty("appServer")) {
    def appServer = project.findProperty('appServer') ?: ''
    out.withStyle(Style.Success).println("Building CAS version ${project.version} with application server ${appServer}")
} else {
    out.withStyle(Style.Success).println("Building CAS version ${project.version} without an application server")
}

dependencies {
    implementation enforcedPlatform("org.apereo.cas:cas-server-support-bom:${project.'cas.version'}")
    implementation platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)

    implementation "org.apereo.cas:cas-server-core-api-configuration-model"
    implementation "org.apereo.cas:cas-server-webapp-init"

    if (appServer == '-tomcat') {
        implementation "org.apereo.cas:cas-server-webapp-init-tomcat"
    }

    developmentOnly "org.springframework.boot:spring-boot-devtools:${project.springBootVersion}"

    // ===== Beenest 核心 API 模块（自定义认证/服务管理需要） =====
    implementation "org.apereo.cas:cas-server-core-authentication-api"
    implementation "org.apereo.cas:cas-server-core-services-api"
    implementation "org.apereo.cas:cas-server-core-api-services"
    implementation "org.apereo.cas:cas-server-core-tickets-api"
    implementation "org.apereo.cas:cas-server-core-authentication-attributes"
    implementation "org.apereo.cas:cas-server-core-web-api"

    // ===== Beenest 自定义 CAS 模块 =====
    implementation "org.apereo.cas:cas-server-support-redis-ticket-registry"
    implementation "org.apereo.cas:cas-server-support-redis-modules"
    implementation "org.apereo.cas:cas-server-support-jpa-service-registry"
    implementation "org.apereo.cas:cas-server-support-jdbc-authentication"
    implementation "org.apereo.cas:cas-server-support-jdbc-drivers"
    implementation "org.apereo.cas:cas-server-support-jpa-hibernate:${project.'cas.version'}"
    implementation "org.apereo.cas:cas-server-support-generic"
    implementation "org.apereo.cas:cas-server-support-rest"
    implementation "org.apereo.cas:cas-server-support-thymeleaf"

    // ===== Beenest 业务依赖 =====
    implementation "org.springframework.boot:spring-boot-starter-jdbc"
    implementation "org.springframework.boot:spring-boot-starter-data-redis"
    implementation "org.springframework.security:spring-security-web"
    implementation "org.springframework.security:spring-security-config"
    implementation "org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4"
    runtimeOnly 'org.postgresql:postgresql:42.7.7'
    implementation "com.github.binarywang:weixin-java-miniapp:4.6.0"
    implementation "com.alipay.sdk:alipay-sdk-java:4.39.79.ALL"

    // ===== Phase 1: 企业级基础设施 =====
    implementation "org.apereo.cas:cas-server-support-audit-jdbc"
    implementation "org.apereo.cas:cas-server-support-events-jpa"
    implementation "org.apereo.cas:cas-server-support-reports"
    implementation "org.apereo.cas:cas-server-support-palantir"
    implementation "org.apereo.cas:cas-server-webapp-resources"

    implementation "org.springframework.boot:spring-boot-starter-actuator"

    // ===== Phase 2: 协议层（企业级统一身份提供商） =====
    implementation "org.apereo.cas:cas-server-support-saml-idp"
    implementation "org.apereo.cas:cas-server-support-saml-sp-integrations"
    implementation "org.apereo.cas:cas-server-support-oauth-webflow"
    implementation "org.apereo.cas:cas-server-support-oidc"
    implementation "org.apereo.cas:cas-server-support-rest-services"

    // ===== Phase 3: 用户属性 =====
    implementation "org.apereo.cas:cas-server-support-person-directory"

    // ===== Phase 4: 企业级功能层 =====
    implementation "org.apereo.cas:cas-server-core-notifications-api"

    testImplementation "org.springframework.boot:spring-boot-starter-test"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}

tasks.named("test") {
    useJUnitPlatform()
}
```

Three path fixes applied (marked with `FIX 1`, `FIX 2` in the code above):
1. **Jib `extraDirectories`**: `file('etc/cas')` → `file(rootProject.file('etc/cas'))` (etc/ is at root)
2. **`casBuildDockerImage`**: `inputDir = project.projectDir` → `inputDir = rootProject.projectDir` (Dockerfile at root)
3. No fix needed for `file('src/main/jib')` and `file("build/libs")` — these resolve correctly within the subproject

---

### Task 2: Update settings.gradle

**Files:**
- Modify: `beenest-cas/settings.gradle`

- [ ] **Step 1: Update settings.gradle**

Change `rootProject.name` and add the new subproject:

```groovy
plugins {
    id "org.gradle.toolchains.foojay-resolver-convention" version "${gradleFoojayPluginVersion}"
}
rootProject.name = 'beenest-cas'

include 'beenest-cas-service'
include 'beenest-cas-client-spring-security-starter'
include 'beenest-cas-client-login-gateway-starter'
include 'beenest-cas-client-resource-server-starter'
```

---

### Task 3: Strip root build.gradle to build coordinator

**Files:**
- Modify: `beenest-cas/build.gradle`

- [ ] **Step 1: Replace root build.gradle with coordinator-only config**

The root `build.gradle` should contain ONLY buildscript dependencies, repositories, and a `subprojects` block for common config. All CAS overlay-specific config has moved to `beenest-cas-service/build.gradle`.

```groovy
import org.gradle.internal.logging.text.*
import static org.gradle.internal.logging.text.StyledTextOutput.Style

buildscript {
    repositories {
        if (project.privateRepoUrl) {
          maven {
            url project.privateRepoUrl
            credentials {
              username = project.privateRepoUsername
              password = System.env.PRIVATE_REPO_TOKEN
            }
          }
        }
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = 'https://central.sonatype.com/repository/maven-snapshots/'
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = "https://repo.spring.io/milestone"
            mavenContent { releasesOnly() }
        }
    }
    dependencies {
        classpath "org.springframework.boot:spring-boot-gradle-plugin:${project.springBootVersion}"
        classpath "io.freefair.gradle:maven-plugin:${project.gradleFreeFairPluginVersion}"
        classpath "io.freefair.gradle:lombok-plugin:${project.gradleFreeFairPluginVersion}"
        classpath "com.google.cloud.tools:jib-gradle-plugin:${project.jibVersion}"
        classpath "com.bmuschko:gradle-docker-plugin:${project.gradleDockerPluginVersion}"
        classpath "org.cyclonedx:cyclonedx-gradle-plugin:${project.gradleCyclonePluginVersion}"
        classpath "de.undercouch:gradle-download-task:${project.gradleDownloadTaskVersion}"
        classpath "org.apereo.cas:cas-server-core-api-configuration-model:${project.'cas.version'}"
        classpath "org.apereo.cas:cas-server-support-configuration-metadata-repository:${project.'cas.version'}"
    }
}

// 公共仓库配置，供所有子项目继承
subprojects {
    repositories {
        if (project.privateRepoUrl) {
          maven {
            url project.privateRepoUrl
            credentials {
              username = project.privateRepoUsername
              password = System.env.PRIVATE_REPO_TOKEN
            }
          }
        }
        mavenLocal()
        mavenCentral()
        maven { url = 'https://oss.sonatype.org/content/repositories/releases' }
        maven {
            url = 'https://central.sonatype.com/repository/maven-snapshots/'
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = "https://repository.apache.org/content/repositories/snapshots"
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = 'https://build.shibboleth.net/nexus/content/repositories/releases/'
            mavenContent { releasesOnly() }
        }
        maven {
            url = "https://build.shibboleth.net/nexus/content/repositories/snapshots"
            mavenContent { snapshotsOnly() }
        }
        maven {
            url = "https://repo.spring.io/milestone"
            mavenContent { releasesOnly() }
        }
    }

    // 全局排除规则（仅影响有 configurations 的子项目）
    configurations.all {
        resolutionStrategy {
            cacheChangingModulesFor 0, "seconds"
            cacheDynamicVersionsFor 0, "seconds"
            preferProjectModules()
        }
        exclude(group: "cglib", module: "cglib")
        exclude(group: "cglib", module: "cglib-full")
        exclude(group: "org.slf4j", module: "slf4j-log4j12")
        exclude(group: "org.slf4j", module: "slf4j-simple")
        exclude(group: "org.slf4j", module: "jcl-over-slf4j")
        exclude(group: "org.apache.logging.log4j", module: "log4j-to-slf4j")
        exclude(group: "ch.qos.logback", module: "logback-classic")
        exclude(group: "ch.qos.logback", module: "logback-core")
        exclude(group: "org.hsqldb", module: "hsqldb")
    }
}
```

Note: `beenest-cas-service/build.gradle` declares its own `repositories {}` and `configurations {}` blocks (needed for its specific resolution strategy and failOnVersionConflict support). The `subprojects` block provides a fallback for the starter subprojects that don't declare their own repositories. The duplicate exclude configuration is harmless — both closures execute but produce the same result.

**IMPORTANT: Tasks 1-6 must execute sequentially.** Do not parallelize them. Task 1 creates the subproject build.gradle, Task 2 registers it in settings, Task 3 strips the root config, Task 4 moves the source, Tasks 5-6 fix external references. A build attempted between any of these steps would fail.

---

### Task 4: Move source files to subproject

**Files:**
- Move: `beenest-cas/src/` → `beenest-cas/beenest-cas-service/src/`

- [ ] **Step 1: Move src/ directory**

```bash
cd beenest-cas
mkdir -p beenest-cas-service
mv src beenest-cas-service/src
```

This moves the entire `src/` tree (main/java, main/resources, main/jib, test/) into the subproject.

---

### Task 5: Update Dockerfile for multi-project layout

**Files:**
- Modify: `beenest-cas/Dockerfile`

The Dockerfile performs a multi-stage build: it copies the project into the container, runs `./gradlew clean build`, then extracts the WAR. After restructuring, the subproject's `build.gradle` and `src/` must be correctly positioned inside the container for Gradle multi-project resolution. The WAR output path also changes.

- [ ] **Step 1: Rewrite the overlay stage COPY and build commands**

Replace the entire overlay stage in `beenest-cas/Dockerfile` (lines 1-25):

```dockerfile
ARG BASE_IMAGE="azul/zulu-openjdk:21"

FROM $BASE_IMAGE AS overlay

ARG EXT_BUILD_COMMANDS=""
ARG EXT_BUILD_OPTIONS=""

ARG JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"
ENV JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS}"

WORKDIR /cas-overlay
COPY ./beenest-cas-service/src beenest-cas-service/src/
COPY ./beenest-cas-service/build.gradle beenest-cas-service/build.gradle
COPY ./gradle/ gradle/
COPY ./gradlew ./settings.gradle ./build.gradle ./gradle.properties ./lombok.config ./

RUN mkdir -p ~/.gradle \
    && echo "org.gradle.daemon=false" >> ~/.gradle/gradle.properties \
    && echo "org.gradle.configureondemand=true" >> ~/.gradle/gradle.properties \
    && chmod 750 ./gradlew \
    && ./gradlew --version;

RUN ./gradlew :beenest-cas-service:clean :beenest-cas-service:build $EXT_BUILD_COMMANDS --parallel --no-daemon -Pexecutable=false $EXT_BUILD_OPTIONS;

RUN java -Djarmode=tools -jar beenest-cas-service/build/libs/cas.war extract \
    && java -XX:ArchiveClassesAtExit=./cas/cas.jsa -Dspring.context.exit=onRefresh -jar cas/cas.war
```

Key changes:
1. `COPY ./src src/` → `COPY ./beenest-cas-service/src beenest-cas-service/src/` (src moved to subproject)
2. **NEW**: `COPY ./beenest-cas-service/build.gradle beenest-cas-service/build.gradle` (Gradle needs the subproject build file)
3. `./gradlew clean build` → `./gradlew :beenest-cas-service:clean :beenest-cas-service:build` (target the subproject explicitly, skip starter builds in Docker)
4. `build/libs/cas.war` → `beenest-cas-service/build/libs/cas.war` (WAR output is now in subproject build dir)

---

### Task 6: Update infrastructure docker-compose.yml WAR path

**Files:**
- Modify: `docker-compose.yml:168`

- [ ] **Step 1: Fix WAR volume path**

Change the beenest-cas service volumes in the infrastructure root `docker-compose.yml`:

```yaml
# Before (line 168):
- ./beenest-cas/build/libs/cas.war:/app/app.war

# After:
- ./beenest-cas/beenest-cas-service/build/libs/cas.war:/app/app.war
```

---

### Task 7: Verify build

- [ ] **Step 1: Clean and build everything**

```bash
cd beenest-cas
./gradlew clean build
```

Expected: BUILD SUCCESSFUL. All four subprojects compile:
- `beenest-cas-service` — CAS overlay WAR built
- `beenest-cas-client-spring-security-starter` — JAR published
- `beenest-cas-client-login-gateway-starter` — JAR published
- `beenest-cas-client-resource-server-starter` — JAR published

- [ ] **Step 2: Verify WAR output location**

```bash
ls -la beenest-cas-service/build/libs/cas.war
```

Expected: `cas.war` exists in the subproject build directory.

- [ ] **Step 3: Verify tests pass**

```bash
./gradlew :beenest-cas-service:test
```

Expected: All tests PASS.

- [ ] **Step 4: Commit the restructure**

```bash
git add beenest-cas/
git add docker-compose.yml
git commit -m "refactor(cas): extract overlay into beenest-cas-service subproject

- Move src/ to beenest-cas-service/src/
- Move build config to beenest-cas-service/build.gradle
- Root project becomes pure build coordinator
- Dockerfile stays at root, COPY path updated
- docker-compose.yml WAR volume path updated"
```

---

## Chunk 2: Documentation Updates

### Task 8: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update beenest-cas build commands**

In the "Build and Development Commands" > "beenest-cas" section, update commands:

```markdown
### beenest-cas

```bash
cd beenest-cas

# Build (includes all subprojects: beenest-cas-service + 3 starters)
./gradlew clean build

# Run locally (starts at https://localhost:8443/cas)
./gradlew :beenest-cas-service:bootRun

# Run tests
./gradlew :beenest-cas-service:test

# Debug mode (JDWP on port 5000)
./gradlew :beenest-cas-service:debug

# Docker build
./gradlew :beenest-cas-service:jibDockerBuild

# Generate keystore for local HTTPS
./gradlew :beenest-cas-service:createKeystore
```
```

- [ ] **Step 2: Update Architecture section**

In the "Architecture" > "beenest-cas" section, update module description:

Add note about 4-subproject structure:
```markdown
**Root package**: `org.apereo.cas.beenest`

**Project structure** (4 Gradle subprojects):
- `beenest-cas-service` — CAS overlay server implementation (WAR)
- `beenest-cas-client-spring-security-starter` — Spring Security client starter (published as `club.beenest.cas:beenest-cas-client-spring-security-starter:1.0.0-SNAPSHOT`)
- `beenest-cas-client-login-gateway-starter` — Login gateway starter
- `beenest-cas-client-resource-server-starter` — Resource server starter
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for beenest-cas subproject restructure"
```

---

### Task 9: Update README.md

**Files:**
- Modify: `beenest-cas/README.md`

- [ ] **Step 1: Update directory structure and build examples in README**

Update the directory structure diagram and any `./gradlew` command examples in `beenest-cas/README.md` to reflect the new subproject paths (e.g., `./gradlew :beenest-cas-service:bootRun`).

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/README.md
git commit -m "docs: update beenest-cas README for subproject restructure"
```

---

### Task 10: Final verification

- [ ] **Step 1: Full clean build**

```bash
cd beenest-cas
./gradlew clean build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify starter publication**

```bash
./gradlew :beenest-cas-client-spring-security-starter:publishToMavenLocal
```

Expected: Publishes to local Maven repo successfully.

- [ ] **Step 3: Verify Dockerfile references are valid**

Check that all paths referenced by the Dockerfile exist at the expected locations:
```bash
# Root-level files referenced by Dockerfile
ls ./gradlew ./settings.gradle ./build.gradle ./gradle.properties ./lombok.config ./gradle/
# Subproject files referenced by Dockerfile
ls ./beenest-cas-service/src ./beenest-cas-service/build.gradle
# etc/ referenced by Dockerfile second stage
ls ./etc/cas/ ./etc/cas/config/ ./etc/cas/services/ ./etc/cas/saml/
```

Expected: All files/directories exist.

- [ ] **Step 4: Verify openrewrite.gradle**

```bash
grep -n 'src/' openrewrite.gradle || echo "No src/ references found"
```

If `openrewrite.gradle` contains `src/` references, they may need adjustment since the root project no longer has source code. Evaluate whether OpenRewrite should target `beenest-cas-service` instead.

- [ ] **Step 5: Verify bin/ scripts**

```bash
grep -rn 'src/' bin/ | head -10 || echo "No src/ references found in bin/"
```

If any `bin/` scripts reference `src/` paths relative to the root project, update them to reference `beenest-cas-service/src/`.

- [ ] **Step 6: Commit any verification fixes**

```bash
git add -A
git commit -m "fix: post-restructure verification fixes"
```

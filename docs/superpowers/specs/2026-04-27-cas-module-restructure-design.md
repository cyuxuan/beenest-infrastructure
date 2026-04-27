# CAS Module Restructure Design

**Date**: 2026-04-27
**Status**: Approved
**Scope**: beenest-cas Gradle multi-project restructuring

## 1. Background

Current `beenest-cas` structure has the CAS overlay implementation (Java source, resources, Docker config) directly in the root project, while three client starters exist as Gradle subprojects. This creates an unbalanced hierarchy where the root project is both the build coordinator AND the main service implementation.

## 2. Goal

Restructure `beenest-cas` so the CAS overlay service becomes a Gradle subproject (`beenest-cas-service`) at the same level as the three client starters. The root project becomes a pure build coordinator with no business code.

## 3. Target Structure

```
beenest-cas/                                       # Gradle multi-project root (build coordinator)
├── build.gradle                                   # buildscript + common repos + subproject config only
├── settings.gradle                                # includes 4 subprojects
├── gradle.properties                              # shared version properties (unchanged)
├── gradle/                                        # Gradle script plugins
│   ├── springboot.gradle                          # Spring Boot build conventions
│   └── tasks.gradle                               # common tasks
├── etc/                                           # CAS certs/config (Docker build)
├── Dockerfile                                     # STAYS at root (depends on root-level gradle/config files)
├── docker-compose.yml                             # STAYS at root (builds from root context)
├── lombok.config                                  # STAYS at root (referenced by Dockerfile)
├── beenest-cas-service/                           # NEW: CAS overlay service
│   ├── build.gradle                               # war + Jib + full dependency declarations
│   └── src/
│       ├── main/java/org/apereo/cas/beenest/...   # all Java source files
│       ├── main/resources/                        # all resources (YAML/templates/migrations/scripts/mapper)
│       ├── main/jib/docker/entrypoint.sh          # Docker entrypoint
│       └── test/...                               # all test code
├── beenest-cas-client-spring-security-starter/    # unchanged
├── beenest-cas-client-login-gateway-starter/      # unchanged
└── beenest-cas-client-resource-server-starter/    # unchanged
```

### 3.1 Dockerfile Decision: Stay at Root

The Dockerfile **stays at `beenest-cas/Dockerfile`** (not moved into the subproject). Reason: it references root-level files (`./gradle/`, `./settings.gradle`, `./build.gradle`, `./gradle.properties`, `./lombok.config`, `./etc/cas/`) that belong to the Gradle multi-project root. The only change needed is the `COPY ./src` line:

```dockerfile
# Before:
COPY ./src src/

# After:
COPY ./beenest-cas-service/src src/
```

This keeps the Docker build context at `beenest-cas/` root, which is the simplest and least risky approach. The `casBuildDockerImage` Gradle task also stays configured from the service subproject but sets `inputDir = rootProject.projectDir` to maintain the root build context.

## 4. Build Configuration Split

### 4.1 Root `build.gradle` (~80 lines)

Retains:
- `buildscript` block: Spring Boot plugin, Freefair, Jib, Docker, CycloneDX, download-task, CAS metadata classpaths
- `repositories` block: all repository declarations (private, mavenLocal, mavenCentral, snapshots, etc.)
- `subprojects` block: common repository inheritance and global exclude rules

Removes:
- `apply plugin: "io.freefair.war-overlay"` / `"war"` / `"org.springframework.boot"` / etc.
- `dependencies {}` block (all CAS overlay dependencies)
- `war {}`, `jib {}`, `java {}` toolchain, `bootBuildImage {}` configurations
- Docker image build tasks (`casBuildDockerImage`, `casPushDockerImage`)
- All CAS-specific configuration

### 4.2 `beenest-cas-service/build.gradle` (~290 lines)

Migrated from root project:
- All plugin applications (war-overlay, spring-boot, lombok, cyclonedx, jib, docker)
- `apply from: rootProject.file("gradle/springboot.gradle")`
- `apply from: rootProject.file("gradle/tasks.gradle")`
- `configurations {}` block with resolution strategy and excludes
- `war {}` configuration
- `java { toolchain {} }` configuration
- `bootBuildImage {}` configuration
- Jib configuration block — **with path fixes** (see Section 4.4)
- Docker build/push tasks — **with `inputDir` fix** (see Section 4.4)
- `dependencies {}` block (all CAS modules + business dependencies)
- Test configuration

### 4.3 `settings.gradle`

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

Changes: `rootProject.name` from `'cas'` to `'beenest-cas'`, added `include 'beenest-cas-service'`.

Side effect: IDE project name changes from "cas" to "beenest-cas". Build artifacts (cas.war) are unaffected since `archiveBaseName` is hardcoded in `springboot.gradle`.

### 4.4 Path Fixes Required in `beenest-cas-service/build.gradle`

When the build config moves to the subproject, some relative paths must be adjusted:

| Config | Current Path (root) | Fixed Path (subproject) | Why |
|--------|---------------------|------------------------|-----|
| Jib `extraDirectories` `from = file('etc/cas')` | resolves to `beenest-cas/etc/cas` | `file(rootProject.file('etc/cas'))` | `etc/` stays at root |
| `casBuildDockerImage` `inputDir = project.projectDir` | resolves to `beenest-cas/` | `inputDir = rootProject.projectDir` | Dockerfile context is root |
| `tasks.gradle` `copyCasConfiguration` `from "etc/cas/config"` | resolves to `beenest-cas/etc/cas/config` | No fix needed — this task copies to `/etc/cas/config` on the host, not used in CI |

**Note on `gradle/tasks.gradle`**: Most tasks use `$buildDir` or `$projectDir` which resolve correctly per-subproject. The `getResource` task at line 329 creates `new File("src/main/resources")` which resolves correctly since `src/` moves to the subproject. The `copyCasConfiguration` task at line 214 uses `"etc/cas/config"` — this will resolve to the non-existent `beenest-cas-service/etc/cas/config`, but this is a manual helper task (copies config to local `/etc/cas`), not used in CI. Can be fixed later if needed.

**Note on `gradle/springboot.gradle`**: Line 1 applies `plugin: "java"`. This must only be applied by `beenest-cas-service`, not by the root project or starter subprojects. Currently only the service subproject will apply this script, so this is safe. The `srcDirs` path at line 6 uses `project.getProjectDir()` which correctly resolves to the subproject directory.

## 5. File Migration Map

| Source | Destination | Notes |
|--------|-------------|-------|
| `src/main/java/` | `beenest-cas-service/src/main/java/` | All Java source files |
| `src/main/resources/` | `beenest-cas-service/src/main/resources/` | YAML, templates, Flyway, Groovy scripts, mapper, services JSON |
| `src/main/jib/` | `beenest-cas-service/src/main/jib/` | Docker entrypoint |
| `src/test/` | `beenest-cas-service/src/test/` | All test code |
| Root `build.gradle` (dependencies/jib/war parts) | `beenest-cas-service/build.gradle` | Split migration |

## 6. Unchanged Files

- `Dockerfile` — stays at root, only `COPY ./src` line changes to `COPY ./beenest-cas-service/src`
- `docker-compose.yml` (beenest-cas local) — stays at root, `build: .` still works (Dockerfile at root)
- `lombok.config` — stays at root (referenced by Dockerfile)
- `gradle.properties` — shared version properties
- `gradle/springboot.gradle`, `gradle/tasks.gradle` — script plugins (relative paths still work)
- `etc/` — CAS certificates and config
- Three client starters — code and build.gradle completely unchanged
- `.github/`, `puppeteer/`, `bin/` — CI/helper scripts (verify `bin/` scripts don't hardcode root `src/` paths)
- `openrewrite.gradle` — stays at root level (verify for any `src/` path references)

## 7. Build Command Changes

| Operation | Before | After |
|-----------|--------|-------|
| Build overlay only | `./gradlew clean build` | `./gradlew :beenest-cas-service:clean :beenest-cas-service:build` |
| Run overlay | `./gradlew bootRun` | `./gradlew :beenest-cas-service:bootRun` |
| Build Docker (Jib) | `./gradlew build jibDockerBuild` | `./gradlew :beenest-cas-service:jibDockerBuild` |
| Build Docker (Dockerfile) | `./gradlew casBuildDockerImage` | `./gradlew :beenest-cas-service:casBuildDockerImage` |
| Build everything | `./gradlew clean build` | `./gradlew clean build` (unchanged) |
| Test overlay | `./gradlew test` | `./gradlew :beenest-cas-service:test` |
| Build a starter | `./gradlew :beenest-cas-client-...-starter:build` | Unchanged |

## 8. External References to Update

### 8.1 `CLAUDE.md` (beenest-infrastructure root)
- Update build commands in "beenest-cas" section
- Update architecture description to reflect 4-subproject structure
- Update module list

### 8.2 `README.md` (beenest-cas)
- Update directory structure diagram
- Update build command examples

### 8.3 `docker-compose.yml` (beenest-infrastructure root)
- **Line 168**: WAR volume path must change:
  ```yaml
  # Before:
  - ./beenest-cas/build/libs/cas.war:/app/app.war
  # After:
  - ./beenest-cas/beenest-cas-service/build/libs/cas.war:/app/app.war
  ```

### 8.4 `.github/` CI configs
- Verify `./gradlew build` still works (it does — builds all subprojects)
- No specific changes expected since `./gradlew clean build` builds everything

### 8.5 `Dockerfile` (beenest-cas root)

The Dockerfile overlay stage needs comprehensive updates for multi-project layout:
- **Line 12**: `COPY ./src src/` → `COPY ./beenest-cas-service/src beenest-cas-service/src/`
- **NEW line after 12**: `COPY ./beenest-cas-service/build.gradle beenest-cas-service/build.gradle`
- **Line 22**: `./gradlew clean build` → `./gradlew :beenest-cas-service:clean :beenest-cas-service:build`
- **Line 24**: `build/libs/cas.war` → `beenest-cas-service/build/libs/cas.war`

## 9. Implementation Steps

1. Create `beenest-cas-service/` directory
2. Write `beenest-cas-service/build.gradle` (migrated from root with path fixes from Section 4.4)
3. Move `src/` to `beenest-cas-service/src/`
4. Update `Dockerfile`: fix COPY paths, add subproject build.gradle copy, fix gradlew command, fix WAR path
5. Update `settings.gradle` (add include, change rootProject.name)
6. Rewrite root `build.gradle` (strip to build coordinator)
7. Verify build: `./gradlew clean build`
8. Update `docker-compose.yml` (beenest-infrastructure root) WAR volume path
9. Update documentation (`CLAUDE.md`, `README.md`)
10. Verify `openrewrite.gradle` and `bin/` scripts for root `src/` path references

## 10. Risks

- **Low**: Java package names unchanged (`org.apereo.cas.beenest.*`), no code changes needed
- **Low**: Starter build.gradle files unchanged, no dependency on root Java code
- **Low**: `gradle.properties` unchanged, version management unaffected
- **Low**: Dockerfile stays at root, Docker build context unchanged, only `COPY ./src` path changes
- **Medium**: `tasks.gradle` `copyCasConfiguration` task uses `"etc/cas/config"` relative path — will resolve to non-existent path in subproject context. This is a manual helper task, not used in CI. Can be fixed later.
- **Medium**: `openrewrite.gradle` should be verified for any `src/` path references after restructuring
- **Medium**: `bin/` scripts may reference root `src/` paths — should be verified
- **Info**: IDE project name changes from "cas" to "beenest-cas" due to `rootProject.name` change

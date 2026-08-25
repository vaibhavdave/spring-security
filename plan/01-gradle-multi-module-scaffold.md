# Story 01 — Gradle Multi-Module Scaffold

**Depends on:** [Story 00 — Prerequisites & Bootstrap](00-prerequisites-and-bootstrap.md)
**Unlocks:** [Story 02 — common-security Library](02-common-security-library.md)

## Goal

Stand up the root Gradle build and the four (empty) module directories it orchestrates, with the
Gradle wrapper committed so nobody needs a system-wide Gradle install to build the project.

## Context

This is a **multi-module** build: one root project that owns shared build logic, and four
subprojects — one shared library (`common-security`) and three independently-runnable Spring Boot
applications (`user-service`, `order-service`, `admin-service`). All four are declared in
`settings.gradle.kts`; shared conventions (Java 21 toolchain, JUnit 5, the Spring Boot BOM) live
once in the root `build.gradle.kts` and apply to every subproject, so individual modules only ever
declare *their own* dependencies.

## Tasks

### Task 1 — Generate and commit the Gradle wrapper

You need a system Gradle install *once*, just to bootstrap the wrapper — after this task, every
build (yours and every later story's) goes through `./gradlew` instead.

```bash
# any recent system Gradle works to bootstrap; sdkman is the easiest way to get one temporarily
sdk install gradle 8.14.3   # or: brew install gradle / your package manager

gradle wrapper --gradle-version 8.14.3 --distribution-type bin
```

This generates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and
`gradle/wrapper/gradle-wrapper.properties`. Confirm `gradle/wrapper/gradle-wrapper.properties`
contains:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Make `gradlew` executable: `chmod +x gradlew`.

### Task 2 — Root `settings.gradle.kts`

```kotlin
rootProject.name = "enterprise-security-microservices"

include(
    "common-security",
    "user-service",
    "order-service",
    "admin-service"
)
```

### Task 3 — Root `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx1536m
org.gradle.caching=true
org.gradle.parallel=true
```

### Task 4 — Root `build.gradle.kts`

This is the one file every subproject inherits from. Create it exactly as below — the comments
explain the two decisions that matter most: why `bootJar`/`jar` get selectively disabled, and why
the toolchain is pinned centrally instead of per module.

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.4" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

val bootModules = setOf(
    "user-service",
    "order-service",
    "admin-service"
)

allprojects {
    group = "com.enterprise.security"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "jvm-test-suite")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
        }
    }

    if (project.name in bootModules) {
        apply(plugin = "org.springframework.boot")
        // Only the executable bootJar is a meaningful artifact for these modules; the plain jar
        // Boot's plugin also builds by default would just be a partial, non-runnable duplicate.
        tasks.findByName("jar")?.let { it.enabled = false }
    } else {
        // library modules (common-security): produce a plain jar, no executable boot jar
        tasks.findByName("bootJar")?.let { it.enabled = false }
    }

    testing {
        suites {
            named<JvmTestSuite>("test") {
                useJUnitJupiter()
            }
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
}
```

`-parameters` matters later: Spring MVC's `@PathVariable`/`@RequestParam` binding-by-name (used
throughout the controllers in Stories 03–05) relies on parameter names being present in bytecode.

### Task 5 — Four module directories with minimal build files

Dependencies get added module-by-module in their own stories — for now each `build.gradle.kts`
just declares which kind of module it is.

`common-security/build.gradle.kts` (a plain library, not a Boot app):
```kotlin
plugins {
    `java-library`
}
```

`user-service/build.gradle.kts`, `order-service/build.gradle.kts`, `admin-service/build.gradle.kts`
(identical for now — each is a Boot application):
```kotlin
plugins {
    java
    id("org.springframework.boot")
}
```

### Task 6 — Second commit

```bash
git add settings.gradle.kts gradle.properties build.gradle.kts gradlew gradlew.bat gradle/ \
        common-security/build.gradle.kts user-service/build.gradle.kts \
        order-service/build.gradle.kts admin-service/build.gradle.kts
git commit -m "Initial Gradle multi-module scaffold"
```

## Definition of done

- [ ] `./gradlew --version` prints Gradle 8.14.3 (proves the wrapper works without a system install)
- [ ] `./gradlew projects` lists all four subprojects (`:common-security`, `:user-service`,
      `:order-service`, `:admin-service`) with no configuration errors
- [ ] `git status` shows a clean tree after the commit

**Expected, not a bug:** `./gradlew build` will still fail at this point — the three Boot modules
have no source and no `@SpringBootApplication` main class yet, so `bootJar` has nothing to package.
That's resolved as each module gets real code in Stories 02–05; don't chase it now.

## What NOT to do yet

No dependencies, no Java source, no `application.yml` in any module — those belong to Stories 02
(`common-security`), 03 (`user-service`), 04 (`order-service`), and 05 (`admin-service`).

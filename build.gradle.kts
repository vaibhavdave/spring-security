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
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    if (project.name in bootModules) {
        apply(plugin = "org.springframework.boot")
    } else {
        // library modules (common-security): produce a plain jar, no executable boot jar
        tasks.findByName("bootJar")?.enabled(false)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
    }
}

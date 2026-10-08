import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libraries.findVersion("spring-boot").get()}")
    }
    // Boot 4.0.8 manages Testcontainers 2.0.x, whose modules were renamed: with
    // only the core managed, the build mixed a 2.0.5 core with 1.20.4
    // postgresql/junit-jupiter modules. Explicit entries win over the imported
    // BOM, so the whole family stays on the catalog's version - the move to 2.x
    // is its own change (docs/ROADMAP.md, M2), not a release's.
    dependencies {
        dependencySet("org.testcontainers:${libraries.findVersion("testcontainers").get()}") {
            entry("testcontainers")
            entry("database-commons")
            entry("jdbc")
            entry("postgresql")
            entry("junit-jupiter")
        }
    }
}

// Kotlin classes are final by default, and Hibernate cannot build a proxy for a
// final class. Without this, every `@ManyToOne(fetch = LAZY)` silently degrades
// to eager: the association still works, nothing warns, and each parent load
// drags its target along - which is exactly the N+1 the fetch plans are written
// to avoid.
//
// `kotlin("plugin.spring")` (applied above) only opens Spring's own annotations,
// so JPA's have to be listed explicitly. This belongs here, in the plugin every
// module applies, and not in `granatum.spring-boot-app`: that one is applied
// solely to `app`, which has no entities.
//
// Collection associations are unaffected either way - a lazy collection is a
// PersistentBag and needs no proxy - which is why the problem stays invisible
// until someone counts the queries.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

configure<KotlinJvmProjectExtension> {
    jvmToolchain(21)
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Nothing else loads `.env`, so without this the tests would depend on
    // fallbacks baked into application.yml - which is exactly what was removed.
    loadDotEnv(rootDir).forEach { (key, value) -> environment(key, value) }

    // Always overrides whatever `.env` says: tests only round-trip tokens, so a
    // key generated per run is strictly better than any committed one, and it
    // keeps the repository free of signing keys even in test resources.
    environment("JWT_SECRET_BASE64", randomJwtKeyBase64())
}

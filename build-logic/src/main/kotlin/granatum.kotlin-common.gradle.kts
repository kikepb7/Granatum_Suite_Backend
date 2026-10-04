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
    maven { url = uri("https://repo.spring.io/milestone") }
    maven { url = uri("https://repo.spring.io/snapshot") }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libraries.findVersion("spring-boot").get()}")
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
}

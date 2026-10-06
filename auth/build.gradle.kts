plugins {
    id("java-library")
    id("granatum.spring-boot-service")
    kotlin("plugin.jpa")
}

dependencies {
    // Principio I: este modulo depende de `common` y de nada mas. La informacion
    // que necesita sobre la persona empleada -si existe y si esta activa- llega
    // por el contrato DirectorioEmpleados, declarado en `common` e implementado
    // por `timetracking`. Anadir aqui `projects.timetracking` seria la violacion
    // que el contrato existe para evitar, y el compilador es quien lo sostiene.
    implementation(projects.common)

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.postgresql)

    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)

    // Lo necesita Argon2PasswordEncoder. Solo en runtime: el codigo nunca
    // importa nada de BouncyCastle, es Spring Security quien lo invoca.
    runtimeOnly(libs.bouncycastle.provider)

    testImplementation(kotlin("test"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.spring.boot.flyway)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
}

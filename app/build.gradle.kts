plugins {
    id("granatum.spring-boot-app")
}

group = "com.granatum"
// SemVer (docs/RAMAS.md): the next version with -SNAPSHOT on develop, the exact
// one on release/* and hotfix/* branches, and on main.
version = "1.0.0-SNAPSHOT"
description = "Granatum Suite backend"

dependencies {
    implementation(projects.common)
    implementation(projects.features.inventory)
    implementation(projects.features.timetracking)
    implementation(projects.features.auth)
    implementation(projects.features.invoices)
    implementation(projects.features.absences)
    implementation(projects.features.notifications)

    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.security)

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.datatype)
    // The OpenAPI document of the whole API, generated from the controllers. In
    // app only: it is where every feature's controllers meet, and the feature
    // modules stay free of documentation annotations.
    implementation(libs.springdoc.openapi.webmvc.ui)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.boot.flyway)
    runtimeOnly(libs.postgresql)
    developmentOnly(libs.spring.boot.devtools)

    // MockMvc needs the test slice; spring-security-test is not used (tokens are
    // minted with the real JwtService, which exercises the real filter).
    testImplementation(libs.spring.boot.starter.test)

    // Only ClienteLentoExportacionIT uses these: it seeds tens of thousands of
    // shifts, which must not land in the developer's database that the other
    // tests here run against.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

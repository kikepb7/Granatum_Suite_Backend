plugins {
    id("granatum.spring-boot-app")
}

group = "com.granatum"
version = "0.0.1-SNAPSHOT"
description = "Granatum Suite backend"

dependencies {
    implementation(projects.common)
    implementation(projects.inventory)
    implementation(projects.timetracking)
    implementation(projects.auth)

    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.security)

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.datatype)
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

plugins {
    id("java-library")
    id("granatum.spring-boot-service")
    kotlin("plugin.jpa")
}

dependencies {
    implementation(projects.common)

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.postgresql)

    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jackson.datatype)

    testImplementation(kotlin("test"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
}

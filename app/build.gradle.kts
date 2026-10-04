plugins {
    id("granatum.spring-boot-app")
}

group = "com.granatum"
version = "0.0.1-SNAPSHOT"
description = "Granatum Suite backend"

dependencies {
    implementation(projects.common)
    implementation(projects.inventory)

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
}

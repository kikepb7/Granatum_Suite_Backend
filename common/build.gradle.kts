plugins {
    id("java-library")
    id("granatum.kotlin-common")
}

dependencies {
    api(libs.kotlin.reflect)
    api(libs.jackson.module.kotlin)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.datatype)

    implementation(libs.spring.boot.starter.security)

    implementation(libs.jwt.api)
    runtimeOnly(libs.jwt.impl)
    runtimeOnly(libs.jwt.jackson)

    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}

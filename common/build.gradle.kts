plugins {
    id("java-library")
    id("granatum.kotlin-common")
}

dependencies {
    api(libs.kotlin.reflect)
    // Jackson 2's Kotlin module, needed by jjwt-jackson.
    api(libs.jackson.module.kotlin)
    // Jackson 3's, needed by Spring Boot 4's web layer. Both are required: they
    // are different libraries in different namespaces, and the HTTP boundary
    // uses the second one.
    api(libs.jackson3.module.kotlin)

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

plugins {
    id("java-library")
    id("granatum.spring-boot-service")
    kotlin("plugin.jpa")
}

dependencies {
    // The only project dependency, by constitution principle I: what this
    // feature shares with timetracking (CSV format, NIF validation) lives in
    // common, never imported from another feature.
    implementation(projects.common)

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.postgresql)

    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jackson.datatype)
    implementation(libs.jackson.module.kotlin)

    // Reading invoices (research.md D-002) and the PDF of the reports / checking
    // uploaded PDFs (D-010).
    implementation(libs.anthropic.java)
    implementation(libs.pdfbox)

    testImplementation(kotlin("test"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.spring.boot.flyway)
    // Stands in for the Claude API in ReconocedorClaudeTest: no test of the
    // suite talks to the real one (D-019).
    testImplementation(libs.okhttp.mockwebserver)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform {
        // PrecisionReconocimientoIT calls the real API and costs money: it runs
        // only through `claudeRealTest`, by hand, with a key (D-019).
        excludeTags("claude-real")
    }
}

tasks.register<Test>("claudeRealTest") {
    description = "Measures recognition against the real Claude API (needs ANTHROPIC_API_KEY and INVOICES_REFERENCIA_DIR)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("claude-real") }
}

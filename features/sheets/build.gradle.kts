plugins {
    id("java-library")
    id("granatum.spring-boot-service")
    kotlin("plugin.jpa")
}

dependencies {
    // The only project dependency, by constitution principle I: the shifts and
    // invoices it copies reach it through contracts in common, never by
    // importing timetracking or invoices (research.md D-002).
    implementation(projects.common)

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.postgresql)

    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jackson.datatype)
    implementation(libs.jackson.module.kotlin)

    // The official Google client, with explicit service-account credentials
    // only (research.md D-001).
    implementation(libs.google.api.services.sheets)
    implementation(libs.google.api.client)
    implementation(libs.google.auth.library.oauth2.http)
    implementation(libs.google.http.client.gson)

    testImplementation(kotlin("test"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.spring.boot.flyway)
    // Stands in for Google in GoogleSheetsClientTest: no test of the suite
    // talks to the real API (research.md D-009).
    testImplementation(libs.okhttp.mockwebserver)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform {
        // SheetsRealIT writes in a real spreadsheet: it runs only through
        // `sheetsRealTest`, by hand, with PRE credentials (research.md D-009).
        excludeTags("sheets-real")
    }
}

tasks.register<Test>("sheetsRealTest") {
    description = "Writes in a real Google spreadsheet (needs GOOGLE_SHEETS_CREDENTIALS_FILE and SHEETS_TEST_SPREADSHEET_ID)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("sheets-real") }
}

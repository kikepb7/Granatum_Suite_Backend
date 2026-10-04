plugins {
    id("granatum.spring-boot-service")
    id("org.springframework.boot")
    kotlin("plugin.spring")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// The allOpen configuration for JPA annotations lives in
// `granatum.kotlin-common`, which every module applies. It used to be here, but
// this plugin is applied only to `app` - the one module with no entities at all -
// so it had no effect where it was needed. See that file for the details.

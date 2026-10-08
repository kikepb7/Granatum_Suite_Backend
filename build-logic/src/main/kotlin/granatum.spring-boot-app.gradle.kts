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

// `bootRun` gets `.env` as real environment variables, because nothing else
// loads it. application.yml no longer carries fallbacks for the JWT signing key
// or the database password, so without this the app would refuse to start
// locally - which is the intended behaviour when a secret is missing, and the
// reason the convenience belongs here rather than in a default value.
tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
    val dotEnv = loadDotEnv(rootDir)
    dotEnv.forEach { (key, value) -> environment(key, value) }

    // The application's default profile is `prod` (feature 006), so that a
    // deployment that forgets SPRING_PROFILES_ACTIVE does not run as `dev`.
    // Running locally is the opposite case: `bootRun` keeps starting as `dev`
    // unless the environment or `.env` says otherwise.
    if (System.getenv("SPRING_PROFILES_ACTIVE") == null && "SPRING_PROFILES_ACTIVE" !in dotEnv) {
        environment("SPRING_PROFILES_ACTIVE", "dev")
    }
}

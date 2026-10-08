pluginManagement {
    includeBuild("build-logic")
    repositories {
        maven { url = uri("https://repo.spring.io/milestone") }
        maven { url = uri("https://repo.spring.io/snapshot") }
        gradlePluginPortal()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "granatum-suite"

include("app")
include("common")

// Every business feature is its own Gradle module under features/, depending
// only on :common (constitution principle I). Grouped in one directory so the
// root shows at a glance what is product and what is plumbing; the folder is
// not a module of its own, and nothing may be added to it but feature modules.
include("features:inventory")
include("features:timetracking")
include("features:auth")
include("features:invoices")
include("features:absences")
include("features:notifications")

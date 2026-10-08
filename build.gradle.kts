plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

group = "com.granatum"
// The one place the version lives (docs/RAMAS.md): every module inherits it,
// the image publishing workflow checks the tag against it, and /actuator/info
// reports it. SemVer: X.Y.Z on main, release/* and hotfix/*; the next one with
// -SNAPSHOT on develop.
version = "1.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

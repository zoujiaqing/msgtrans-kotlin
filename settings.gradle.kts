pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
rootProject.name = "msgtrans-kotlin"
// neton-io is the I/O foundation. Consumed as a sibling composite build during development so both
// compile with one Kotlin/Native toolchain; the coordinates below match its published artifacts,
// so removing this line makes the build resolve them from Maven Central instead.
includeBuild("../neton-io")
include(":msgtrans", ":msgtrans-bench")

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
rootProject.name = "msgtrans-kotlin"
// neton-io is the I/O foundation. Consumed as a sibling composite build during development (where the
// checkout exists) so both compile with one Kotlin/Native toolchain; its coordinates match the published
// artifacts, so without the sibling (CI) the build resolves them from Maven Central instead.
if (file("../io").isDirectory) includeBuild("../io")
include(":msgtrans", ":msgtrans-bench")

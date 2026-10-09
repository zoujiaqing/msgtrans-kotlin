pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
rootProject.name = "msgtrans-kotlin"
// neton-io is the I/O foundation. Consumed as a sibling composite build during development (where the
// checkout exists) so both compile with one Kotlin/Native toolchain; its coordinates match the published
// artifacts, so without the sibling (CI) the build resolves them from Maven Central instead.
if (file("../io").isDirectory) includeBuild("../io")
// msgtrans-quic's QUIC, likewise from the sibling checkout where it exists.
if (file("../quic").isDirectory) includeBuild("../quic")
include(":msgtrans", ":msgtrans-quic", ":msgtrans-websocket", ":msgtrans-bench")

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
rootProject.name = "msgtrans-kotlin"
// neton-io is the I/O foundation (sibling build, not published).
includeBuild("../neton-io")
include(":msgtrans-core", ":msgtrans-transport")

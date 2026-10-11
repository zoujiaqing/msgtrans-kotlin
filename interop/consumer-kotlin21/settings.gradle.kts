// Downstream projects on the oldest Kotlin the JVM artifacts promise (2.1, the KuiklyUI host line), with no
// includeBuild: they compile and run against published artifacts only (build.gradle.kts says which and why four).
// -PstagedRepositories=<dir>,<dir>… points them at local staging repositories before a release; those then serve
// com.netonstream exclusively, so a same-numbered artifact already on Central cannot stand in for the staged one.
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories {
        val staged = providers.gradleProperty("stagedRepositories").orNull?.split(',')?.map(String::trim).orEmpty()
        if (staged.isNotEmpty()) {
            exclusiveContent {
                forRepositories(*staged.map { path -> maven { url = uri(path) } }.toTypedArray())
                filter { includeGroup("com.netonstream") }
            }
        }
        mavenCentral()
    }
}
rootProject.name = "consumer-kotlin21"
include(":quic", ":msgtrans-quic", ":ws", ":wss")

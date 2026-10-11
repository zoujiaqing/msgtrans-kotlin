// An Android application on Kotlin 2.1 (the KuiklyUI host line) built the way a downstream app is: AGP, a release
// build shrunk and optimized by R8, the libraries from published artifacts only (no includeBuild). Before a release,
// -PstagedRepositories=<dir>,<dir>… points it at local staging repositories, which then serve com.netonstream
// exclusively. interop/android-app-smoke.sh builds, installs and runs it on a device or emulator.
pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
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
        google()
        mavenCentral()
    }
}
rootProject.name = "android-app-kotlin21"

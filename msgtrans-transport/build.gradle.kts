plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    val nativeTargets = listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64())
    nativeTargets.forEach { target ->
        target.binaries {
            executable("requestServer") { entryPoint = "msgtrans.transport.requestServerMain" }
            executable("requestClient") { entryPoint = "msgtrans.transport.requestClientMain" }
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":msgtrans-core"))
            api("com.netonstream.io:neton-io-core")
            api("com.netonstream.io:neton-io-net")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

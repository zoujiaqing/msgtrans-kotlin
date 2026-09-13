plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    val nativeTargets = listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64())
    iosArm64(); iosSimulatorArm64(); iosX64()
    nativeTargets.forEach { target ->
        target.binaries {
            // Benchmark harness (bench/run.sh): one server and one client binary, mode-selected
            // (raw | framed | rpc) so the three layers share identical harness code.
            executable("benchServer") { entryPoint = "msgtrans.transport.bench.benchServerMain" }
            executable("benchClient") { entryPoint = "msgtrans.transport.bench.benchClientMain" }
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

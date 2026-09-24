plugins { kotlin("multiplatform") }
repositories { mavenCentral() }

// Benchmark harness (bench/run.sh): one server and one client binary, mode-selected
// (raw | framed | rpc) so the three layers share identical harness code. Not published.
kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64()).forEach { target ->
        target.binaries {
            executable("benchServer") { entryPoint = "msgtrans.bench.benchServerMain" }
            executable("benchClient") { entryPoint = "msgtrans.bench.benchClientMain" }
        }
    }
    sourceSets {
        commonMain.dependencies { implementation(project(":msgtrans")) }
    }
}

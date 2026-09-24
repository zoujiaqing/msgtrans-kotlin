plugins { kotlin("multiplatform") }
repositories { mavenCentral() }

// One artifact: the wire codec (msgtrans.core) and the session/transport layer (msgtrans.transport)
// ship together, the way the Rust crate does. Additional protocols (WebSocket, QUIC) and the RPC
// layer are separate modules that depend on this one; nothing needs the codec without the session.
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    iosArm64(); iosSimulatorArm64(); iosX64()
    sourceSets {
        commonMain.dependencies {
            api("com.netonstream:neton-io:${project.version}")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("com.squareup.zstd:zstd-kmp:0.4.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

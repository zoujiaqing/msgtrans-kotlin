plugins { kotlin("multiplatform") }
repositories { mavenCentral() }

// Its own version line, starting at 0.1.0 (the root sets msgtrans's).
version = "0.1.0"

// msgtrans over WebSocket: a separate artifact because it brings com.netonstream:websocket (and its HTTP), which TCP
// users (and the JVM / Android artifact) do not need. The targets msgtrans and websocket both provide.
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    iosArm64(); iosSimulatorArm64(); iosX64()

    sourceSets {
        nativeMain.dependencies {
            api(project(":msgtrans"))
            api("com.netonstream:websocket:0.2.0")
        }
        nativeTest.dependencies {
            implementation(kotlin("test"))
            // wss in the tests: TLS on both sides, with certificates generated at test time.
            implementation("com.netonstream:tls-websocket:0.2.0")
            implementation("com.netonstream:quic-testkit:0.2.0")
        }
    }
}

plugins { kotlin("multiplatform") }
repositories { mavenCentral() }

// msgtrans over QUIC: a separate artifact because it brings com.netonstream:quic and OpenSSL, which TCP users (and the
// JVM / Android artifact) do not need. The targets msgtrans and quic both provide.
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    iosArm64(); iosSimulatorArm64(); iosX64()

    sourceSets {
        nativeMain.dependencies {
            api(project(":msgtrans"))
            api("com.netonstream:quic:0.2.0")
        }
        nativeTest.dependencies {
            implementation(kotlin("test"))
            // Test certificates (a CA and leaves) generated with OpenSSL at test time.
            implementation("com.netonstream:quic-testkit:0.2.0")
        }
    }
}

plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        commonMain.dependencies {
            api("com.netonstream.io:neton-io-bytes")
            api("com.netonstream.io:neton-io-codec")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

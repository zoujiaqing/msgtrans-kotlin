plugins { kotlin("multiplatform") }
repositories { mavenCentral() }
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
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
